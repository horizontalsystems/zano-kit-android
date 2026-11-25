package io.horizontalsystems.zanokit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * A transfer the engine built and signed, taken from the error of a broadcast that did not end
 * in a clear answer.
 *
 * @param blobHex the signed transaction, or null when the engine gave only its id.
 */
internal class SignedTransfer(val hash: String, val blobHex: String?)

/**
 * The SOCKS proxy the JVM is set to use, which an embedded Tor installs: the one the engine is
 * given at start. [Proxy.NO_PROXY] when none is set. The address is left unresolved, so host
 * names are looked up by the proxy and not on the device.
 */
internal fun jvmSocksProxy(): Proxy {
    val host = System.getProperty("socksProxyHost").orEmpty()
    if (host.isEmpty()) return Proxy.NO_PROXY

    // Same default as the JVM's own SOCKS sockets when only the host is set
    val rawPort = System.getProperty("socksProxyPort")
    val port = if (rawPort == null) 1080 else rawPort.toIntOrNull() ?: -1
    // Fail rather than connect in a way that differs from what the JVM proxy settings ask for
    if (port !in 1..65535) throw ZanoException("Invalid SOCKS proxy port: $rawPort")

    return Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
}

/**
 * The two daemon calls a transfer is settled with, made by the kit itself and not through the
 * wallet engine, so they can go to a daemon the wallet is not connected to.
 *
 * @param proxy where every request goes through. It is set on the client, which then never
 * connects any other way: with a SOCKS proxy that does not answer the calls fail.
 */
internal class DaemonRpc(proxy: Proxy, timeoutMs: Long = TIMEOUT_MS) {

    private val client = OkHttpClient.Builder()
        .proxy(proxy)
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /** Hands the daemon a signed transaction. True only when it answers that it took it. */
    suspend fun broadcast(daemonAddress: String, blobHex: String): Boolean {
        val params = JSONObject().put("tx_as_hex", blobHex)
        val result = call(daemonAddress, "sendrawtransaction", params)?.optJSONObject("result")
        return result?.optString("status") == STATUS_OK
    }

    /** Whether the daemon has the transaction, in a block or in its pool. */
    suspend fun hasTransaction(daemonAddress: String, hash: String): Boolean {
        val params = JSONObject().put("tx_hash", hash)
        val result = call(daemonAddress, "get_tx_details", params)?.optJSONObject("result")
        return result?.optString("status") == STATUS_OK && result.optJSONObject("tx_info") != null
    }

    private suspend fun call(daemonAddress: String, method: String, params: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("jsonrpc", "2.0").put("id", 0).put("method", method).put("params", params)
                val request = Request.Builder()
                    .url(NodePinger.url(daemonAddress))
                    .post(body.toString().toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    response.body?.string()?.takeIf { response.isSuccessful }?.let { JSONObject(it) }
                }
            } catch (e: Exception) {
                null
            }
        }

    companion object {
        private const val TIMEOUT_MS = 8_000L
        private const val STATUS_OK = "OK"
        private val jsonMediaType = "application/json".toMediaType()
    }
}

/**
 * Settles a transfer whose broadcast did not end in a clear answer: the daemon may have taken
 * it, or not. The same signed transaction is handed to each daemon in turn, which cannot pay
 * twice, and each is asked whether it has it. One daemon that took it or has it is enough: the
 * transfer is in the network.
 *
 * @param daemonAddresses the daemons to go through, in order.
 */
internal class TransferDelivery(
    private val broadcast: suspend (daemonAddress: String, blobHex: String) -> Boolean,
    private val hasTransaction: suspend (daemonAddress: String, hash: String) -> Boolean,
    private val daemonAddresses: List<String>,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    constructor(rpc: DaemonRpc, daemonAddresses: List<String>) : this(rpc::broadcast, rpc::hasTransaction, daemonAddresses)

    /**
     * The daemon that took the transfer or has it. Null says nothing: it may still be out there.
     */
    suspend fun deliver(transfer: SignedTransfer): String? =
        withTimeoutOrNull(timeoutMs) {
            daemonAddresses.firstOrNull { daemon ->
                // A daemon answers a transaction it already holds in a block with a refusal,
                // so a broadcast that was not taken is followed by the question
                (transfer.blobHex != null && broadcast(daemon, transfer.blobHex)) || hasTransaction(daemon, transfer.hash)
            }
        }

    companion object {
        private const val TIMEOUT_MS = 30_000L
    }
}

/**
 * One send, start to end, with what the kit does about a daemon that fails.
 *
 * Before the broadcast nothing was sent, so the wallet moves to another daemon and the transfer
 * is built again there, once. After a broadcast that did not end in a clear answer the same
 * signed transaction is settled with the daemons, and the wallet follows it to the daemon that
 * has it. Nothing is ever built a second time once a broadcast may have gone out.
 *
 * @param transfer builds, signs and broadcasts through the wallet's daemon; gives the id.
 * @param otherDaemonThatAnswers another daemon the kit may use that is ready, or null.
 * @param deliver see [TransferDelivery.deliver].
 */
internal class SendFlow(
    private val transfer: suspend () -> String,
    private val otherDaemonThatAnswers: suspend () -> String?,
    private val setDaemon: (String) -> Unit,
    private val deliver: suspend (SignedTransfer) -> String?,
) {
    suspend fun run(): SendResult =
        try {
            SendResult.Sent(transferOnAWorkingDaemon())
        } catch (error: InsufficientFundsException) {
            SendResult.NotSent(SendResult.Reason.InsufficientFunds, error.message)
        } catch (error: NodeUnreachableException) {
            SendResult.NotSent(SendResult.Reason.NodeUnreachable, error.message)
        } catch (error: BroadcastUnconfirmedException) {
            SendResult.Unknown(error.transfer?.hash, error.message)
        } catch (error: ZanoException) {
            SendResult.NotSent(SendResult.Reason.Failed, error.message)
        }

    private suspend fun transferOnAWorkingDaemon(): String =
        try {
            settledTransfer()
        } catch (error: NodeUnreachableException) {
            val other = otherDaemonThatAnswers() ?: throw error
            setDaemon(other)
            settledTransfer()
        }

    private suspend fun settledTransfer(): String =
        try {
            transfer()
        } catch (error: ZanoException) {
            // The daemon may have taken the transaction although the wallet was told it failed:
            // its answer was lost, or it refused a repeat of a broadcast it already had. The
            // engine names the transaction, so it is settled with the daemons directly.
            val signed = error.transfer ?: throw error
            val daemon = deliver(signed) ?: throw error
            setDaemon(daemon)
            signed.hash
        }
}

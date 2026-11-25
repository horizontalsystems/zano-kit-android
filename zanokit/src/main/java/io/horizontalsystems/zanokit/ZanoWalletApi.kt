package io.horizontalsystems.zanokit

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

class ZanoWalletApi(private val walletId: Long) {

    fun closeWallet() {
        synchronized(nativeLock) {
            ZanoNative.closeWallet(walletId)
            ZanoNative.deinit()
        }
    }

    fun invoke(method: String, params: Map<String, Any>? = null): JSONObject? {
        val request = JSONObject().apply {
            put("method", method)
            params?.let { put("params", JSONObject(it)) }
        }
        val raw = synchronized(nativeLock) { ZanoNative.invoke(walletId, request.toString()) } ?: return null
        return parseResult(raw)
    }

    fun getWalletStatus(): JSONObject? {
        val raw = synchronized(nativeLock) { ZanoNative.getWalletStatus(walletId) } ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    // Returns list of balance entries: [{asset_info:{...}, total, unlocked, awaiting_in, awaiting_out}]
    fun getBalances(): List<JSONObject> {
        val result = invoke("getbalance") ?: return emptyList()
        val arr = result.optJSONArray("balances") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    // Returns list of transfer entries from get_recent_txs_and_info3.
    // The v1 call is legacy: its entries carry only the native-coin amount/is_income
    // and no per-asset subtransfers, so confidential asset transfers are invisible.
    fun getRecentTransactions(offset: Int = 0, count: Int = 1000): List<JSONObject> {
        val params = mapOf("offset" to offset, "count" to count, "update_provision_info" to true)
        val result = invoke("get_recent_txs_and_info3", params) ?: return emptyList()
        val arr = result.optJSONArray("transfers") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    // Returns tx_hash of submitted transaction.
    fun transfer(
        toAddress: String,
        assetId: String,
        amount: Long,
        fee: Long,
        mixin: Int = 10,
        comment: String?,
    ): String {
        val destination = JSONObject().apply {
            put("address", toAddress)
            put("amount", amount)
            if (assetId != ZANO_ASSET_ID) put("asset_id", assetId)
        }
        val destinations = JSONArray().apply { put(destination) }
        val paramsObj = JSONObject().apply {
            put("destinations", destinations)
            put("fee", fee)
            put("mixin", mixin)
            if (!comment.isNullOrEmpty()) put("comment", comment)
        }
        val request = JSONObject().apply {
            put("method", "transfer")
            put("params", paramsObj)
        }
        val raw = synchronized(nativeLock) { ZanoNative.invoke(walletId, request.toString()) }
        return parseTransferResponse(raw)
    }

    fun store() {
        invoke("store")
    }

    // Adds the asset to the wallet's local whitelist (m_custom_assets). The wallet
    // resolves the descriptor from the connected daemon and persists it in the
    // wallet file, so once added the asset is tracked without depending on the
    // global whitelist fetch from api.zano.org. Requires a live daemon connection.
    fun addAssetToWhitelist(assetId: String): Boolean =
        invoke("assets_whitelist_add", mapOf("asset_id" to assetId)) != null

    companion object {
        // Serializes every engine-touching native call process-wide: the native
        // engine is a global singleton and closeWallet/deinit must never overlap
        // an in-flight call (use-after-free otherwise — cancelling a coroutine
        // does not interrupt a blocking JNI call already executing). Pure crypto
        // helpers (address generation/validation, seed words) don't take it.
        private val nativeLock = Any()

        fun init(host: String, port: String, workingDir: String, logLevel: Int) {
            synchronized(nativeLock) { ZanoNative.init2(host, port, workingDir, logLevel) }
        }

        // An empty host connects directly
        fun setSocksProxy(host: String, port: Int) {
            // The port is narrowed to uint16_t natively, so out-of-range values would wrap
            require(host.isEmpty() || port in 1..65535) { "Invalid SOCKS proxy port: $port" }
            synchronized(nativeLock) { ZanoNative.setSocksProxy(host, port) }
        }

        fun isWalletExist(path: String): Boolean = ZanoNative.isWalletExist(path)

        fun openWallet(path: String, password: String): String? =
            synchronized(nativeLock) { ZanoNative.openWallet(path, password) }

        fun restoreWallet(seed: String, path: String, password: String, seedPassword: String): String? =
            synchronized(nativeLock) { ZanoNative.restoreWallet(seed, path, password, seedPassword) }

        /**
         * Points the engine at another daemon. Open wallets stay open and go on from the
         * height they have. The connection in use is dropped; the next call opens a new one.
         */
        fun resetConnectionUrl(daemonAddress: String) {
            val (host, port) = parseAddress(daemonAddress)
            syncCall("reset_connection_url", 0, "$host:$port")
        }

        fun syncCall(method: String, instanceId: Long, params: String): String? =
            synchronized(nativeLock) { ZanoNative.syncCall(method, instanceId, params) }

        fun generateAddress(seed: String, seedPassword: String): String? =
            ZanoNative.generateAddress(seed, seedPassword)

        fun generateAddressFromDerivation(secretDerivationHex: String, isAuditable: Boolean): String? =
            ZanoNative.generateAddressFromDerivation(secretDerivationHex, isAuditable)

        fun getTimestampFromWord(word: String): Long = ZanoNative.getTimestampFromWord(word)

        // Keeps schema in host so C++ lib enables SSL.
        // "https://node:443" → ("https://node", "443")
        // "http://node:8081" → ("http://node", "8081")
        // "node:11211"       → ("node", "11211")
        fun parseAddress(address: String): Pair<String, String> {
            for (schema in listOf("https://", "http://")) {
                if (address.startsWith(schema)) {
                    val afterSchema = address.removePrefix(schema).substringBefore("/")
                    val colonIdx = afterSchema.lastIndexOf(':')
                    if (colonIdx >= 0) {
                        val portStr = afterSchema.substring(colonIdx + 1)
                        val hostOnly = afterSchema.substring(0, colonIdx)
                        if (portStr.isNotEmpty()) return (schema + hostOnly) to portStr
                    }
                    val defaultPort = if (schema.startsWith("https")) "443" else "80"
                    return (schema + afterSchema) to defaultPort
                }
            }
            val colonIdx = address.lastIndexOf(':')
            if (colonIdx >= 0) {
                val host = address.substring(0, colonIdx)
                val port = address.substring(colonIdx + 1)
                if (host.isNotEmpty() && port.isNotEmpty()) return host to port
            }
            return address to "8081"
        }

        fun isValidAddress(address: String): Boolean {
            val raw = ZanoNative.getAddressInfo(address) ?: return false
            return try {
                val json = JSONObject(raw)
                val result = json.optJSONObject("result") ?: json
                result.optBoolean("valid", false)
            } catch (_: Exception) {
                false
            }
        }

        fun getCurrentTxFee(priority: Int): Long {
            return synchronized(nativeLock) { ZanoNative.getCurrentTxFee(priority.toLong()) }
        }

        // Parses {"result":{...},"error":{"code":Int,"message":String}} → result object or throws
        fun parseResponse(raw: String): Result<JSONObject> {
            return try {
                val json = JSONObject(raw)
                val error = json.optJSONObject("error")
                if (error != null && error.optInt("code", 0) != 0) {
                    Result.failure(ZanoException(error.optString("message", "Unknown error")))
                } else {
                    val result = json.optJSONObject("result")
                        ?: return Result.failure(ZanoException("No result field"))
                    Result.success(result)
                }
            } catch (e: Exception) {
                Timber.e(e, "parseResponse failed")
                Result.failure(e)
            }
        }
    }

    private fun parseResult(raw: String): JSONObject? {
        return parseResponse(raw).getOrNull()
    }

}

private val insufficientFundsPattern = Regex("NOT_ENOUGH_MONEY|not enough money|insufficient", RegexOption.IGNORE_CASE)
private val noConnectionPattern = Regex("no connection to daemon", RegexOption.IGNORE_CASE)

// The daemon answered that it is busy, so it did not take the transaction
private val daemonBusyPattern = Regex("DAEMON_IS_BUSY", RegexOption.IGNORE_CASE)

// The wallet names the daemon call that got no answer (with patches/0005; an engine without it
// names none, and every such failure then reads as an unanswered broadcast). These are the calls
// that only read from the daemon, and the daemon's own answer that it is offline: none of them
// hands it the transaction.
private val readCallUnansweredPattern = Regex(
    "no connection to daemon,\\s*request\\s*=\\s*(?:getrandom_outs\\d*\\.bin|get_o_indexes\\.bin|getblocks\\.bin|" +
        "get_blocks_details|get_tx_pool|Transfer attempt while daemon offline)\\s*$",
    RegexOption.IGNORE_CASE,
)

/**
 * The hash of the transfer in the engine's answer to a transfer request.
 *
 * Only an answer that carries an error says the transfer failed. One that is missing, cannot be
 * read or has no hash says nothing about the broadcast, which may have happened before the
 * answer was put together: it is reported as unconfirmed, never as a failure to send again.
 */
internal fun parseTransferResponse(raw: String?): String {
    if (raw == null) throw BroadcastUnconfirmedException("transfer: null response")

    val json = try {
        JSONObject(raw)
    } catch (_: Exception) {
        throw BroadcastUnconfirmedException("transfer: unreadable response")
    }

    val error = json.optJSONObject("error")
    val result = json.optJSONObject("result")
    // The engine's own errors carry a text code, which reads as 0, and come without a result
    if (error != null && (error.optInt("code", 0) != 0 || result == null)) {
        throw parseTransferError(error.optString("message"))
    }

    val hash = result?.optString("tx_hash").orEmpty()
    if (hash.isEmpty()) throw BroadcastUnconfirmedException("transfer: no hash in the response")
    return hash
}

// With patches/0006 the engine adds the transaction to the error of a broadcast that got no
// answer: its id and the signed bytes as hex
private val unansweredTransferPattern = Regex(
    "request\\s*=\\s*sendrawtransaction,\\s*tx_id\\s*=\\s*([0-9a-f]{64}),\\s*tx_blob\\s*=\\s*([0-9a-f]+)",
    RegexOption.IGNORE_CASE,
)

// A transaction the daemon refused. The wallet tries the broadcast again when it gets no answer,
// and a daemon that took the first try may refuse the repeat, which reads the same as a
// transaction it never took. The engine names the transaction, so the daemons can be asked.
private val rejectedTransferPattern = Regex(
    "rejected by daemon,\\s*status\\s*=\\s*[^,]*,\\s*tx_id\\s*=\\s*([0-9a-f]{64})",
    RegexOption.IGNORE_CASE,
)

internal fun parseTransferError(message: String?): ZanoException {
    if (message == null) return SendFailedException(null)
    if (insufficientFundsPattern.containsMatchIn(message)) return InsufficientFundsException(message)
    if (readCallUnansweredPattern.containsMatchIn(message)) return NodeUnreachableException(message)
    // Any other call without an answer is taken for the broadcast: "sendrawtransaction" today,
    // and whatever a later wallet engine calls it. For the broadcast the wallet raises the same
    // error as for the calls before it, although the daemon may have taken the transaction and
    // only its answer was lost. A name this list does not know must not read as safe to repeat.
    if (noConnectionPattern.containsMatchIn(message)) {
        val match = unansweredTransferPattern.find(message)
            ?: return BroadcastUnconfirmedException(message)
        val (hash, blobHex) = match.destructured
        // The bytes run to kilobytes and are of no use in a message
        val text = message.substring(0, match.range.first) + "request = sendrawtransaction, tx_id = $hash"
        return BroadcastUnconfirmedException(text, SignedTransfer(hash.lowercase(), blobHex))
    }
    if (daemonBusyPattern.containsMatchIn(message)) return NodeUnreachableException(message)

    val rejected = rejectedTransferPattern.find(message)
    return SendFailedException(message, rejected?.let { SignedTransfer(it.groupValues[1].lowercase(), blobHex = null) })
}

open class ZanoException internal constructor(
    message: String?,
    // The transfer a failed send left behind, when the engine named it: the daemons can be
    // asked for it, and given it again
    internal val transfer: SignedTransfer?,
) : Exception(message) {
    constructor(message: String?) : this(message, null)
}
internal class InsufficientFundsException(message: String?) : ZanoException(message)
// A daemon call failed or timed out before the broadcast: nothing was sent, sending again is safe.
internal class NodeUnreachableException(message: String?) : ZanoException(message)
// The transaction was handed to the daemon and no answer came back, and no daemon the kit could
// ask has it: it may still be in the network. Sending again can pay twice.
internal class BroadcastUnconfirmedException(message: String?, transfer: SignedTransfer?) : ZanoException(message, transfer) {
    constructor(message: String?) : this(message, null)
}
internal class SendFailedException(message: String?, transfer: SignedTransfer?) : ZanoException(message, transfer) {
    constructor(message: String?) : this(message, null)
}

package io.horizontalsystems.zanokit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Asks daemons for their state with getinfo, all at once. Needs no wallet. The requests go
 * through the JVM's proxy settings, the same ones the engine is given for Tor.
 */
internal class NodePinger(timeoutMs: Long = TIMEOUT_MS) {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            // A hard limit for the whole request, so a daemon that answers slowly enough to
            // keep the read alive cannot hold a ping
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    suspend fun ping(daemonAddresses: List<String>): List<NodePingResult> = coroutineScope {
        daemonAddresses.map { address -> async { ping(address) } }.awaitAll()
    }

    private suspend fun ping(daemonAddress: String): NodePingResult = withContext(Dispatchers.IO) {
        try {
            val start = System.nanoTime()
            client.newCall(request(daemonAddress)).execute().use { response ->
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                val body = response.body?.string()
                if (!response.isSuccessful || body == null) return@withContext unreachable(daemonAddress)

                val height = syncedHeight(body) ?: return@withContext unreachable(daemonAddress)
                NodePingResult(daemonAddress, elapsedMs, height, isValid = true)
            }
        } catch (e: Exception) {
            unreachable(daemonAddress)
        }
    }

    private fun request(daemonAddress: String): Request =
        Request.Builder()
            .url(daemonAddress.trimEnd('/') + "/json_rpc")
            .post(GET_INFO.toRequestBody(jsonMediaType))
            .build()

    private fun unreachable(daemonAddress: String) =
        NodePingResult(daemonAddress, Double.MAX_VALUE, height = 0, isValid = false)

    companion object {
        private const val TIMEOUT_MS = 5_000L

        private val jsonMediaType = "application/json".toMediaType()

        // flags 0 leaves out the statistics that are slow to compute
        private const val GET_INFO = """{"jsonrpc":"2.0","id":0,"method":"getinfo","params":{"flags":0}}"""

        // daemon_network_state of a daemon that has caught up with the network; while it is
        // connecting or still downloading blocks a wallet cannot sync from it
        private const val NETWORK_STATE_ONLINE = 2

        /** The daemon's height, or null when the answer is not from a daemon that is ready. */
        fun syncedHeight(body: String): Long? = try {
            val result = JSONObject(body).getJSONObject("result")
            val ready = result.optString("status") == "OK" &&
                result.optInt("daemon_network_state", -1) == NETWORK_STATE_ONLINE
            result.optLong("height", 0).takeIf { ready && it > 0 }
        } catch (e: Exception) {
            null
        }
    }
}

package io.horizontalsystems.zanokit

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Finds a transfer the daemon took although the wallet was told the send failed.
 *
 * A failed send returns no transaction hash. The wallet scans the daemon's pool every few
 * seconds and lists a transaction that spends its own outputs as an outgoing transfer, so a
 * transfer of the sent amount that was not listed before the send is the one that went out.
 *
 * [recentTransfers] is the newest part of the wallet's list, read with the same size before and
 * after the send: entries are only added at its top, so one that was not there before is new.
 */
internal class SentTransferLookup(
    private val recentTransfers: () -> List<JSONObject>,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val intervalMs: Long = INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    /** Hashes listed before the send. Null when the list cannot be read, and then nothing can be told apart. */
    fun knownHashes(): Set<String>? {
        val hashes = try {
            recentTransfers().map { it.optString("tx_hash") }.filter { it.isNotEmpty() }.toSet()
        } catch (error: Exception) {
            return null
        }
        // A wallet that can send has received something, so an empty list was not read
        return hashes.takeIf { it.isNotEmpty() }
    }

    /**
     * The hash of a transfer of [amount] that appeared after [knownBefore] was taken, or null.
     * [toSelf] is a send to the wallet's own address, which the wallet lists as its fee alone.
     */
    suspend fun find(knownBefore: Set<String>?, assetId: String, amount: Long, fee: Long, toSelf: Boolean = false): String? {
        if (knownBefore == null) return null
        val deadline = now() + timeoutMs
        while (true) {
            val found = try {
                recentTransfers().firstOrNull { isSentTransfer(it, knownBefore, assetId, amount, fee, toSelf) }
            } catch (error: Exception) {
                null
            }
            if (found != null) return found.optString("tx_hash")
            if (now() >= deadline) return null
            sleep(intervalMs)
        }
    }

    private fun isSentTransfer(
        tx: JSONObject,
        knownBefore: Set<String>,
        assetId: String,
        amount: Long,
        fee: Long,
        toSelf: Boolean,
    ): Boolean {
        val hash = tx.optString("tx_hash")
        if (hash.isEmpty() || hash in knownBefore) return false

        val groups = tx.optJSONArray("subtransfers_by_pid") ?: return false
        for (g in 0 until groups.length()) {
            val subtransfers = groups.getJSONObject(g).optJSONArray("subtransfers") ?: continue
            for (i in 0 until subtransfers.length()) {
                val sub = subtransfers.getJSONObject(i)
                if (sub.optBoolean("is_income", false)) continue
                val subAssetId = sub.optString("asset_id").ifEmpty { ZANO_ASSET_ID }
                val sent = sub.optLong("amount", 0)
                // What was sent came back, so the wallet lost the fee and nothing else
                if (toSelf && subAssetId == ZANO_ASSET_ID && sent == fee) return true
                if (subAssetId != assetId) continue
                // The wallet lists a native transfer together with its fee
                if (sent == amount || (assetId == ZANO_ASSET_ID && sent == amount + fee)) return true
            }
        }
        return false
    }

    companion object {
        // Two pool scans of the wallet engine, which run 5 seconds apart
        private const val TIMEOUT_MS = 12_000L
        private const val INTERVAL_MS = 1_000L

        // How much of the wallet's list is read. The transfer looked for is at its top.
        const val LIST_SIZE = 50
    }
}

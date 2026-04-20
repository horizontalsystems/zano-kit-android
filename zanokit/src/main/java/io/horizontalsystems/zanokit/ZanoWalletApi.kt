package io.horizontalsystems.zanokit

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

class ZanoWalletApi(private val walletId: Long) {

    fun invoke(method: String, params: Map<String, Any>? = null): JSONObject? {
        val request = JSONObject().apply {
            put("method", method)
            params?.let { put("params", JSONObject(it)) }
        }
        val raw = ZanoNative.invoke(walletId, request.toString()) ?: return null
        return parseResult(raw)
    }

    fun getWalletStatus(): JSONObject? {
        val raw = ZanoNative.getWalletStatus(walletId) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    // Returns list of balance entries: [{asset_info:{...}, total, unlocked, awaiting_in, awaiting_out}]
    fun getBalances(): List<JSONObject> {
        val result = invoke("getbalance") ?: return emptyList()
        val arr = result.optJSONArray("balances") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    // Returns list of transfer entries from get_recent_txs_and_info
    fun getRecentTransactions(offset: Int = 0, count: Int = 1000): List<JSONObject> {
        val params = mapOf("offset" to offset, "count" to count, "update_provision_info" to true)
        val result = invoke("get_recent_txs_and_info", params) ?: return emptyList()
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
        val raw = ZanoNative.invoke(walletId, request.toString())
            ?: throw ZanoException("transfer: null response")
        val result = parseResult(raw) ?: run {
            val errorMsg = try {
                JSONObject(raw).optJSONObject("error")?.optString("message")
            } catch (_: Exception) { null }
            throw parseTransferError(errorMsg)
        }
        return result.getString("tx_hash")
    }

    fun store() {
        invoke("store")
    }

    companion object {
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
            return ZanoNative.getCurrentTxFee(priority.toLong())
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

    private fun parseTransferError(message: String?): ZanoException {
        if (message != null) {
            val insufficientFundsPattern = Regex("NOT_ENOUGH_MONEY|not enough money|insufficient", RegexOption.IGNORE_CASE)
            if (insufficientFundsPattern.containsMatchIn(message)) return InsufficientFundsException(message)
        }
        return SendFailedException(message)
    }
}

open class ZanoException(message: String?) : Exception(message)
class InsufficientFundsException(message: String?) : ZanoException(message)
class SendFailedException(message: String?) : ZanoException(message)

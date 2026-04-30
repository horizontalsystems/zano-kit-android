package io.horizontalsystems.zanokit

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.horizontalsystems.zanokit.storage.ZanoStorage
import io.horizontalsystems.zanokit.util.deriveZanoSecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ZanoCore(
    private val context: Context,
    private val wallet: ZanoWallet,
    private val walletId: String,
    private val daemonAddress: String,
    private val networkType: NetworkType,
    private val storage: ZanoStorage,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var nativeWalletId: Long = -1
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var walletAddress: String = ""
    private lateinit var api: ZanoWalletApi
    private lateinit var syncManager: SyncStateManager

    private val _syncStateFlow = MutableStateFlow<SyncState>(SyncState.NotSynced.NotStarted)
    val syncStateFlow: StateFlow<SyncState> = _syncStateFlow

    private val _assetsFlow = MutableStateFlow<List<AssetInfo>>(emptyList())
    val assetsFlow: StateFlow<List<AssetInfo>> = _assetsFlow

    private val _balancesFlow = MutableStateFlow<List<BalanceInfo>>(emptyList())
    val balancesFlow: StateFlow<List<BalanceInfo>> = _balancesFlow

    private val _transactionsFlow = MutableStateFlow<List<TransactionInfo>>(emptyList())
    val transactionsFlow: StateFlow<List<TransactionInfo>> = _transactionsFlow

    suspend fun start() = withContext(Dispatchers.IO) {
        try {
            doStart()
        } catch (e: Exception) {
            if (e !is RestoreHeightDontMatchException)
                _syncStateFlow.value = SyncState.NotSynced.StartError(e.message)

            throw e
        }
    }

    private fun doStart() {
        val (host, port) = ZanoWalletApi.parseAddress(daemonAddress)
        val workingDir = walletDir()
        File(workingDir).mkdirs()

        ZanoNative.init2(host, port, workingDir, 0)

        // restore_from_derivations prepends workingDir/wallets/ internally, so BIP39 wallets
        // live at workingDir/wallets/wallet while legacy wallets live at workingDir/wallet.
        val walletExisted: Boolean
        val openResult: String? = when (wallet) {
            is ZanoWallet.Bip39 -> {
                val path = "$workingDir/wallets/wallet"
                walletExisted = ZanoNative.isWalletExist(path)
                if (walletExisted) ZanoNative.openWallet("wallet", "")
                else restoreFromBip39(wallet)
            }

            is ZanoWallet.Legacy -> {
                val path = "$workingDir/wallet"
                walletExisted = ZanoNative.isWalletExist(path)
                if (walletExisted) ZanoNative.openWallet(path, "")
                else ZanoNative.restoreWallet(wallet.seed, path, "", wallet.seedPassword)
            }
        }

        openResult ?: throw ZanoException("Failed to open/restore wallet")

        // Check for error in response (e.g. WRONG_SEED)
        val parsed = JSONObject(openResult)
        val errorCode = parsed.optJSONObject("error")?.optString("code")
        if (!errorCode.isNullOrEmpty() && errorCode != "0") {
            throw ZanoException(errorCode)
        }

        val resultObj = parsed.optJSONObject("result")
            ?: throw ZanoException("No result in open/restore response: $openResult")
        nativeWalletId = resultObj.optLong("wallet_id", -1)
        if (nativeWalletId < 0) throw ZanoException("Invalid wallet_id: $openResult")

        api = ZanoWalletApi(nativeWalletId)

        // BIP39: detect creation-timestamp drift against persisted value.
        if (wallet is ZanoWallet.Bip39) {
            val stored = storage.getCreationTimestamp()
            if (walletExisted && stored != null && stored != wallet.creationTimestamp) {
                ZanoNative.closeWallet(nativeWalletId)
                nativeWalletId = -1
                throw RestoreHeightDontMatchException()
            }
            if (stored == null || stored != wallet.creationTimestamp) {
                storage.saveCreationTimestamp(wallet.creationTimestamp)
            }
        }

        walletAddress = resultObj.optJSONObject("wi")?.optString("address") ?: ""

        val restoreHeight = wallet.restoreHeight

        // Emit cached state immediately so UI isn't blank while syncing
        _balancesFlow.value = storage.getAllBalances()
        _assetsFlow.value = storage.getAllAssets()
        _transactionsFlow.value = storage.getTransactions()

        syncManager = SyncStateManager(api, restoreHeight)
        syncManager.onSyncedPoll = { refresh() }
        syncManager.onBlockHeightsChanged = { wh, dh -> storage.saveBlockHeights(wh, dh) }
        syncManager.start(scope)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isConnected = cm.activeNetwork?.let {
            cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: false
        syncManager.onNetworkAvailabilityChange(isConnected)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                syncManager.onNetworkAvailabilityChange(true)
            }

            override fun onLost(network: Network) {
                syncManager.onNetworkAvailabilityChange(false)
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
        scope.launch {
            syncManager.syncStateFlow.collect { newState ->
                _syncStateFlow.value = newState
                when (newState) {
                    is SyncState.Synced -> runCatching { api.store() }
                    is SyncState.Syncing -> if (syncManager.chunkOfBlocksSynced) {
                        refresh()
                        runCatching { api.store() }
                        syncManager.walletStored()
                    }

                    else -> Unit
                }
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        networkCallback?.let {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(it)
            networkCallback = null
        }
        if (::syncManager.isInitialized) syncManager.stop()
        if (nativeWalletId >= 0) {
            runCatching { api.store() }
            ZanoNative.closeWallet(nativeWalletId)
            nativeWalletId = -1
        }
        scope.cancel()
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            fetchBalances()
            fetchTransactions()
        }
    }

    val receiveAddress: String get() = walletAddress

    val lastBlockHeight: Long?
        get() = if (::syncManager.isInitialized) syncManager.currentWalletHeight.takeIf { it > 0 } else null

    val lastDaemonHeight: Long?
        get() = if (::syncManager.isInitialized) syncManager.currentDaemonHeight.takeIf { it > 0 } else null

    val lastBlockUpdatedFlow get() = if (::syncManager.isInitialized) syncManager.lastBlockUpdatedFlow else null

    private fun fetchBalances() {
        if (syncManager.isInLongRefresh) return
        val entries = api.getBalances()
        val assets = mutableListOf<AssetInfo>()
        val balances = mutableListOf<BalanceInfo>()

        for (entry in entries) {
            val info = entry.optJSONObject("asset_info") ?: continue
            val assetId = info.optString("asset_id")
            if (assetId.isEmpty()) continue

            assets.add(
                AssetInfo(
                    assetId = assetId,
                    ticker = info.optString("ticker"),
                    fullName = info.optString("full_name"),
                    decimalPoint = info.optInt("decimal_point", 12),
                    totalMaxSupply = info.optLong("total_max_supply"),
                    currentSupply = info.optLong("current_supply"),
                    metaInfo = info.optString("meta_info").takeIf { it.isNotEmpty() },
                )
            )
            balances.add(
                BalanceInfo(
                    assetId = assetId,
                    total = entry.optLong("total"),
                    unlocked = entry.optLong("unlocked"),
                    awaitingIn = entry.optLong("awaiting_in"),
                    awaitingOut = entry.optLong("awaiting_out"),
                )
            )
        }

        storage.updateAssets(assets)
        storage.updateBalances(balances)
        _assetsFlow.value = assets
        _balancesFlow.value = balances
    }

    private fun fetchTransactions() {
        if (syncManager.isInLongRefresh) return
        val transfers = api.getRecentTransactions()
        val sentMap = storage.getAllSentTransfers().associateBy { it.txHash }
        val fetched = mutableListOf<TransactionInfo>()

        for (tx in transfers) {
            val txHash = tx.optString("tx_hash")
            val isIncome = tx.optBoolean("is_income", false)
            val fee = tx.optLong("fee", 0)
            val height = tx.optLong("height", 0)
            val timestamp = tx.optLong("timestamp", 0)
            val comment = tx.optString("comment").takeIf { it.isNotEmpty() }

            val remoteArr = tx.optJSONArray("remote_addresses")
            var remoteAddress: String? = if (remoteArr != null && remoteArr.length() > 0) remoteArr.getString(0) else null
            if (remoteAddress == null) remoteAddress = sentMap[txHash]?.address

            var recordsCreated = 0
            val subtransfers = tx.optJSONArray("subtransfers")

            if (subtransfers != null && subtransfers.length() > 0) {
                for (i in 0 until subtransfers.length()) {
                    val sub = subtransfers.getJSONObject(i)
                    val assetId = sub.optString("asset_id").ifEmpty { ZANO_ASSET_ID }
                    val amount = sub.optLong("amount", 0)
                    val subIsIncome = sub.optBoolean("is_income", isIncome)

                    // Fee is tracked separately; skip the fee-sized outgoing ZANO entry
                    if (!subIsIncome && assetId == ZANO_ASSET_ID && amount == fee) continue

                    val type = if (subIsIncome) TransactionType.incoming else TransactionType.outgoing
                    // For outgoing native ZANO the displayed amount is net (amount minus fee)
                    val storedAmount = if (!subIsIncome && assetId == ZANO_ASSET_ID) amount - fee else amount

                    fetched.add(buildTx(txHash, assetId, type, height, storedAmount, fee, timestamp, comment, remoteAddress))
                    recordsCreated++
                }
            } else {
                // No subtransfers — legacy / fallback path
                val type = if (isIncome) TransactionType.incoming else TransactionType.outgoing
                val rawAmount = tx.optLong("amount", 0)
                val storedAmount = if (isIncome) Math.abs(rawAmount) else Math.abs(rawAmount) - fee
                fetched.add(buildTx(txHash, ZANO_ASSET_ID, type, height, storedAmount, fee, timestamp, comment, remoteAddress))
                recordsCreated++
            }

            // Self-send detection: triggered when no records were created for an outgoing tx
            if (recordsCreated == 0 && !isIncome) {
                val employed = tx.optJSONObject("employed_entries") ?: continue
                val receiveArr = employed.optJSONArray("receive")
                val spentArr = employed.optJSONArray("spent")

                val receivedByAsset = mutableMapOf<String, MutableList<Long>>()
                val spentByAsset = mutableMapOf<String, Long>()

                receiveArr?.let {
                    for (i in 0 until it.length()) {
                        val e = it.getJSONObject(i)
                        val aid = e.optString("asset_id").ifEmpty { ZANO_ASSET_ID }
                        receivedByAsset.getOrPut(aid) { mutableListOf() }.add(e.optLong("amount", 0))
                    }
                }
                spentArr?.let {
                    for (i in 0 until it.length()) {
                        val e = it.getJSONObject(i)
                        val aid = e.optString("asset_id").ifEmpty { ZANO_ASSET_ID }
                        spentByAsset[aid] = (spentByAsset[aid] ?: 0L) + e.optLong("amount", 0)
                    }
                }

                val candidates = mutableListOf<TransactionInfo>()
                for ((aid, amounts) in receivedByAsset) {
                    val totalReceived = amounts.sum()
                    val totalSpent = spentByAsset[aid] ?: continue
                    val matched = if (aid == ZANO_ASSET_ID) totalReceived + fee == totalSpent else totalReceived == totalSpent
                    if (totalReceived > 0 && matched) {
                        val sentAmount = sentMap[txHash]?.takeIf { it.assetId == aid }?.amount ?: amounts.first()
                        val selfAddress = walletAddress.takeIf { it.isNotEmpty() }
                        candidates.add(buildTx(txHash, aid, TransactionType.sentToSelf, height, sentAmount, fee, timestamp, comment, selfAddress))
                    }
                }
                // Prefer non-native asset entry; fall back to ZANO
                val toAdd = candidates.firstOrNull { it.assetId != ZANO_ASSET_ID } ?: candidates.firstOrNull()
                if (toAdd != null) fetched.add(toAdd)
            }
        }

        val sorted = fetched.sortedByDescending { it.timestamp }
        storage.updateTransactions(sorted)
        _transactionsFlow.value = sorted
    }

    private fun buildTx(
        hash: String,
        assetId: String,
        type: TransactionType,
        height: Long,
        amount: Long,
        fee: Long,
        timestamp: Long,
        memo: String?,
        recipientAddress: String?,
    ) = TransactionInfo(
        uid = "${hash}_${assetId}",
        hash = hash,
        assetId = assetId,
        type = type,
        blockHeight = height,
        amount = amount,
        fee = fee,
        isPending = height == 0L,
        isFailed = false,
        timestamp = timestamp,
        memo = memo,
        recipientAddress = recipientAddress,
    )

    // Submits a transfer and caches the sent info for tx enrichment on next poll
    fun send(toAddress: String, assetId: String, amount: Long, fee: Long, memo: String?): String {
        val txHash = api.transfer(toAddress, assetId, amount, fee, comment = memo)
        storage.saveSentTransfer(txHash, assetId, amount, toAddress)
        refresh()
        return txHash
    }

    fun getBalance(assetId: String): BalanceInfo? = storage.getBalance(assetId)

    private fun restoreFromBip39(wallet: ZanoWallet.Bip39): String? {
        val secretDerivation = deriveZanoSecretKey(wallet.mnemonic, wallet.passphrase)
        val params = JSONObject().apply {
            put("pass", "")
            put("path", "wallet")  // relative — native prepends workingDir/wallets/ internally
            put("secret_derivation", secretDerivation)
            put("is_auditable", false)
            put("creation_timestamp", wallet.creationTimestamp)
        }.toString()
        return ZanoNative.syncCall("restore_from_derivations", 0, params)
    }

    private fun walletDir(): String {
        val base = context.filesDir.absolutePath
        return "$base/ZanoKit/$walletId/network_${networkType.value}/zano_core"
    }

    fun walletDirPath(): String = walletDir()
}

class RestoreHeightDontMatchException : ZanoException("restore_height_dont_match")

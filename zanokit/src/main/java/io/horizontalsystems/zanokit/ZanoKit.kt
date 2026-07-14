package io.horizontalsystems.zanokit

import android.content.Context
import io.horizontalsystems.zanokit.storage.ZanoDatabase
import io.horizontalsystems.zanokit.storage.ZanoStorage
import io.horizontalsystems.zanokit.util.RestoreHeight
import io.horizontalsystems.zanokit.util.deriveZanoSecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class ZanoKit private constructor(
    private val core: ZanoCore,
    private val storage: ZanoStorage,
    private val kitId: String,
) {
    companion object {
        const val ZANO_ASSET_ID = "d6329b5b1f7c0805b5c345f4957554002a2f557845f64d7645dae0e051a6498a"
        const val CONFIRMATIONS_THRESHOLD = 10

        // Mainnet asset ids of https://api.zano.org/assets_whitelist.json, pinned into
        // the wallet's local whitelist on start so these assets stay visible even when
        // the runtime global-whitelist fetch fails (endpoint down, DNS blocked, offline).
        val GLOBAL_WHITELIST_ASSET_IDS = listOf(
            "93da681503353509367e241cda3234299dedbbad9ec851de31e900490807bf0c", // ETHX Wrapped Ethereum
            "040a180aca4194a158c17945dd115db42086f6f074c1f77838621a4927fffa91", // BTCX Wrapped Bitcoin
            "86143388bd056a8f0bab669f78f14873fac8e2dd8d57898cdb725a2d5e2e4f8f", // FUSD Freedom Dollar
            "3de9ad7243afa49e0ade6839e97a9f10a527c4958ece2fc9cb1b87a44032167d", // BCHX Wrapped Bitcoin Cash
            "bfa6609a94e39f418d9adb000f89edc7bd180fd120f1cd272201220e3070fb4f", // TONX Wrapped TON
            "65b3bc549c8bc2c773781d5436f25f7af84644e61baaabd675d9867b007d17b4", // SOLX Wrapped Solana
            "6ca3fa07f1b6a75b6e195d2918c32228765968b54ea691c75958affa1c4073fb", // BNBX Wrapped BNB
            "24819c4b65786c3ac424e05d9ef4ab212de6222cc73bc5c4b012df5a3107eea4", // DAIX Wrapped Dai
        )

        fun getInstance(
            context: Context,
            wallet: ZanoWallet,
            walletId: String,
            daemonAddress: String,
            networkType: NetworkType = NetworkType.MainNet,
            pinnedAssetIds: List<String>? = null,
        ): ZanoKit {
            val db = ZanoDatabase.build(context, "Zano-${networkType.name}-${walletId}")
            val storage = ZanoStorage(db)

            // The default pinned ids are mainnet assets; they don't exist on testnet
            val pinned = pinnedAssetIds
                ?: if (networkType == NetworkType.MainNet) GLOBAL_WHITELIST_ASSET_IDS else emptyList()

            val core = ZanoCore(context, wallet, walletId, daemonAddress, networkType, storage, pinned)
            return ZanoKit(core, storage, UUID.randomUUID().toString())
        }

        fun isValidAddress(address: String): Boolean = ZanoWalletApi.isValidAddress(address)

        fun restoreHeightForDate(date: Date): Long = RestoreHeight.getHeight(date)

        fun dateForRestoreHeight(height: Long): Date = RestoreHeight.getDate(height)

        // Derives the wallet address offline without opening the wallet.
        fun address(wallet: ZanoWallet): String? = when (wallet) {
            is ZanoWallet.Legacy ->
                ZanoWalletApi.generateAddress(wallet.seed, wallet.seedPassword)
                    ?.takeIf { it.isNotEmpty() }

            is ZanoWallet.Bip39 -> {
                val hex = deriveZanoSecretKey(wallet.mnemonic, wallet.passphrase)
                ZanoWalletApi.generateAddressFromDerivation(hex, false)
                    ?.takeIf { it.isNotEmpty() }
            }
        }
    }

    private val lifecycleDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val lifecycleScope = CoroutineScope(lifecycleDispatcher + SupervisorJob())
    private var started = false

    // Reactive state
    val syncStateFlow: StateFlow<SyncState> get() = core.syncStateFlow
    val assetsFlow: StateFlow<List<AssetInfo>> get() = core.assetsFlow
    val balancesFlow: StateFlow<List<BalanceInfo>> get() = core.balancesFlow
    val transactionsFlow: StateFlow<List<TransactionInfo>> get() = core.transactionsFlow

    val receiveAddress: String get() = core.receiveAddress

    val lastBlockHeight: Long?
        get() = core.lastBlockHeight ?: storage.getBlockHeights()?.walletHeight?.takeIf { it > 0 }

    val daemonBlockHeight: Long?
        get() = core.lastDaemonHeight ?: storage.getBlockHeights()?.daemonHeight?.takeIf { it > 0 }

    val lastBlockUpdatedFlow: Flow<Unit> get() = core.lastBlockUpdatedFlow ?: emptyFlow()

    val nativeBalance: BalanceInfo
        get() = storage.getBalance(ZANO_ASSET_ID) ?: BalanceInfo(ZANO_ASSET_ID, 0, 0, 0, 0)

    fun balance(assetId: String): BalanceInfo? = storage.getBalance(assetId)

    fun transactions(
        assetId: String? = null,
        descending: Boolean = true,
        type: TransactionFilterType? = null,
        limit: Int? = null,
    ): List<TransactionInfo> = storage.getTransactions(assetId, descending, type, limit)

    // Lifecycle

    fun start() { lifecycleScope.launch { _start() } }
    fun stop()  { lifecycleScope.launch { _stop() } }

    private suspend fun _start() {
        if (started) return
        started = true

        core.setConnectingState(waiting = true)

        var kitState = KitManager.checkAndGetInitialState(kitId)
        while (kitState == KitManager.KitState.Waiting) {
            delay(1_000)
            if (!started) return  // stop() was called while waiting
            kitState = KitManager.checkAndGetState(kitId)
        }
        if (kitState != KitManager.KitState.Running) return

        // Global lock (shared across ALL kits) taken only here, AFTER the
        // KitManager wait loop, so a concurrent stop() can still flip `started`
        // (polled by the loop above) to break us out of waiting — no deadlock.
        KitManager.lifecycleMutex.withLock {
            if (!started) return  // stop() superseded us before we opened anything
            core.setConnectingState(waiting = false)
            startCore()
        }
    }

    private suspend fun _stop() {
        if (!started) return
        started = false
        // Same lock _start() uses: an in-flight startCore() finishes first, so we
        // never tear down a wallet mid-open. stopCore() always runs: every started
        // wallet is stopped, no skipping. The lifecycle scope stays alive so the
        // kit can be started again.
        KitManager.lifecycleMutex.withLock {
            stopCore()
            KitManager.removeRunning(kitId)
        }
    }

    // Never throws: every failure ends up published via syncStateFlow as
    // NotSynced.StartError.
    private fun startCore(): Boolean {
        try {
            try {
                core.start()
            } catch (_: RestoreHeightDontMatchException) {
                recreateWalletAndStart()
            } catch (e: ZanoException) {
                if (e.message in listOf("INVALID_FILE", "FAILED_TO_LOAD_FILE")) {
                    recreateWalletAndStart()
                } else {
                    throw e
                }
            }
            return true
        } catch (e: Exception) {
            // core.start() publishes StartError itself before rethrowing; setting it
            // here as well covers exceptions thrown by the recreate path.
            core.setStartError(e.message ?: e.javaClass.simpleName)
            return false
        }
    }

    private fun recreateWalletAndStart() {
        File(core.walletDirPath()).deleteRecursively()
        storage.clearAll()
        core.start()
    }

    private fun stopCore() {
        runCatching { core.stop() }
    }

    fun store() { lifecycleScope.launch { runCatching { core.store() } } }

    fun refresh() = core.refresh()

    // Sending

    suspend fun send(
        toAddress: String,
        assetId: String = ZANO_ASSET_ID,
        amount: SendAmount,
        priority: SendPriority = SendPriority.default,
        memo: String? = null,
    ): String {
        val fee = estimateFee(priority)
        val amountValue: Long = when (amount) {
            is SendAmount.Value -> amount.amount
            is SendAmount.All -> {
                val bal = core.getBalance(assetId)
                    ?: throw ZanoException("No balance for asset $assetId")
                if (assetId == ZANO_ASSET_ID) {
                    if (bal.unlocked <= fee) throw ZanoException("Insufficient funds")
                    bal.unlocked - fee
                } else {
                    if (bal.unlocked <= 0) throw ZanoException("Insufficient funds")
                    bal.unlocked
                }
            }
        }
        return core.send(toAddress, assetId, amountValue, fee, memo)
    }

    fun estimateFee(priority: SendPriority = SendPriority.default): Long =
        ZanoWalletApi.getCurrentTxFee(priority.value)

    fun statusInfo(): Map<String, Any> {
        val info = LinkedHashMap<String, Any>()
        info["Sync State"] = syncStateFlow.value.toString()
        info["Last Block Height"] = lastBlockHeight ?: 0L
        info["Daemon Height"] = daemonBlockHeight ?: 0L
        info["Restore Height"] = core.restoreHeight
        info["Is Daemon Connected"] = core.isDaemonConnected
        info["Is In Long Refresh"] = core.isInLongRefresh
        info["Network"] = core.networkType.name
        info["Node"] = core.daemonAddress
        return info
    }
}

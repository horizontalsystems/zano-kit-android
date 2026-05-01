package io.horizontalsystems.zanokit

import android.content.Context
import android.util.Log
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

        fun getInstance(
            context: Context,
            wallet: ZanoWallet,
            walletId: String,
            daemonAddress: String,
            networkType: NetworkType = NetworkType.MainNet,
        ): ZanoKit {
            val base = context.dataDir.absolutePath
            val db = ZanoDatabase.build(context, "$base/Zano-${networkType.name}-${walletId}")
            val storage = ZanoStorage(db)

            val core = ZanoCore(context, wallet, walletId, daemonAddress, networkType, storage)
            return ZanoKit(core, storage, UUID.randomUUID().toString())
        }

        fun isValidAddress(address: String): Boolean = ZanoWalletApi.isValidAddress(address)

        fun restoreHeightForDate(date: Date): Long = RestoreHeight.getHeight(date)

        fun dateForRestoreHeight(height: Long): Date = RestoreHeight.getDate(height)

        // Derives the wallet address offline without opening the wallet.
        fun address(wallet: ZanoWallet): String? = when (wallet) {
            is ZanoWallet.Legacy ->
                ZanoNative.generateAddress(wallet.seed, wallet.seedPassword)
                    ?.takeIf { it.isNotEmpty() }

            is ZanoWallet.Bip39 -> {
                val hex = deriveZanoSecretKey(wallet.mnemonic, wallet.passphrase)
                ZanoNative.generateAddressFromDerivation(hex, false)
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
    fun stop()  { lifecycleScope.launch { _stop()  } }

    private suspend fun _start() {
        if (started) return
        started = true

        var kitState = KitManager.checkAndGetInitialState(kitId)
        while (kitState == KitManager.KitState.Waiting) {
            core.setConnectingState(waiting = true)
            delay(1_000)
            kitState = KitManager.checkAndGetState(kitId)
        }
        if (kitState != KitManager.KitState.Running) return  // Obsolete — bail out

        try {
            core.start()
        } catch (e: RestoreHeightDontMatchException) {
            Log.e("eee", "restart ZanoCore RestoreHeightDontMatchException: ${e.message}")
            File(core.walletDirPath()).deleteRecursively()
            storage.clearAll()
            core.start()
        } catch (e: ZanoException) {
            when (e.message) {
                "ALREADY_EXISTS" -> {
                    Log.e("eee", "restart ZanoCore after ALREADY_EXISTS — native cleanup still in progress, retrying in 3s")
                    delay(3_000)
                    core.start()
                }
                "INVALID_FILE", "FAILED_TO_LOAD_FILE" -> {
                    Log.e("eee", "restart ZanoCore: ${e.message}")
                    File(core.walletDirPath()).deleteRecursively()
                    storage.clearAll()
                    core.start()
                }
                else -> throw e
            }
        }
    }

    private suspend fun _stop() {
        if (!started) return
        started = false
        core.stop()
        KitManager.removeRunning(kitId)
    }

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
}

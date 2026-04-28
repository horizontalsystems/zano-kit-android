package io.horizontalsystems.zanokit

import android.content.Context
import io.horizontalsystems.zanokit.storage.ZanoDatabase
import io.horizontalsystems.zanokit.storage.ZanoStorage
import io.horizontalsystems.zanokit.util.RestoreHeight
import io.horizontalsystems.zanokit.util.deriveZanoSecretKey
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.Date
import java.util.UUID

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
            networkType: NetworkType = NetworkType.mainnet,
        ): ZanoKit {
            val base = context.filesDir.absolutePath
            val baseDir = "$base/ZanoKit/$walletId/network_${networkType.value}"
            val db = ZanoDatabase.build(context, "$baseDir/storage")
            val storage = ZanoStorage(db)

            // Restore in-memory sent transfer cache from DB so first fetchTransactions()
            // can enrich outgoing tx display even after a cold restart
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

    // Reactive state
    val syncStateFlow: StateFlow<SyncState> get() = core.syncStateFlow
    val assetsFlow: StateFlow<List<AssetInfo>> get() = core.assetsFlow
    val balancesFlow: StateFlow<List<BalanceInfo>> get() = core.balancesFlow
    val transactionsFlow: StateFlow<List<TransactionInfo>> get() = core.transactionsFlow

    val receiveAddress: String get() = core.receiveAddress

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

    suspend fun start() {
        KitManager.waitAndRun(kitId) {
            try {
                core.start()
            } catch (e: RestoreHeightDontMatchException) {
                File(core.walletDirPath()).deleteRecursively()
                storage.clearAll()
                core.start()
            }
        }
    }

    suspend fun stop() = core.stop()

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

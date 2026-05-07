package io.horizontalsystems.zanokit.storage

import io.horizontalsystems.zanokit.*
import io.horizontalsystems.zanokit.storage.entities.*

class ZanoStorage(private val db: ZanoDatabase) {

    // Assets

    fun updateAssets(assets: List<AssetInfo>) {
        db.assetDao().deleteAll()
        db.assetDao().insertAll(assets.map { AssetEntity.from(it) })
    }

    fun getAsset(assetId: String): AssetInfo? =
        db.assetDao().getById(assetId)?.toAssetInfo()

    fun getAllAssets(): List<AssetInfo> =
        db.assetDao().getAll().map { it.toAssetInfo() }

    // Balances

    fun updateBalances(balances: List<BalanceInfo>) {
        db.balanceDao().deleteAll()
        db.balanceDao().insertAll(balances.map { BalanceEntity.from(it) })
    }

    fun getBalance(assetId: String): BalanceInfo? =
        db.balanceDao().getById(assetId)?.toBalanceInfo()

    fun getAllBalances(): List<BalanceInfo> =
        db.balanceDao().getAll().map { it.toBalanceInfo() }

    // Transactions

    fun updateTransactions(transactions: List<TransactionInfo>) {
        db.transactionDao().deleteAll()
        db.transactionDao().insertAll(transactions.map { TransactionEntity.from(it) })
    }

    fun getTransactions(
        assetId: String? = null,
        descending: Boolean = true,
        type: TransactionFilterType? = null,
        limit: Int? = null,
    ): List<TransactionInfo> {
        val typeFilter = type?.types?.map { it.ordinal }
        return db.transactionDao().getFiltered(
            assetId = assetId,
            typeFilter = typeFilter,
            descending = descending,
            limitVal = limit ?: -1,
        ).map { it.toTransactionInfo() }
    }

    // Sent transfers (cached before confirmation, used to enrich tx display)

    fun saveSentTransfer(txHash: String, assetId: String, amount: Long, address: String) {
        db.sentTransferDao().insert(SentTransferEntity(txHash, assetId, amount, address))
    }

    fun getSentTransfer(txHash: String): SentTransferEntity? =
        db.sentTransferDao().getByTxHash(txHash)

    fun getAllSentTransfers(): List<SentTransferEntity> =
        db.sentTransferDao().getAll()

    // Wallet info

    fun saveCreationTimestamp(timestamp: Long) {
        db.walletInfoDao().insert(WalletInfoEntity(creationTimestamp = timestamp))
    }

    fun getCreationTimestamp(): Long? =
        db.walletInfoDao().get()?.creationTimestamp

    // Block heights

    fun saveBlockHeights(walletHeight: Long, daemonHeight: Long) {
        db.blockHeightDao().insert(BlockHeightEntity(walletHeight = walletHeight, daemonHeight = daemonHeight))
    }

    fun getBlockHeights(): BlockHeightEntity? = db.blockHeightDao().get()

    // Full reset

    fun clearAll() {
        db.assetDao().deleteAll()
        db.balanceDao().deleteAll()
        db.transactionDao().deleteAll()
        db.walletInfoDao()  // wallet_info intentionally preserved across resets
    }
}

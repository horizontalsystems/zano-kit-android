package io.horizontalsystems.zanokit

const val ZANO_ASSET_ID = "d6329b5b1f7c0805b5c345f4957554002a2f557845f64d7645dae0e051a6498a"

enum class TransactionType { incoming, outgoing, sentToSelf }

enum class TransactionFilterType {
    incoming, outgoing;

    val types: List<TransactionType>
        get() = when (this) {
            incoming -> listOf(TransactionType.incoming, TransactionType.sentToSelf)
            outgoing -> listOf(TransactionType.outgoing, TransactionType.sentToSelf)
        }
}

enum class SendPriority(val value: Int) {
    default(0), low(1), medium(2), high(3)
}

enum class NetworkType(val value: Int) {
    mainnet(0), testnet(1)
}

sealed class SendAmount {
    data class Value(val amount: Long) : SendAmount()
    object All : SendAmount()
}

sealed class SyncState {
    object Synced : SyncState()
    data class Connecting(val waiting: Boolean) : SyncState()
    data class Syncing(val progress: Int, val remainingBlocks: Long) : SyncState()
    sealed class NotSynced : SyncState() {
        object NotStarted : NotSynced()
        object NoNetwork : NotSynced()
        data class StartError(val message: String?) : NotSynced()
        data class StatusError(val message: String?) : NotSynced()
    }
}

data class AssetInfo(
    val assetId: String,
    val ticker: String,
    val fullName: String,
    val decimalPoint: Int,
    val totalMaxSupply: Long,
    val currentSupply: Long,
    val metaInfo: String?,
) {
    val isNative: Boolean get() = assetId == ZANO_ASSET_ID
}

data class BalanceInfo(
    val assetId: String,
    val total: Long,
    val unlocked: Long,
    val awaitingIn: Long,
    val awaitingOut: Long,
) {
    val isNative: Boolean get() = assetId == ZANO_ASSET_ID
}

data class TransactionInfo(
    val uid: String,
    val hash: String,
    val assetId: String,
    val type: TransactionType,
    val blockHeight: Long,
    val amount: Long,
    val fee: Long,
    val isPending: Boolean,
    val isFailed: Boolean,
    val timestamp: Long,
    val memo: String?,
    val recipientAddress: String?,
) {
    val isNative: Boolean get() = assetId == ZANO_ASSET_ID
}

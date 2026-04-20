package io.horizontalsystems.zanokit.storage.entities

import androidx.room.Entity
import io.horizontalsystems.zanokit.TransactionInfo
import io.horizontalsystems.zanokit.TransactionType

// Composite PK: one tx can transfer multiple assets, generating one record per asset
@Entity(tableName = "transactions", primaryKeys = ["hash", "assetId"])
data class TransactionEntity(
    val uid: String,
    val hash: String,
    val assetId: String,
    val type: Int,          // TransactionType.ordinal
    val blockHeight: Long,
    val amount: Long,
    val fee: Long,
    val isPending: Boolean,
    val isFailed: Boolean,
    val timestamp: Long,
    val note: String?,
    val recipientAddress: String?,
) {
    fun toTransactionInfo() = TransactionInfo(
        uid = uid,
        hash = hash,
        assetId = assetId,
        type = TransactionType.entries[type],
        blockHeight = blockHeight,
        amount = amount,
        fee = fee,
        isPending = isPending,
        isFailed = isFailed,
        timestamp = timestamp,
        memo = note,
        recipientAddress = recipientAddress,
    )

    companion object {
        fun from(info: TransactionInfo) = TransactionEntity(
            uid = info.uid,
            hash = info.hash,
            assetId = info.assetId,
            type = info.type.ordinal,
            blockHeight = info.blockHeight,
            amount = info.amount,
            fee = info.fee,
            isPending = info.isPending,
            isFailed = info.isFailed,
            timestamp = info.timestamp,
            note = info.memo,
            recipientAddress = info.recipientAddress,
        )
    }
}

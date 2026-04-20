package io.horizontalsystems.zanokit.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sent_transfers")
data class SentTransferEntity(
    @PrimaryKey val txHash: String,
    val assetId: String,
    val amount: Long,
    val address: String,
)

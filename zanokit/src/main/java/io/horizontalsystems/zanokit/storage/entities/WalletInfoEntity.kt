package io.horizontalsystems.zanokit.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "wallet_info")
data class WalletInfoEntity(
    @PrimaryKey val id: String = "singleton",
    val creationTimestamp: Long,
)

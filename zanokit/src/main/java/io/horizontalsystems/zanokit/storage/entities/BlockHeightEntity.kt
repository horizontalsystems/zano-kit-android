package io.horizontalsystems.zanokit.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "BlockHeight")
data class BlockHeightEntity(
    @PrimaryKey val id: String = "single-row-id",
    val walletHeight: Long,
    val daemonHeight: Long,
)

package io.horizontalsystems.zanokit.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "block_height")
data class BlockHeightEntity(
    @PrimaryKey val id: String = "single-row-id",
    val walletHeight: Long,
    val daemonHeight: Long,
)

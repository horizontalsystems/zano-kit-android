package io.horizontalsystems.zanokit.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.horizontalsystems.zanokit.BalanceInfo

@Entity(tableName = "balances")
data class BalanceEntity(
    @PrimaryKey val assetId: String,
    val total: Long,
    val unlocked: Long,
    val awaitingIn: Long,
    val awaitingOut: Long,
) {
    fun toBalanceInfo() = BalanceInfo(assetId, total, unlocked, awaitingIn, awaitingOut)

    companion object {
        fun from(info: BalanceInfo) = BalanceEntity(
            assetId = info.assetId,
            total = info.total,
            unlocked = info.unlocked,
            awaitingIn = info.awaitingIn,
            awaitingOut = info.awaitingOut,
        )
    }
}

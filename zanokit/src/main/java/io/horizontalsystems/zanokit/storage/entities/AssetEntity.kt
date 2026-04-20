package io.horizontalsystems.zanokit.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.horizontalsystems.zanokit.AssetInfo

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey val assetId: String,
    val ticker: String,
    val fullName: String,
    val decimalPoint: Int,
    val totalMaxSupply: Long,
    val currentSupply: Long,
    val metaInfo: String?,
) {
    fun toAssetInfo() = AssetInfo(assetId, ticker, fullName, decimalPoint, totalMaxSupply, currentSupply, metaInfo)

    companion object {
        fun from(info: AssetInfo) = AssetEntity(
            assetId = info.assetId,
            ticker = info.ticker,
            fullName = info.fullName,
            decimalPoint = info.decimalPoint,
            totalMaxSupply = info.totalMaxSupply,
            currentSupply = info.currentSupply,
            metaInfo = info.metaInfo,
        )
    }
}

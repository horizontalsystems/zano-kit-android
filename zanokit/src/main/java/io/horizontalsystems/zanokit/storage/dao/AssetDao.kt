package io.horizontalsystems.zanokit.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.zanokit.storage.entities.AssetEntity

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets")
    fun getAll(): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE assetId = :assetId")
    fun getById(assetId: String): AssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(assets: List<AssetEntity>)

    @Query("DELETE FROM assets")
    fun deleteAll()
}

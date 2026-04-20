package io.horizontalsystems.zanokit.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.zanokit.storage.entities.BalanceEntity

@Dao
interface BalanceDao {
    @Query("SELECT * FROM balances")
    fun getAll(): List<BalanceEntity>

    @Query("SELECT * FROM balances WHERE assetId = :assetId")
    fun getById(assetId: String): BalanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(balances: List<BalanceEntity>)

    @Query("DELETE FROM balances")
    fun deleteAll()
}

package io.horizontalsystems.zanokit.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.zanokit.storage.entities.WalletInfoEntity

@Dao
interface WalletInfoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(info: WalletInfoEntity)

    @Query("SELECT * FROM wallet_info WHERE id = 'singleton'")
    fun get(): WalletInfoEntity?
}

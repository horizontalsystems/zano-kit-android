package io.horizontalsystems.zanokit.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.zanokit.storage.entities.SentTransferEntity

@Dao
interface SentTransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(sentTransfer: SentTransferEntity)

    @Query("SELECT * FROM sent_transfers WHERE txHash = :txHash")
    fun getByTxHash(txHash: String): SentTransferEntity?

    @Query("SELECT * FROM sent_transfers")
    fun getAll(): List<SentTransferEntity>
}

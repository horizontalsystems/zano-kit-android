package io.horizontalsystems.zanokit.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.zanokit.storage.entities.TransactionEntity

@Dao
interface TransactionDao {
    @Query("""
        SELECT * FROM transactions
        WHERE (:assetId IS NULL OR assetId = :assetId)
          AND (:typeFilter IS NULL OR type IN (:typeFilter))
        ORDER BY
          CASE WHEN :descending = 1 THEN timestamp END DESC,
          CASE WHEN :descending = 0 THEN timestamp END ASC
        LIMIT CASE WHEN :limitVal < 0 THEN -1 ELSE :limitVal END
    """)
    fun getFiltered(
        assetId: String?,
        typeFilter: List<Int>?,
        descending: Boolean,
        limitVal: Int,
    ): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE hash = :hash")
    fun getByHash(hash: String): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(transactions: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    fun deleteAll()
}

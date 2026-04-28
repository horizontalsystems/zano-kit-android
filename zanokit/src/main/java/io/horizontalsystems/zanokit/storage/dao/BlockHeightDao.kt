package io.horizontalsystems.zanokit.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.zanokit.storage.entities.BlockHeightEntity

@Dao
interface BlockHeightDao {
    @Query("SELECT * FROM BlockHeight WHERE id = 'single-row-id'")
    fun get(): BlockHeightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(entity: BlockHeightEntity)
}

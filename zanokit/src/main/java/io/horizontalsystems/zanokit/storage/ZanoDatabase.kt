package io.horizontalsystems.zanokit.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.zanokit.storage.dao.*
import io.horizontalsystems.zanokit.storage.entities.*

@Database(
    entities = [
        AssetEntity::class,
        BalanceEntity::class,
        TransactionEntity::class,
        SentTransferEntity::class,
        WalletInfoEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ZanoDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun balanceDao(): BalanceDao
    abstract fun transactionDao(): TransactionDao
    abstract fun sentTransferDao(): SentTransferDao
    abstract fun walletInfoDao(): WalletInfoDao

    companion object {
        fun build(context: Context, dbPath: String): ZanoDatabase {
            return Room.databaseBuilder(context, ZanoDatabase::class.java, dbPath)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

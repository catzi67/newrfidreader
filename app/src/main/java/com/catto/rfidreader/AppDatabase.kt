package com.catto.rfidreader

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [ScannedCard::class, Quest::class], version = 6, exportSchema = false)
@TypeConverters(CardStatsConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scannedCardDao(): ScannedCardDao
    abstract fun questDao(): QuestDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nfc_app_database"
                )
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            INSTANCE?.let { database ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    if (database.questDao().getQuestCount() == 0) {
                                        database.questDao().insertAll(QuestManager.allQuests)
                                    }
                                }
                            }
                        }
                    })
                    // FIX: The correct parameter name is 'dropAllTables', not 'recreateAllTables'.
                    // This resolves the "None of the following candidates is applicable" error.
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

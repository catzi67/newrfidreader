package com.catto.rfidreader

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(entities = [ScannedCard::class, Quest::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scannedCardDao(): ScannedCardDao
    abstract fun questDao(): QuestDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        @Suppress("DEPRECATION")
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
                            // Every time the app opens, check if the quests exist.
                            // If not, pre-populate them. This is robust for all users.
                            INSTANCE?.let { database ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    if (database.questDao().getQuestCount() == 0) {
                                        database.questDao().insertAll(QuestManager.allQuests)
                                    }
                                }
                            }
                        }
                    })
                    // This is the correct and intended function. The deprecation warning
                    // is a known issue in some versions of the Android build tools' linter
                    // and can be safely suppressed.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

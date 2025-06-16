package com.catto.rfidreader

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ScannedCard::class], version = 2, exportSchema = false) // <-- Change version to 2
abstract class AppDatabase : RoomDatabase() {

    abstract fun scannedCardDao(): ScannedCardDao

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
                    .fallbackToDestructiveMigration(true) // <-- ADD THIS LINE
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
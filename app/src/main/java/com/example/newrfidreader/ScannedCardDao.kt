package com.example.newrfidreader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedCardDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(card: ScannedCard)

    @Query("SELECT * FROM scanned_card_history ORDER BY scanTimestamp DESC")
    fun getAllCards(): Flow<List<ScannedCard>>

    @Query("DELETE FROM scanned_card_history") // <-- ADD THIS
    suspend fun clearHistory()
}
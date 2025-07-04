package com.catto.rfidreader

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedCardDao {
    // The conflict strategy is changed to REPLACE to allow the "Undo" feature to re-insert a deleted card.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: ScannedCard)

    @Update
    suspend fun update(card: ScannedCard)

    // A new function to delete a card from the database.
    @Delete
    suspend fun delete(card: ScannedCard)

    @Query("SELECT * FROM scanned_card_history ORDER BY scanTimestamp DESC")
    fun getAllCards(): Flow<List<ScannedCard>>

    @Query("SELECT * FROM scanned_card_history")
    suspend fun getAllCardsList(): List<ScannedCard>

    @Query("SELECT * FROM scanned_card_history WHERE id = :cardId")
    suspend fun getCardById(cardId: Int): ScannedCard?

    @Query("DELETE FROM scanned_card_history")
    suspend fun clearHistory()
}

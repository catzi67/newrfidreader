package com.catto.rfidreader

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedCardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(card: ScannedCard)

    @Update
    suspend fun update(card: ScannedCard)

    @Update
    suspend fun updateAll(cards: List<ScannedCard>)

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

    @Query("SELECT * FROM scanned_card_history ORDER BY eloRating DESC")
    fun getLeaderboard(): Flow<List<ScannedCard>>
}

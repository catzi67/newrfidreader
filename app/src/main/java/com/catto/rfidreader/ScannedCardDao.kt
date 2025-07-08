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

    @Query("SELECT * FROM scanned_card_history WHERE serialNumberHex = :serialNumberHex LIMIT 1")
    suspend fun getCardBySerialNumber(serialNumberHex: String): ScannedCard?

    @Query("DELETE FROM scanned_card_history")
    suspend fun clearHistory()

    @Query("SELECT * FROM scanned_card_history ORDER BY eloRating DESC")
    fun getLeaderboard(): Flow<List<ScannedCard>>

    @Transaction
    suspend fun upsert(card: ScannedCard) {
        val existingCard = getCardBySerialNumber(card.serialNumberHex)
        if (existingCard == null) {
            // Card is new, insert it.
            insert(card)
        } else {
            // Card exists, update it but preserve its name, notes, and battle record.
            val updatedCard = existingCard.copy(
                scanTimestamp = card.scanTimestamp,
                tagInfo = card.tagInfo,
                score = card.score,
                battleStats = card.battleStats
            )
            update(updatedCard)
        }
    }
}

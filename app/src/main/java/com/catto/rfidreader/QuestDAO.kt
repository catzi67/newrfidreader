package com.catto.rfidreader

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(quests: List<Quest>)

    @Update
    suspend fun update(quest: Quest)

    @Query("SELECT * FROM quests")
    fun getAllQuests(): Flow<List<Quest>>

    @Query("SELECT * FROM quests WHERE id = :id")
    suspend fun getQuestById(id: String): Quest?

    @Query("SELECT COUNT(*) FROM quests")
    suspend fun getQuestCount(): Int
}

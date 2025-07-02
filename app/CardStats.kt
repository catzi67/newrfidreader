package com.catto.rfidreader

import androidx.room.TypeConverter
import com.google.gson.Gson

// This data class holds the generated stats for each card.
data class CardStats(
    val hp: Int,        // Health Points
    val attack: Int,    // Attack Power
    val defense: Int,   // Defense Power
    val speed: Int,     // Determines who attacks first
    val elementType: ElementType
)

// Defines the different elemental types a card can have.
enum class ElementType {
    TECH, ENERGY, ANCIENT, VOID
}

// This class tells Room how to store the custom CardStats object in the database.
// It converts the object to a JSON string for storage and back again when retrieved.
class CardStatsConverter {
    @TypeConverter
    fun fromCardStats(stats: CardStats?): String? {
        return Gson().toJson(stats)
    }

    @TypeConverter
    fun toCardStats(statsString: String?): CardStats? {
        if (statsString == null) {
            return null
        }
        return Gson().fromJson(statsString, CardStats::class.java)
    }
}

package com.catto.rfidreader

import androidx.room.TypeConverter
import com.google.gson.Gson

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

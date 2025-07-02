package com.catto.rfidreader

import androidx.room.Entity
import androidx.room.PrimaryKey

// The @TypeConverters annotation has been removed from this file.
// It correctly resides only on the AppDatabase class.
@Entity(tableName = "scanned_card_history")
data class ScannedCard(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val serialNumberHex: String,
    val tagInfo: String,
    val scanTimestamp: Long,
    val decValue: String,
    val binValue: String,
    val revHexValue: String,
    val revDecValue: String,
    val revBinValue: String,
    val score: Int,
    var name: String? = null,
    var notes: String? = null,
    val battleStats: CardStats? = null
)
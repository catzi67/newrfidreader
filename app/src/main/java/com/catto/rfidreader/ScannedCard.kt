package com.catto.rfidreader

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scanned_card_history")
data class ScannedCard(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val serialNumberHex: String,
    val tagInfo: String,
    val scanTimestamp: Long,
    // --- ADD THESE NEW FIELDS ---
    val decValue: String,
    val binValue: String,
    val revHexValue: String,
    val revDecValue: String,
    val revBinValue: String,
    val score: Int
)
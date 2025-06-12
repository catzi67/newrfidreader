package com.example.newrfidreader

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scanned_card_history")
data class ScannedCard(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val serialNumberHex: String,
    val tagInfo: String,
    val scanTimestamp: Long
)
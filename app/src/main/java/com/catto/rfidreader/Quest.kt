package com.catto.rfidreader

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quests")
data class Quest(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    var isCompleted: Boolean = false
)

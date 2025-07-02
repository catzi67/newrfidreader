package com.catto.rfidreader

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

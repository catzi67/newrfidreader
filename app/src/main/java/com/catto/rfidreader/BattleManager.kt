package com.catto.rfidreader

import kotlin.math.max
import kotlin.random.Random

// A singleton object to manage all battle-related logic.
object BattleManager {

    // Generates a unique set of battle stats based on a card's ID and tag info.
    fun generateStats(cardId: ByteArray, tagInfo: String): CardStats {
        if (cardId.isEmpty()) {
            // Return default stats for cards with no ID.
            return CardStats(10, 5, 5, 5, ElementType.VOID)
        }

        // Use the card's ID to create a consistent seed for the random number generator.
        val seed = cardId.contentHashCode().toLong()
        val random = Random(seed)

        // HP: Derived from the sum of the first two bytes of the ID.
        val hp = 80 + (cardId.getOrElse(0) { 0 }.toInt() and 0xFF + cardId.getOrElse(1) { 0 }.toInt() and 0xFF) % 71 // Range: 80-150

        // Attack: Increased base attack and range for more impactful hits.
        val attack = 15 + (cardId.getOrElse(2) { 0 }.toInt() and 0xFF) % 31 // Range: 15-45

        // Defense: Lowered base defense to make cards more vulnerable.
        val defense = 5 + (cardId.getOrElse(3) { 0 }.toInt() and 0xFF) % 21 // Range: 5-25

        // Speed: A random value derived from the ID's seed.
        val speed = 5 + random.nextInt(21) // Range: 5-25

        // Element Type: Determined by the card's NFC technology type.
        val elementType = when {
            tagInfo.contains("MifareClassic", ignoreCase = true) -> ElementType.TECH
            tagInfo.contains("NfcA", ignoreCase = true) -> ElementType.ENERGY
            tagInfo.contains("NfcB", ignoreCase = true) -> ElementType.ANCIENT
            else -> ElementType.VOID
        }

        return CardStats(hp, attack, defense, speed, elementType)
    }

    // Calculates the damage one card deals to another in a single attack.
    fun calculateDamage(attacker: CardStats, defender: CardStats): Int {
        // Add a random variance to the attack to make battles less predictable.
        val attackRoll = attacker.attack * (1.0f + Random.nextFloat() * 0.25f) // 0% to 25% bonus damage

        // The core damage calculation.
        val damage = attackRoll - defender.defense

        // Ensure damage is always at least 1, so every attack does something.
        return max(1, damage.toInt())
    }
}

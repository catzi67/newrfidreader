package com.catto.rfidreader

import kotlin.math.max
import kotlin.random.Random

// A data class to hold the detailed outcome of a single attack turn.
data class AttackResult(
    val damage: Int = 0,
    val isMiss: Boolean = false,
    val isCritical: Boolean = false,
    val isBlocked: Boolean = false,
    val didCounter: Boolean = false,
    val counterDamage: Int = 0
)

// A singleton object to manage all battle-related logic.
object BattleManager {

    // Generates a unique set of battle stats based on a card's ID and tag info.
    fun generateStats(cardId: ByteArray, tagInfo: String): CardStats {
        if (cardId.isEmpty()) {
            // Return default stats for cards with no ID.
            return CardStats(10, 5, 5, 5, 5, ElementType.VOID)
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

        // Luck: A new stat influencing critical hits, blocks, and counters.
        val luck = 5 + random.nextInt(16) // Range: 5-20

        // Element Type: Determined by the card's NFC technology type.
        val elementType = when {
            tagInfo.contains("MifareClassic", ignoreCase = true) -> ElementType.TECH
            tagInfo.contains("NfcA", ignoreCase = true) -> ElementType.ENERGY
            tagInfo.contains("NfcB", ignoreCase = true) -> ElementType.ANCIENT
            else -> ElementType.VOID
        }

        return CardStats(hp, attack, defense, speed, luck, elementType)
    }

    // Resolves a single attack, returning a detailed AttackResult.
    fun resolveAttack(attacker: CardStats, defender: CardStats): AttackResult {
        // 1. Check for a miss. Higher speed difference increases evasion.
        val speedDifference = defender.speed - attacker.speed
        val missChance = 0.10 + (speedDifference * 0.01)
        if (Random.nextFloat() < missChance.coerceIn(0.05, 0.5)) {
            return AttackResult(isMiss = true)
        }

        // 2. Calculate base damage.
        var damage = attacker.attack.toFloat()

        // 3. Check for a Critical Hit. Luck increases the chance.
        val critChance = 0.05 + (attacker.luck * 0.01)
        val isCritical = Random.nextFloat() < critChance.coerceIn(0.0, 0.4)
        if (isCritical) {
            damage *= 1.5f // 50% more damage
        }

        // 4. Calculate damage reduction from defense.
        val damageReduction = defender.defense * 0.75f
        damage -= damageReduction

        // 5. Check for a partial block.
        val blockChance = 0.05 + (defender.defense * 0.005) + (defender.luck * 0.005)
        val isBlocked = Random.nextFloat() < blockChance.coerceIn(0.0, 0.3)
        if (isBlocked) {
            damage *= 0.5f // Damage is halved on a block
        }

        // Ensure damage is at least 0.
        var finalDamage = max(0, damage.toInt())

        // A successful, non-blocked hit always does at least 1 damage.
        if (finalDamage == 0 && !isBlocked) {
            finalDamage = 1
        }

        // 6. Check for a Counter-Attack.
        var didCounter = false
        var counterDamage = 0
        val counterChance = 0.10 + (defender.speed * 0.005) + (defender.luck * 0.01)
        if (Random.nextFloat() < counterChance.coerceIn(0.0, 0.35)) {
            didCounter = true
            // Counter-attack deals a fraction of the defender's attack power.
            counterDamage = max(1, (defender.attack * 0.4f).toInt())
        }

        return AttackResult(
            damage = finalDamage,
            isCritical = isCritical,
            isBlocked = isBlocked,
            didCounter = didCounter,
            counterDamage = counterDamage
        )
    }
}

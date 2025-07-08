package com.catto.rfidreader

import kotlin.math.abs
import kotlin.math.max
import java.util.Random // Use java.util.Random for determinism

// A data class to hold the detailed outcome of a single attack turn.
data class AttackResult(
    val damage: Int = 0,
    val isMiss: Boolean = false,
    val isCritical: Boolean = false,
    val isBlocked: Boolean = false,
    val didCounter: Boolean = false,
    val counterDamage: Int = 0,
    val isSuperEffective: Boolean = false,
    val isNotVeryEffective: Boolean = false
)

// A singleton object to manage all battle-related logic.
object BattleManager {

    /**
     * Generates a unique and deterministic set of battle stats based on a card's ID.
     * The formula is designed to produce the exact same stats for the same ID on any device.
     */
    fun generateStats(cardId: ByteArray): CardStats {
        if (cardId.isEmpty()) {
            // Return default stats for an empty ID.
            return CardStats(10, 5, 5, 5, 5, ElementType.VOID)
        }

        // --- Deterministic Stat Generation ---
        // Each stat is derived directly from the bytes of the card ID to ensure
        // the result is the same every time, on any device. We use different
        // bytes and modulo operations to create variety in the stat distribution.

        // HP: Based on the first two bytes, in the range of 80-150.
        val hp = 80 + (cardId.getOrElse(0) { 1 }.toInt() and 0xFF + cardId.getOrElse(1) { 1 }.toInt() and 0xFF) % 71

        // Attack: Based on the third byte, in the range of 15-45.
        val attack = 15 + (cardId.getOrElse(2) { 2 }.toInt() and 0xFF) % 31

        // Defense: Based on the fourth byte, in the range of 5-25.
        val defense = 5 + (cardId.getOrElse(3) { 3 }.toInt() and 0xFF) % 21

        // Speed: Based on the fifth byte, in the range of 5-25.
        val speed = 5 + (cardId.getOrElse(4) { 4 }.toInt() and 0xFF) % 21

        // Luck: Based on the sixth byte, in the range of 5-20.
        val luck = 5 + (cardId.getOrElse(5) { 5 }.toInt() and 0xFF) % 16

        // Element Type: Determined by the sum of all byte values.
        val elementValue = cardId.sumOf { it.toInt() }
        val elementType = when (abs(elementValue) % 4) {
            0 -> ElementType.TECH
            1 -> ElementType.ENERGY
            2 -> ElementType.ANCIENT
            else -> ElementType.VOID
        }

        return CardStats(hp, attack, defense, speed, luck, elementType)
    }

    /**
     * Resolves a single attack, returning a detailed AttackResult.
     * This function is now deterministic if the same Random instance is provided.
     * @param attacker The stats of the attacking card.
     * @param defender The stats of the defending card.
     * @param random A seeded Random instance to ensure outcomes are the same on both devices.
     * @return An AttackResult object detailing what happened in the turn.
     */
    fun resolveAttack(attacker: CardStats, defender: CardStats, random: Random): AttackResult {
        val speedDifference = defender.speed - attacker.speed
        val missChance = 0.10 + (speedDifference * 0.01)
        if (random.nextFloat() < missChance.coerceIn(0.05, 0.5)) {
            return AttackResult(isMiss = true)
        }

        var damage = attacker.attack.toFloat()
        var isSuperEffective = false
        var isNotVeryEffective = false

        if (attacker.elementType.isSuperEffectiveAgainst(defender.elementType)) {
            damage *= 1.5f
            isSuperEffective = true
        } else if (defender.elementType.isSuperEffectiveAgainst(attacker.elementType)) {
            damage *= 0.75f
            isNotVeryEffective = true
        }

        val critChance = 0.05 + (attacker.luck * 0.01)
        val isCritical = random.nextFloat() < critChance.coerceIn(0.0, 0.4)
        if (isCritical) {
            damage *= 1.5f
        }

        val damageReduction = defender.defense * 0.75f
        damage -= damageReduction

        val blockChance = 0.05 + (defender.defense * 0.005) + (defender.luck * 0.005)
        val isBlocked = random.nextFloat() < blockChance.coerceIn(0.0, 0.3)
        if (isBlocked) {
            damage *= 0.5f
        }

        var finalDamage = max(0, damage.toInt())
        if (finalDamage == 0 && !isBlocked) {
            finalDamage = 1
        }

        var didCounter = false
        var counterDamage = 0
        val counterChance = 0.10 + (defender.speed * 0.005) + (defender.luck * 0.01)
        if (random.nextFloat() < counterChance.coerceIn(0.0, 0.35)) {
            didCounter = true
            counterDamage = max(1, (defender.attack * 0.4f).toInt())
        }

        return AttackResult(
            damage = finalDamage,
            isCritical = isCritical,
            isBlocked = isBlocked,
            didCounter = didCounter,
            counterDamage = counterDamage,
            isSuperEffective = isSuperEffective,
            isNotVeryEffective = isNotVeryEffective
        )
    }
}

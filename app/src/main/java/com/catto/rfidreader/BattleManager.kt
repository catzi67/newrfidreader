package com.catto.rfidreader

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

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

    // Generates a unique set of battle stats based on a card's ID.
    fun generateStats(cardId: ByteArray): CardStats {
        if (cardId.isEmpty()) {
            return CardStats(10, 5, 5, 5, 5, ElementType.VOID)
        }
        val seed = cardId.contentHashCode().toLong()
        val random = Random(seed)
        val hp = 80 + (cardId.getOrElse(0) { 0 }.toInt() and 0xFF + cardId.getOrElse(1) { 0 }.toInt() and 0xFF) % 71
        val attack = 15 + (cardId.getOrElse(2) { 0 }.toInt() and 0xFF) % 31
        val defense = 5 + (cardId.getOrElse(3) { 0 }.toInt() and 0xFF) % 21
        val speed = 5 + random.nextInt(21)
        val luck = 5 + random.nextInt(16)

        val elementValue = cardId.sumOf { it.toInt() }
        val elementType = when (abs(elementValue) % 4) {
            0 -> ElementType.TECH
            1 -> ElementType.ENERGY
            2 -> ElementType.ANCIENT
            else -> ElementType.VOID
        }

        return CardStats(hp, attack, defense, speed, luck, elementType)
    }

    // Resolves a single attack, returning a detailed AttackResult.
    fun resolveAttack(attacker: CardStats, defender: CardStats): AttackResult {
        val speedDifference = defender.speed - attacker.speed
        val missChance = 0.10 + (speedDifference * 0.01)
        if (Random.nextFloat() < missChance.coerceIn(0.05, 0.5)) {
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
        val isCritical = Random.nextFloat() < critChance.coerceIn(0.0, 0.4)
        if (isCritical) {
            damage *= 1.5f
        }

        val damageReduction = defender.defense * 0.75f
        damage -= damageReduction

        val blockChance = 0.05 + (defender.defense * 0.005) + (defender.luck * 0.005)
        val isBlocked = Random.nextFloat() < blockChance.coerceIn(0.0, 0.3)
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
        if (Random.nextFloat() < counterChance.coerceIn(0.0, 0.35)) {
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

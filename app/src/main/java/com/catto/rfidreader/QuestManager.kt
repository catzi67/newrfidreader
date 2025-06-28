package com.catto.rfidreader

import android.content.Context
import com.google.android.material.snackbar.Snackbar
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object QuestManager {

    // Define all possible quests here
    val allQuests = listOf(
        Quest("palindrome_hex", "Palindrome Hunter", "Scan a card with a palindrome Hex ID."),
        Quest("binary_bounty", "Binary Repeater", "Scan a card with at least 4 repeating binary digits."),
        Quest("high_roller", "High Roller", "Scan a card with a score of 950 or higher."),
        Quest("pioneer", "Pioneer", "Scan your first 10 unique cards."),
        Quest("century_club", "Century Club", "Scan 100 total cards (including duplicates).")
    )

    fun checkQuests(view: View, context: Context, card: ScannedCard, allScannedCards: List<ScannedCard>) {
        val dao = (context.applicationContext as App).database.questDao()
        CoroutineScope(Dispatchers.IO).launch {
            // Check Pioneer Quest
            val pioneerQuest = dao.getQuestById("pioneer")
            if (pioneerQuest != null && !pioneerQuest.isCompleted) {
                if (allScannedCards.distinctBy { it.serialNumberHex }.size >= 10) {
                    completeQuest(view, pioneerQuest, dao)
                }
            }

            // Check Century Club Quest
            val centuryQuest = dao.getQuestById("century_club")
            if (centuryQuest != null && !centuryQuest.isCompleted) {
                if (allScannedCards.size >= 100) {
                    completeQuest(view, centuryQuest, dao)
                }
            }

            // Check Palindrome Quest
            val palindromeQuest = dao.getQuestById("palindrome_hex")
            if (palindromeQuest != null && !palindromeQuest.isCompleted) {
                val hexString = card.serialNumberHex.replace(" ", "")
                if (hexString.isNotEmpty() && hexString == hexString.reversed()) {
                    completeQuest(view, palindromeQuest, dao)
                }
            }

            // Check High Roller Quest
            val highRollerQuest = dao.getQuestById("high_roller")
            if (highRollerQuest != null && !highRollerQuest.isCompleted) {
                if (card.score >= 950) {
                    completeQuest(view, highRollerQuest, dao)
                }
            }

            // Check Binary Bounty Quest
            val binaryBountyQuest = dao.getQuestById("binary_bounty")
            if (binaryBountyQuest != null && !binaryBountyQuest.isCompleted) {
                val binaryString = card.binValue.replace(" ", "")
                if (binaryString.contains("0000") || binaryString.contains("1111")) {
                    completeQuest(view, binaryBountyQuest, dao)
                }
            }
        }
    }

    private suspend fun completeQuest(view: View, quest: Quest, dao: QuestDao) {
        quest.isCompleted = true
        dao.update(quest)
        // Show a Snackbar on the main thread
        CoroutineScope(Dispatchers.Main).launch {
            Snackbar.make(view, "Quest Completed: ${quest.title}", Snackbar.LENGTH_LONG).show()
        }
    }
}

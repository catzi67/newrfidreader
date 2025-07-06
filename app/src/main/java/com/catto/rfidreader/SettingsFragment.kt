package com.catto.rfidreader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : PreferenceFragmentCompat() {

    private val database by lazy { (requireActivity().application as App).database }

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val prefs = preferenceManager.sharedPreferences
            prefs?.edit {
                putString("pref_key_background_type", "IMAGE")
                putString("pref_key_background_value", it.toString())
            }
            Toast.makeText(requireContext(), "Background image set", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("pref_key_reset_score")?.setOnPreferenceClickListener {
            resetHighScore()
            true
        }

        findPreference<Preference>("pref_key_clear_history")?.setOnPreferenceClickListener {
            confirmClearHistory()
            true
        }

        findPreference<Preference>("pref_key_choose_background")?.setOnPreferenceClickListener {
            selectImageLauncher.launch("image/*")
            true
        }

        findPreference<Preference>("pref_key_reset_background")?.setOnPreferenceClickListener {
            resetBackground()
            true
        }

        findPreference<Preference>("pref_key_recalculate_stats")?.setOnPreferenceClickListener {
            confirmRecalculateStats()
            true
        }
    }

    private fun resetHighScore() {
        val prefs = requireActivity().getSharedPreferences("NfcAppPrefs", Context.MODE_PRIVATE)
        prefs.edit {
            putInt("high_score", 0)
        }
        Toast.makeText(requireContext(), "High score reset", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear History")
            .setMessage("Are you sure you want to permanently delete all scanned card history?")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    database.scannedCardDao().clearHistory()
                    Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetBackground() {
        val prefs = preferenceManager.sharedPreferences
        prefs?.edit {
            putString("pref_key_background_type", "COLOR")
            putString("pref_key_background_value", null) // Reset to default
        }
        Toast.makeText(requireContext(), "Background reset to default", Toast.LENGTH_SHORT).show()
    }

    private fun confirmRecalculateStats() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_title_recalculate_stats))
            .setMessage(getString(R.string.dialog_message_recalculate_stats))
            .setPositiveButton("Recalculate") { _, _ ->
                recalculateAllCardStats()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun recalculateAllCardStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allCards = database.scannedCardDao().getAllCardsList()
                val updatedCards = allCards.map { card ->
                    val idBytes = hexStringToByteArray(card.serialNumberHex)
                    val newStats = BattleManager.generateStats(idBytes)
                    card.copy(battleStats = newStats)
                }
                database.scannedCardDao().updateAll(updatedCards)

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.toast_recalculation_complete), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("SettingsFragment", "Error recalculating stats", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(R.string.toast_recalculation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

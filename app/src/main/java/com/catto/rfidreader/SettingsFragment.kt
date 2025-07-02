package com.catto.rfidreader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.launch

class SettingsFragment : PreferenceFragmentCompat() {

    private val database by lazy { (requireActivity().application as App).database }

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val prefs = preferenceManager.sharedPreferences
            prefs?.edit()?.putString("pref_key_background_type", "IMAGE")?.apply()
            prefs?.edit()?.putString("pref_key_background_value", it.toString())?.apply()
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
    }

    private fun resetHighScore() {
        val prefs = requireActivity().getSharedPreferences("NfcAppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("high_score", 0).apply()
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
        prefs?.edit()?.putString("pref_key_background_type", "COLOR")?.apply()
        prefs?.edit()?.putString("pref_key_background_value", null)?.apply() // Reset to default
        Toast.makeText(requireContext(), "Background reset to default", Toast.LENGTH_SHORT).show()
    }
}

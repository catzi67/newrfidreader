package com.catto.rfidreader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.preference.SwitchPreferenceCompat

class SettingsFragment : PreferenceFragmentCompat() {

    private val photoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                requireActivity().contentResolver.takePersistableUriPermission(uri, flags)
                val prefs = requireActivity().getSharedPreferences("NfcAppPrefs", Context.MODE_PRIVATE)
                prefs.edit { putString("background_image_uri", uri.toString()) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        // --- ADD THIS LOGIC ---
        val gameifySwitch = findPreference<SwitchPreferenceCompat>("pref_key_gameify")
        val resetHighScorePref = findPreference<Preference>("pref_key_reset_high_score")

        // Set the initial visibility of the reset option based on the switch's state
        resetHighScorePref?.isVisible = gameifySwitch?.isChecked ?: true

        // Add a listener to hide/show the reset option when the switch is toggled
        gameifySwitch?.setOnPreferenceChangeListener { _, newValue ->
            resetHighScorePref?.isVisible = newValue as Boolean
            true
        }


        // Handler for "Choose Background"
        findPreference<Preference>("pref_key_choose_background")?.setOnPreferenceClickListener {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            true
        }

        findPreference<Preference>("pref_key_reset_background")?.setOnPreferenceClickListener {
            val prefs = requireActivity().getSharedPreferences("NfcAppPrefs", Context.MODE_PRIVATE)
            prefs.edit {
                remove("background_image_uri")
            }
            Toast.makeText(requireContext(), getString(R.string.toast_background_reset), Toast.LENGTH_SHORT).show()
            true
        }

        // Handler for "Reset High Score"
        findPreference<Preference>("pref_key_reset_score")?.setOnPreferenceClickListener {
            showConfirmationDialog("Reset High Score?", "This will reset your high score to 0.") {
                val prefs = requireActivity().getSharedPreferences("NfcAppPrefs", Context.MODE_PRIVATE)
                prefs.edit { putInt("high_score", 0) }
            }
            true
        }

        // Handler for "Clear History"
        findPreference<Preference>("pref_key_clear_history")?.setOnPreferenceClickListener {
            showConfirmationDialog("Clear Scan History?", "This action cannot be undone.") {
                val dao = (requireActivity().application as App).database.scannedCardDao()
                lifecycleScope.launch {
                    dao.clearHistory()
                }
            }
            true
        }
    }

    private fun showConfirmationDialog(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
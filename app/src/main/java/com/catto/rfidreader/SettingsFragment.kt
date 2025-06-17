package com.catto.rfidreader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.github.dhaval2404.colorpicker.ColorPickerDialog
import com.github.dhaval2404.colorpicker.model.ColorShape
import kotlinx.coroutines.launch
import androidx.activity.result.PickVisualMediaRequest

class SettingsFragment : PreferenceFragmentCompat() {

    private val photoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                requireActivity().contentResolver.takePersistableUriPermission(uri, flags)
                // Save the URI and set the background type to IMAGE
                saveBackgroundPreference("IMAGE", uri.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val gameifySwitch = findPreference<SwitchPreferenceCompat>("pref_key_gameify")
        val resetHighScorePref = findPreference<Preference>("pref_key_reset_score")
        resetHighScorePref?.isVisible = gameifySwitch?.isChecked ?: true
        gameifySwitch?.setOnPreferenceChangeListener { _, newValue ->
            resetHighScorePref?.isVisible = newValue as Boolean
            true
        }

        // Handler for "Set Background Color"
        findPreference<Preference>("pref_key_set_background_color")?.setOnPreferenceClickListener {
            ColorPickerDialog
                .Builder(requireContext())
                .setTitle("Pick a Color")
                .setColorShape(ColorShape.SQAURE)
                .setDefaultColor(android.R.color.darker_gray)
                .setColorListener { color, _ ->
                    saveBackgroundPreference("COLOR", color.toString())
                }
                .show()
            true
        }

        // Handler for "Choose Background Image"
        findPreference<Preference>("pref_key_choose_background")?.setOnPreferenceClickListener {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            true
        }

        // --- THIS IS THE CORRECTED LISTENER ---
        findPreference<Preference>("pref_key_reset_background")?.setOnPreferenceClickListener {
            val prefs = requireActivity().getSharedPreferences("NfcAppPrefs", Context.MODE_PRIVATE)
            prefs.edit {
                // Before: remove("background_image_uri")
                // After: Remove the correct keys
                remove("pref_key_background_type")
                remove("pref_key_background_value")
            }
            Toast.makeText(requireContext(), getString(R.string.toast_background_reset), Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>("pref_key_reset_score")?.setOnPreferenceClickListener {
            showConfirmationDialog("Reset High Score?", "This will reset your high score to 0.") {
                val prefs = requireActivity().getSharedPreferences("NfcAppPrefs", Context.MODE_PRIVATE)
                prefs.edit { putInt("high_score", 0) }
            }
            true
        }

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

    private fun saveBackgroundPreference(type: String, value: String) {
        val prefs = requireActivity().getSharedPreferences("NfcAppPrefs", Context.MODE_PRIVATE)
        prefs.edit {
            putString("pref_key_background_type", type)
            putString("pref_key_background_value", value)
        }
        Toast.makeText(requireContext(), "Background updated", Toast.LENGTH_SHORT).show()
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
package com.example.newrfidreader

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

        // Handler for "Choose Background"
        findPreference<Preference>("pref_key_choose_background")?.setOnPreferenceClickListener {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
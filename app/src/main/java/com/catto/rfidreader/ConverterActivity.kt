package com.catto.rfidreader

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import java.math.BigInteger

class ConverterActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "NfcAppPrefs"
        private const val TAG = "ConverterActivity"
    }

    private lateinit var inputEditText: TextInputEditText
    private lateinit var inputTypeGroup: RadioGroup
    private lateinit var convertButton: Button
    private lateinit var resultsCard: MaterialCardView
    private lateinit var hexValue: TextView
    private lateinit var decValue: TextView
    private lateinit var binValue: TextView
    private lateinit var revHexValue: TextView
    private lateinit var revDecValue: TextView
    private lateinit var revBinValue: TextView
    private lateinit var rootLayout: ConstraintLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_converter)

        val toolbar: Toolbar = findViewById(R.id.converter_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        rootLayout = findViewById(R.id.converter_root_layout)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        inputEditText = findViewById(R.id.input_edit_text)
        inputTypeGroup = findViewById(R.id.input_type_group)
        convertButton = findViewById(R.id.convert_button)
        resultsCard = findViewById(R.id.results_card)
        hexValue = findViewById(R.id.hex_value)
        decValue = findViewById(R.id.dec_value)
        binValue = findViewById(R.id.bin_value)
        revHexValue = findViewById(R.id.rev_hex_value)
        revDecValue = findViewById(R.id.rev_dec_value)
        revBinValue = findViewById(R.id.rev_bin_value)

        convertButton.setOnClickListener {
            performConversion()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAndApplySettings()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun performConversion() {
        val inputText = inputEditText.text.toString().trim()
        if (inputText.isEmpty()) {
            Toast.makeText(this, "Please enter a value.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedRadioButtonId = inputTypeGroup.checkedRadioButtonId
        val selectedRadioButton: RadioButton = findViewById(selectedRadioButtonId)

        try {
            val bigInt: BigInteger = when (selectedRadioButton.id) {
                R.id.radio_hex -> BigInteger(inputText.replace(" ", ""), 16)
                R.id.radio_dec -> BigInteger(inputText)
                R.id.radio_bin -> BigInteger(inputText.replace(" ", ""), 2)
                else -> throw IllegalArgumentException("Invalid input type selected")
            }

            val bigEndianBytes = bigInt.toByteArray().let {
                if (it.size > 1 && it[0] == 0.toByte() && bigInt > BigInteger.ZERO) {
                    it.sliceArray(1 until it.size)
                } else {
                    it
                }
            }

            val littleEndianBytes = bigEndianBytes.reversedArray()

            hexValue.text = bytesToHexString(bigEndianBytes)
            decValue.text = bytesToDecString(bigEndianBytes)
            binValue.text = bytesToBinString(bigEndianBytes)

            revHexValue.text = bytesToHexString(littleEndianBytes)
            revDecValue.text = bytesToDecString(littleEndianBytes)
            revBinValue.text = bytesToBinString(littleEndianBytes)

            resultsCard.visibility = View.VISIBLE

        } catch (_: NumberFormatException) {
            Toast.makeText(this, "Invalid number format for the selected type.", Toast.LENGTH_LONG).show()
            resultsCard.visibility = View.INVISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "An error occurred during conversion", e)
            Toast.makeText(this, "An error occurred: ${e.message}", Toast.LENGTH_LONG).show()
            resultsCard.visibility = View.INVISIBLE
        }
    }

    private fun loadAndApplySettings() {
        loadSavedBackground()
        applyTextSize()
    }

    private fun applyTextSize() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val textSizePref = prefs.getString("pref_key_text_size", "small")
        val (labelSize, valueSize) = when (textSizePref) {
            "medium" -> 16f to 18f
            "large" -> 18f to 20f
            else -> 14f to 16f // "small"
        }

        val valueTextViews = listOf(hexValue, decValue, binValue, revHexValue, revDecValue, revBinValue)
        valueTextViews.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSize) }

        val labelTextViews = listOf<TextView>(
            findViewById(R.id.hex_label), findViewById(R.id.dec_label), findViewById(R.id.bin_label),
            findViewById(R.id.rev_hex_label), findViewById(R.id.rev_dec_label), findViewById(R.id.rev_bin_label)
        )
        labelTextViews.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize) }
    }


    private fun loadSavedBackground() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val backgroundType = prefs.getString("pref_key_background_type", "COLOR")
        val backgroundValue = prefs.getString("pref_key_background_value", null)

        when (backgroundType) {
            "IMAGE" -> {
                if (backgroundValue != null) {
                    try {
                        val uri = backgroundValue.toUri()
                        loadBackgroundFromUri(uri)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse or load background URI", e)
                        Toast.makeText(this, getString(R.string.toast_failed_to_load_saved_background), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "COLOR" -> {
                val color = backgroundValue?.toIntOrNull() ?: Color.DKGRAY
                rootLayout.setBackgroundColor(color)
            }
            else -> {
                rootLayout.setBackgroundColor(Color.DKGRAY)
            }
        }
    }

    private fun loadBackgroundFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val drawable = Drawable.createFromStream(inputStream, uri.toString())
            rootLayout.background = drawable
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create drawable from URI", e)
            Toast.makeText(this, getString(R.string.toast_failed_to_load_image), Toast.LENGTH_SHORT).show()
        }
    }

    private fun bytesToHexString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun bytesToDecString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return BigInteger(1, bytes).toString()
    }

    private fun bytesToBinString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return bytes.joinToString(" ") { byte ->
            String.format("%8s", Integer.toBinaryString(byte.toInt() and 0xFF)).replace(' ', '0')
        }
    }
}

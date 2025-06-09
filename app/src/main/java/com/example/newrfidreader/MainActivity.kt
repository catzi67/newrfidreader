package com.example.newrfidreader


import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.math.BigInteger

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var nfcSerialNumberTextView: TextView
    private lateinit var reverseButton: Button
    private lateinit var formatRadioGroup: RadioGroup

    private var isReversed = false
    private var originalSerialNumberBytes: ByteArray? = null

    // Enum to represent the number systems
    private enum class NumberFormat { HEX, DEC, BIN }
    private var currentFormat = NumberFormat.HEX

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nfcSerialNumberTextView = findViewById(R.id.nfc_serial_number)
        reverseButton = findViewById(R.id.reverse_button)
        formatRadioGroup = findViewById(R.id.format_radio_group)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC is not available on this device.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupButtonListeners()
    }

    private fun setupButtonListeners() {
        reverseButton.setOnClickListener {
            val currentText = nfcSerialNumberTextView.text.toString()
            if (originalSerialNumberBytes != null) {
                if (isReversed) {
                    // If it's reversed, just display the original formatted number
                    displaySerialNumber()
                    reverseButton.text = "Reverse"
                } else {
                    nfcSerialNumberTextView.text = currentText.reversed()
                    reverseButton.text = "Un-reverse"
                }
                isReversed = !isReversed
            }
        }

        formatRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentFormat = when (checkedId) {
                R.id.radio_dec -> NumberFormat.DEC
                R.id.radio_bin -> NumberFormat.BIN
                else -> NumberFormat.HEX
            }
            // When format changes, display the number in the new format and reset the reverse state
            isReversed = false
            reverseButton.text = "Reverse"
            displaySerialNumber()
        }
    }

    override fun onResume() {
        super.onResume()
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            tag?.let {
                originalSerialNumberBytes = it.id
                // Reset state for the new card
                isReversed = false
                reverseButton.text = "Reverse"
                // Display the number in the currently selected format
                displaySerialNumber()
            }
        }
    }

    private fun displaySerialNumber() {
        originalSerialNumberBytes?.let { bytes ->
            val numberAsString = when (currentFormat) {
                NumberFormat.HEX -> bytesToHexString(bytes)
                NumberFormat.DEC -> bytesToDecString(bytes)
                NumberFormat.BIN -> bytesToBinString(bytes)
            }
            nfcSerialNumberTextView.text = numberAsString
        }
    }

    // --- Conversion Functions ---

    private fun bytesToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun bytesToDecString(bytes: ByteArray): String {
        // Use BigInteger to handle large numbers that would overflow a Long
        return BigInteger(1, bytes).toString()
    }

    private fun bytesToBinString(bytes: ByteArray): String {
        return BigInteger(1, bytes).toString(2)
    }
}
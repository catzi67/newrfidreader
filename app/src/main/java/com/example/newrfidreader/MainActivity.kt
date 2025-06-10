package com.example.newrfidreader

import android.app.PendingIntent
import android.util.Log
import android.content.Intent
import android.graphics.drawable.Drawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.math.BigInteger

class MainActivity : AppCompatActivity() {

    // Add a TAG for logging
    private val TAG = "NFCApp"

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var nfcSerialNumberTextView: TextView
    private lateinit var reverseButton: Button
    private lateinit var formatRadioGroup: RadioGroup
    private lateinit var selectBackgroundButton: Button
    private lateinit var mainLayout: ConstraintLayout

    private var isReversed = false
    // Holds the original, unmodified bytes from the NFC tag
    private var originalSerialNumberBytes: ByteArray? = null
    // Holds the bytes to be displayed (can be original or reversed)
    private var displayedSerialNumberBytes: ByteArray? = null

    private enum class NumberFormat { HEX, DEC, BIN }
    private var currentFormat = NumberFormat.HEX

    private val photoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val drawable = Drawable.createFromStream(inputStream, uri.toString())
                mainLayout.background = drawable
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Add this log message
        Log.d(TAG, "onCreate: Activity created.")


        nfcSerialNumberTextView = findViewById(R.id.nfc_serial_number)
        reverseButton = findViewById(R.id.reverse_button)
        formatRadioGroup = findViewById(R.id.format_radio_group)
        selectBackgroundButton = findViewById(R.id.select_background_button)
        mainLayout = findViewById(R.id.main_layout)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, "NFC is not available on this device.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.d(TAG, "onCreate: NFC Adapter found.")
        setupButtonListeners()
    }

    private fun setupButtonListeners() {
        reverseButton.setOnClickListener {
            if (originalSerialNumberBytes == null) return@setOnClickListener

            isReversed = !isReversed

            if (isReversed) {
                // Reverse the actual byte array
                displayedSerialNumberBytes = originalSerialNumberBytes?.reversedArray()
                reverseButton.text = "Un-reverse"
            } else {
                // Revert to the original byte array
                displayedSerialNumberBytes = originalSerialNumberBytes
                reverseButton.text = "Reverse"
            }
            // Update the display with the newly ordered bytes
            displaySerialNumber()
        }

        formatRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentFormat = when (checkedId) {
                R.id.radio_dec -> NumberFormat.DEC
                R.id.radio_bin -> NumberFormat.BIN
                else -> NumberFormat.HEX
            }
            // When format changes, reset the reversal state and display
            isReversed = false
            reverseButton.text = "Reverse"
            displayedSerialNumberBytes = originalSerialNumberBytes
            displaySerialNumber()
        }

        selectBackgroundButton.setOnClickListener {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    override fun onResume() {
        super.onResume()
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)

        // Add this log message
        Log.d(TAG, "onResume: Foreground dispatch enabled.")
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)

        // Add this log message
        Log.d(TAG, "onPause: Foreground dispatch disabled.")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
            // Add this log to see if we get inside the 'if' block
            Log.d(TAG, "onNewIntent: TAG_DISCOVERED intent.")


            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            tag?.let {
                Log.d(TAG, "onNewIntent: Tag parsed successfully.")
                // Store the original bytes
                originalSerialNumberBytes = it.id
                // Set the initial display bytes to the original order
                displayedSerialNumberBytes = originalSerialNumberBytes
                // Reset state for the new card
                isReversed = false
                reverseButton.text = "Reverse"
                // Display the number in the currently selected format
                displaySerialNumber()
            }
        }
    }

    private fun displaySerialNumber() {
        // Always use the 'displayedSerialNumberBytes' for conversion
        displayedSerialNumberBytes?.let { bytes ->
            val numberAsString = when (currentFormat) {
                NumberFormat.HEX -> bytesToHexString(bytes)
                NumberFormat.DEC -> bytesToDecString(bytes)
                NumberFormat.BIN -> bytesToBinString(bytes)
            }
            nfcSerialNumberTextView.text = numberAsString
        }
    }

    private fun bytesToHexString(bytes: ByteArray): String {
        // Create hex string by joining each byte, separated by a space for readability
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun bytesToDecString(bytes: ByteArray): String {
        return BigInteger(1, bytes).toString()
    }

    private fun bytesToBinString(bytes: ByteArray): String {
        return BigInteger(1, bytes).toString(2)
    }
}
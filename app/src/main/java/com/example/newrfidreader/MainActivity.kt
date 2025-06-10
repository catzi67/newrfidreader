package com.example.newrfidreader

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Drawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.math.BigInteger

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var nfcSerialNumberTextView: TextView
    private lateinit var reverseButton: Button
    private lateinit var formatRadioGroup: RadioGroup
    private lateinit var selectBackgroundButton: Button
    private lateinit var mainLayout: ConstraintLayout

    private lateinit var radioHex: RadioButton
    private lateinit var radioDec: RadioButton
    private lateinit var radioBin: RadioButton

    private var isReversed = false
    private var originalSerialNumberBytes: ByteArray? = null
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

        nfcSerialNumberTextView = findViewById(R.id.nfc_serial_number)
        reverseButton = findViewById(R.id.reverse_button)
        formatRadioGroup = findViewById(R.id.format_radio_group)
        selectBackgroundButton = findViewById(R.id.select_background_button)
        mainLayout = findViewById(R.id.main_layout)

        // Add these to get references to the individual radio buttons
        radioHex = findViewById(R.id.radio_hex)
        radioDec = findViewById(R.id.radio_dec)
        radioBin = findViewById(R.id.radio_bin)

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
            if (originalSerialNumberBytes == null) return@setOnClickListener

            isReversed = !isReversed

            if (isReversed) {
                displayedSerialNumberBytes = originalSerialNumberBytes?.reversedArray()
                reverseButton.text = "Un-reverse"
            } else {
                displayedSerialNumberBytes = originalSerialNumberBytes
                reverseButton.text = "Reverse"
            }
            displaySerialNumber()
        }

        formatRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentFormat = when (checkedId) {
                R.id.radio_dec -> NumberFormat.DEC
                R.id.radio_bin -> NumberFormat.BIN
                else -> NumberFormat.HEX
            }
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
                displayedSerialNumberBytes = originalSerialNumberBytes
                isReversed = false
                reverseButton.text = "Reverse"
                displaySerialNumber()

                // --- This is the new part ---
                // Enable the controls now that we have a number
                setControlsEnabled(true)
            }
        }
    }

    // --- Add this new helper function ---
    private fun setControlsEnabled(isEnabled: Boolean) {
        reverseButton.isEnabled = isEnabled
        formatRadioGroup.isEnabled = isEnabled
        radioHex.isEnabled = isEnabled
        radioDec.isEnabled = isEnabled
        radioBin.isEnabled = isEnabled
    }

    private fun displaySerialNumber() {
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
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    private fun bytesToDecString(bytes: ByteArray): String {
        return BigInteger(1, bytes).toString()
    }

    private fun bytesToBinString(bytes: ByteArray): String {
        return BigInteger(1, bytes).toString(2)
    }
}
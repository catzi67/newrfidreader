package com.example.newrfidreader

import android.app.PendingIntent
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

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var nfcSerialNumberTextView: TextView
    private lateinit var reverseButton: Button
    private lateinit var formatRadioGroup: RadioGroup
    private lateinit var selectBackgroundButton: Button
    private lateinit var mainLayout: ConstraintLayout // Reference to the main layout

    private var isReversed = false
    private var originalSerialNumberBytes: ByteArray? = null

    private enum class NumberFormat { HEX, DEC, BIN }
    private var currentFormat = NumberFormat.HEX

    // Modern way to handle activity results like picking a photo.
    // This launcher will open the photo picker.
    private val photoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // This block is executed when the user selects an image (or closes the picker).
        if (uri != null) {
            // User selected an image. The URI points to the image.
            try {
                // Convert the URI to a Drawable and set it as the background
                val inputStream = contentResolver.openInputStream(uri)
                val drawable = Drawable.createFromStream(inputStream, uri.toString())
                mainLayout.background = drawable
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
            }
        } else {
            // User closed the picker without selecting an image.
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize all UI components
        nfcSerialNumberTextView = findViewById(R.id.nfc_serial_number)
        reverseButton = findViewById(R.id.reverse_button)
        formatRadioGroup = findViewById(R.id.format_radio_group)
        selectBackgroundButton = findViewById(R.id.select_background_button)
        mainLayout = findViewById(R.id.main_layout) // Get the main layout

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
            // ... (this logic remains the same)
        }

        formatRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            // ... (this logic remains the same)
        }

        // Add a listener for our new button
        selectBackgroundButton.setOnClickListener {
            // Launch the photo picker
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    // ... all other functions (onResume, onPause, onNewIntent, displaySerialNumber, etc.) remain the same
}
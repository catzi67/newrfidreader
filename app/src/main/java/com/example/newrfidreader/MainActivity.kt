package com.example.newrfidreader

import android.os.Build // <-- Make sure this import is present
import android.content.ClipData
import android.content.ClipboardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.ImageButton // <-- Make sure this import is here
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.math.BigInteger
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.core.net.toUri
import androidx.core.content.edit
import com.google.android.material.card.MaterialCardView
import android.os.Handler
import android.os.Looper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat


class MainActivity : AppCompatActivity() {

    // --- NEW: A companion object for class-level constants ---
    companion object {
        private const val PREFS_NAME = "NfcAppPrefs"
        private const val PREF_KEY_BACKGROUND_URI = "background_image_uri"
    }

    private val database by lazy { (application as App).database.scannedCardDao() }

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var nfcSerialNumberTextView: TextView
    private lateinit var reverseButton: ImageButton
    private lateinit var formatRadioGroup: RadioGroup
    private lateinit var selectBackgroundButton: ImageButton
    private lateinit var mainLayout: ConstraintLayout
    private lateinit var historyButton: ImageButton // <-- ADD THIS
    private lateinit var copyFab: FloatingActionButton // NEW
    private lateinit var radioHex: RadioButton
    private lateinit var radioDec: RadioButton
    private lateinit var radioBin: RadioButton
    private lateinit var infoCard: MaterialCardView
    private val resetHandler = Handler(Looper.getMainLooper())
    private lateinit var resetRunnable: Runnable

    private var isReversed = false
    private var originalSerialNumberBytes: ByteArray? = null
    private var displayedSerialNumberBytes: ByteArray? = null

    private lateinit var nfcTagInfoTextView: TextView // NEW

    private enum class NumberFormat { HEX, DEC, BIN }
    private var currentFormat = NumberFormat.HEX

    private val photoPickerLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            // --- MODIFIED: Added saving logic ---
            try {
                // Take persistent permission to access this URI across app restarts
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)

                // Save the URI string to SharedPreferences
                saveBackgroundUri(uri.toString())

                // Load the image into the background
                loadBackgroundFromUri(uri)

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, getString(R.string.toast_failed_to_load_image), Toast.LENGTH_SHORT).show() // CHANGED
            }
        } else {
            Toast.makeText(this, getString(R.string.toast_no_image_selected), Toast.LENGTH_SHORT).show() // CHANGED

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // --- ADD THIS LINE FIRST ---
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nfcSerialNumberTextView = findViewById(R.id.nfc_serial_number)
        reverseButton = findViewById(R.id.reverse_button)
        formatRadioGroup = findViewById(R.id.format_radio_group)
        selectBackgroundButton = findViewById(R.id.select_background_button)
        historyButton = findViewById(R.id.history_button) // <-- ADD THIS
        mainLayout = findViewById(R.id.main_layout)
        nfcTagInfoTextView = findViewById(R.id.nfc_tag_info) // NEW
        copyFab = findViewById(R.id.fab_copy) // NEW: Initialize the FAB
        infoCard = findViewById(R.id.info_card) // <-- NEW: Initialize the card view
        mainLayout = findViewById(R.id.main_layout)

        // --- NEW: Define what the reset Runnable does ---
        resetRunnable = Runnable {
            // This code will run after the delay
            resetUI()
        }


        // Add these to get references to the individual radio buttons
        radioHex = findViewById(R.id.radio_hex)
        radioDec = findViewById(R.id.radio_dec)
        radioBin = findViewById(R.id.radio_bin)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.toast_nfc_not_available), Toast.LENGTH_LONG).show() // CHANGED
            finish()
            return

        }

        val historyButton: ImageButton = findViewById(R.id.history_button)

        historyButton.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        setupButtonListeners()

        // --- NEW: Load the saved background on startup ---
        resetUI()
        loadSavedBackground()

        // This listener will be called when the layout is ready, providing the system bar insets.
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { view, windowInsets ->
        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply the insets as padding to the view. This will push your content
            // down from the status bar and up from the navigation bar.
        view.setPadding(insets.left, insets.top, insets.right, insets.bottom)

            // Return a default value to signal that we've handled the insets.
         WindowInsetsCompat.CONSUMED
        }

    }

    private fun resetUI() {
        nfcSerialNumberTextView.text = getString(R.string.scan_prompt) // CHANGED
        nfcTagInfoTextView.text = "" // Clear the tag info
        setControlsEnabled(false)
        copyFab.hide() // NEW: Hide the FAB when there's nothing to copy
        // --- NEW ---
        // Set the card's initial state for the animation: invisible and moved down
        infoCard.alpha = 0f
        infoCard.translationY = 50f // Move it down by 50 pixels
    }

    private fun setupButtonListeners() {
        reverseButton.setOnClickListener {
            if (originalSerialNumberBytes == null) return@setOnClickListener

            isReversed = !isReversed

            if (isReversed) {
                displayedSerialNumberBytes = originalSerialNumberBytes?.reversedArray()
                reverseButton.setImageResource(R.drawable.ic_undo_24)
            } else {
                displayedSerialNumberBytes = originalSerialNumberBytes
                reverseButton.setImageResource(R.drawable.ic_swap_horiz_24)
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
            reverseButton.setImageResource(R.drawable.ic_swap_horiz_24)
            displayedSerialNumberBytes = originalSerialNumberBytes
            displaySerialNumber()
        }

        selectBackgroundButton.setOnClickListener {
            photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        historyButton.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        // NEW: Add a listener for our new FAB
        copyFab.setOnClickListener {
            val serialNumber = nfcSerialNumberTextView.text.toString()
            if (serialNumber.isNotBlank() && serialNumber != "Scan an RFID Card") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("RFID Serial Number", serialNumber)
                clipboard.setPrimaryClip(clip)

                // Give the user feedback
                Toast.makeText(this, getString(R.string.toast_copied_to_clipboard), Toast.LENGTH_SHORT).show() // CHANGED

            }
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

        // --- NEW: Cancel any pending reset if the user leaves the activity ---
        resetHandler.removeCallbacks(resetRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {

            // --- NEW: Cancel any previous timer when a new card is scanned ---
            resetHandler.removeCallbacks(resetRunnable)

            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Use the new, type-safe method for Android 13 (API 33) and higher
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                // Use the old, deprecated method for older versions
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }

            // --- The rest of the function remains the same ---

            tag?.let {
                // --- This is the new part ---
                val tagInfo = parseTagInfo(it)
                nfcTagInfoTextView.text = tagInfo

                originalSerialNumberBytes = it.id
                val serialNumber = it.id.toHexString() // Get hex string for saving

                displayedSerialNumberBytes = originalSerialNumberBytes
                isReversed = false
                reverseButton.setImageResource(R.drawable.ic_swap_horiz_24)
                displaySerialNumber()
                setControlsEnabled(true)
                copyFab.show() // NEW: Show the FAB now that there is data

                // --- NEW ---
                // Animate the card view to its final state
                infoCard.animate()
                    .alpha(1f) // Fade in to full opacity
                    .translationY(0f) // Move it back to its original Y position
                    .setDuration(400) // Animation duration in milliseconds
                    .start()

                // --- SAVE TO DATABASE ---
                val cardToSave = ScannedCard(
                    serialNumberHex = serialNumber,
                    tagInfo = tagInfo,
                    scanTimestamp = System.currentTimeMillis()
                )
                lifecycleScope.launch {
                    database.insert(cardToSave)
                }
                // --- NEW: Schedule the UI to reset in 10 seconds ---
                resetHandler.postDelayed(resetRunnable, 10000) // 10,000 milliseconds = 10 seconds

            }
        }
    }

    // --- Add this new parsing function ---
    private fun parseTagInfo(tag: Tag): String {
        val sb = StringBuilder()
        val techList = tag.techList.map { it.substringAfterLast('.') }
        sb.append("Technologies: ").append(techList.joinToString(", ")).append("\n")

        // Check for specific technologies and extract more detailed info
        for (tech in techList) {
            when (tech) {
                "MifareClassic" -> {
                    MifareClassic.get(tag)?.use { mifare ->
                        val type = when (mifare.type) {
                            MifareClassic.TYPE_CLASSIC -> "MIFARE Classic"
                            MifareClassic.TYPE_PLUS -> "MIFARE Plus"
                            MifareClassic.TYPE_PRO -> "MIFARE Pro"
                            else -> "Unknown MIFARE"
                        }
                        sb.append("Type: ").append(type).append("\n")
                        sb.append("Size: ").append(mifare.size).append(" bytes\n")
                        sb.append("Sectors: ").append(mifare.sectorCount).append("\n")
                    }
                }
                "NfcA" -> {
                    NfcA.get(tag)?.use { nfcA ->
                        sb.append("ATQA: 0x").append(nfcA.atqa.toHexString()).append("\n")
                        sb.append("SAK: 0x").append(Integer.toHexString(nfcA.sak.toInt())).append("\n")
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    // --- Add this helper extension function for converting bytes to hex ---
    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }



    // --- NEW: Helper function to load the saved background URI ---
    private fun loadSavedBackground() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(PREF_KEY_BACKGROUND_URI, null)
        if (uriString != null) {
            try {
                // BEFORE: val uri = Uri.parse(uriString)
                // AFTER:
                val uri = uriString.toUri() // This is the change

                loadBackgroundFromUri(uri)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, getString(R.string.toast_failed_to_load_saved_background), Toast.LENGTH_SHORT).show() // CHANGED

            }
        }
    }

    // --- NEW: Helper function to save the background URI string ---
    private fun saveBackgroundUri(uriString: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // The KTX version is cleaner and safer.
        // It automatically handles the .apply() call.
        prefs.edit {
            putString(PREF_KEY_BACKGROUND_URI, uriString)
        }
    }

    // --- NEW: Refactored image loading logic into its own function ---
    private fun loadBackgroundFromUri(uri: Uri) {
        val inputStream = contentResolver.openInputStream(uri)
        val drawable = Drawable.createFromStream(inputStream, uri.toString())
        mainLayout.background = drawable
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
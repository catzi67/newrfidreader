package com.catto.rfidreader

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NFCApp"
        private const val PREFS_NAME = "NfcAppPrefs"
        private const val PREF_KEY_BACKGROUND_URI = "background_image_uri"
        private const val PREF_KEY_HIGH_SCORE = "high_score"
        private const val SCORING_EXPONENT = 3.5
    }

    // --- UI VIEW REFERENCES ---
    private lateinit var mainLayout: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var infoCard: MaterialCardView
    private lateinit var promptCard: MaterialCardView
    private lateinit var scoreCard: MaterialCardView
    private lateinit var hexValue: TextView
    private lateinit var decValue: TextView
    private lateinit var binValue: TextView
    private lateinit var revHexValue: TextView
    private lateinit var revDecValue: TextView
    private lateinit var revBinValue: TextView
    private lateinit var nfcTagInfoTextView: TextView
    private lateinit var scoreValueText: TextView
    private lateinit var highScoreValueText: TextView
    private lateinit var historyButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var copyFab: FloatingActionButton

    // --- State and Logic Variables ---
    private var nfcAdapter: NfcAdapter? = null
    private val resetHandler = Handler(Looper.getMainLooper())
    private lateinit var resetRunnable: Runnable
    private var highScore = 0
    private val database by lazy { (application as App).database.scannedCardDao() }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize all views
        mainLayout = findViewById(R.id.main_layout)
        infoCard = findViewById(R.id.info_card)
        promptCard = findViewById(R.id.prompt_card)
        scoreCard = findViewById(R.id.score_card)
        hexValue = findViewById(R.id.hex_value)
        decValue = findViewById(R.id.dec_value)
        binValue = findViewById(R.id.bin_value)
        revHexValue = findViewById(R.id.rev_hex_value)
        revDecValue = findViewById(R.id.rev_dec_value)
        revBinValue = findViewById(R.id.rev_bin_value)
        nfcTagInfoTextView = findViewById(R.id.nfc_tag_info)
        scoreValueText = findViewById(R.id.score_value_text)
        highScoreValueText = findViewById(R.id.high_score_value_text)
        historyButton = findViewById(R.id.history_button)
        shareButton = findViewById(R.id.share_button)
        settingsButton = findViewById(R.id.settings_button)
        copyFab = findViewById(R.id.fab_copy)

        resetRunnable = Runnable { resetUI() }

        // Set initial animated state
        infoCard.alpha = 0f
        promptCard.alpha = 0f
        scoreCard.alpha = 0f

        loadHighScore()
        setupButtonListeners()
        resetUI()
    }

    override fun onResume() {
        super.onResume()
        // Refresh UI in case settings were changed
        loadHighScore()
        loadSavedBackground()
        setupForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
        resetHandler.removeCallbacks(resetRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent received with action: ${intent.action}")
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
            handleNfcTag(intent)
        }
    }

    private fun setupForegroundDispatch() {
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            Toast.makeText(this, getString(R.string.toast_nfc_not_available), Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        // The flag MUST be FLAG_MUTABLE so the NFC system can add the tag data to the intent.
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE
        )

        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun handleNfcTag(intent: Intent) {
        resetHandler.removeCallbacks(resetRunnable)

        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        tag?.let {
            // --- THIS IS THE UPDATED LOGIC ---

            // The raw tag ID is typically Little-Endian. This is our "Reversed" value.
            val littleEndianBytes = it.id
            // Reversing the byte order gives us the standard Big-Endian value for our "Forward" value.
            val bigEndianBytes = littleEndianBytes.reversedArray()

            // Populate the UI according to the new definitions
            hexValue.text = bytesToHexString(bigEndianBytes)
            decValue.text = bytesToDecString(bigEndianBytes)
            binValue.text = bytesToBinString(bigEndianBytes)

            revHexValue.text = bytesToHexString(littleEndianBytes)
            revDecValue.text = bytesToDecString(littleEndianBytes)
            revBinValue.text = bytesToBinString(littleEndianBytes)

            val tagInfo = parseTagInfo(it)
            nfcTagInfoTextView.text = tagInfo

            // The score should be calculated from the standard Big-Endian value
            val score = calculateScore(bigEndianBytes)
            scoreValueText.text = score.toString()
            if (score > highScore) {
                highScore = score
                saveHighScore(highScore)
                highScoreValueText.text = highScore.toString()
                showCongratsSnackbar()
            }

            // --- SAVE TO DB ---
            // We will save the standard Big-Endian representation as the primary value.
            lifecycleScope.launch {
                database.insert(
                    ScannedCard(
                        serialNumberHex = hexValue.text.toString(), // Big-Endian
                        decValue = decValue.text.toString(),
                        binValue = binValue.text.toString(),
                        revHexValue = revHexValue.text.toString(), // Little-Endian
                        revDecValue = revDecValue.text.toString(),
                        revBinValue = revBinValue.text.toString(),
                        score = score,
                        tagInfo = tagInfo,
                        scanTimestamp = System.currentTimeMillis()
                    )
                )
            }

            // --- UPDATE UI ---
            promptCard.animate().alpha(0f).setDuration(400).start()
            infoCard.animate().alpha(1f).translationY(0f).setDuration(400).start()
            scoreCard.animate().alpha(1f).translationY(0f).setDuration(400).start()

            setControlsEnabled(true)
            copyFab.show()
            resetHandler.postDelayed(resetRunnable, 10000)
        }
    }

    private fun setupButtonListeners() {
        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        shareButton.setOnClickListener {
            val shareText = """
                NFC Card Scan:
                Hex: ${hexValue.text}
                Decimal: ${decValue.text}
                Binary: ${binValue.text}
                ---
                Reversed Hex: ${revHexValue.text}
                Reversed Dec: ${revDecValue.text}
                Reversed Bin: ${revBinValue.text}
                ---
                Score: ${scoreValueText.text} (High: $highScore)
                ---
                ${nfcTagInfoTextView.text}
            """.trimIndent()

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
            }
            val shareIntent = Intent.createChooser(sendIntent, "Share Card Details")
            startActivity(shareIntent)
        }

        copyFab.setOnClickListener {
            resetHandler.removeCallbacks(resetRunnable)
            resetHandler.postDelayed(resetRunnable, 10000)

            val textToCopy = "Hex: ${hexValue.text}\nDecimal: ${decValue.text}"
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("RFID Data", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.toast_copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setControlsEnabled(isEnabled: Boolean) {
        shareButton.isEnabled = isEnabled
    }

    private fun resetUI() {
        // Clear all text fields
        hexValue.text = ""
        decValue.text = ""
        binValue.text = ""
        revHexValue.text = ""
        revDecValue.text = ""
        revBinValue.text = ""
        scoreValueText.text = ""
        nfcTagInfoTextView.text = ""

        setControlsEnabled(false)
        copyFab.hide()

        // Hide data cards and show the prompt card
        infoCard.animate().alpha(0f).setDuration(400).start()
        scoreCard.animate().alpha(0f).setDuration(400).start()
        promptCard.animate().alpha(1f).setDuration(400).start()
    }

    // --- Helper and Calculation Functions ---
    private fun showCongratsSnackbar() {
        Snackbar.make(mainLayout, getString(R.string.congrats_new_high_score), Snackbar.LENGTH_LONG).show()
    }

    private fun loadHighScore() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        highScore = prefs.getInt(PREF_KEY_HIGH_SCORE, 0)
        highScoreValueText.text = highScore.toString()
    }

    private fun saveHighScore(score: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putInt(PREF_KEY_HIGH_SCORE, score) }
    }

    private fun loadSavedBackground() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val uriString = prefs.getString(PREF_KEY_BACKGROUND_URI, null)
        if (uriString != null) {
            try {
                val uri = uriString.toUri()
                loadBackgroundFromUri(uri)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, getString(R.string.toast_failed_to_load_saved_background), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadBackgroundFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val drawable = Drawable.createFromStream(inputStream, uri.toString())
            mainLayout.background = drawable
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.toast_failed_to_load_image), Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseTagInfo(tag: Tag): String {
        val sb = StringBuilder()
        val techList = tag.techList.map { it.substringAfterLast('.') }
        sb.append("Technologies: ").append(techList.joinToString(", ")).append("\n")

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
                        sb.append("Type: ").append(type)
                    }
                }
                "NfcA" -> {
                    NfcA.get(tag)?.use { nfcA ->
                        sb.append("\nATQA: 0x").append(bytesToHexString(nfcA.atqa))
                        sb.append(" | SAK: 0x").append(Integer.toHexString(nfcA.sak.toInt()))
                    }
                }
            }
        }
        return sb.toString().trim()
    }

    private fun calculateScore(idBytes: ByteArray): Int {
        val paddedBytes = idBytes.copyOf(4)
        val intValue = ByteBuffer.wrap(paddedBytes).int
        val absValue = abs(intValue.toLong())
        val baseValue = absValue % 1000
        val normalizedValue = baseValue / 999.0
        val skewedValue = normalizedValue.pow(SCORING_EXPONENT)
        val finalValue = skewedValue * 999
        return finalValue.toInt() + 1
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
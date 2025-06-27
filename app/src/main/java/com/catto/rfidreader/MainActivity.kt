package com.catto.rfidreader

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
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
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NFCApp"
        private const val PREFS_NAME = "NfcAppPrefs"
        private const val PREF_KEY_HIGH_SCORE = "high_score"
        private const val SCORING_EXPONENT = 3.5
    }

    // UI View References
    private lateinit var mainLayout: androidx.constraintlayout.widget.ConstraintLayout
    private lateinit var cardContainer: LinearLayout
    private lateinit var scoreCard: MaterialCardView
    private lateinit var infoCard: MaterialCardView
    private lateinit var promptCard: MaterialCardView
    private lateinit var initialPromptCard: MaterialCardView
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

    // State and Logic Variables
    private var nfcAdapter: NfcAdapter? = null
    private var highScore = 0
    private var isGameifyEnabled = true
    private lateinit var database: ScannedCardDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize database from the Application class
        database = (application as App).database.scannedCardDao()

        // Initialize all views
        mainLayout = findViewById(R.id.main_layout)
        cardContainer = findViewById(R.id.card_container)
        promptCard = findViewById(R.id.prompt_card)
        initialPromptCard = findViewById(R.id.initial_prompt_card)
        scoreCard = findViewById(R.id.score_card)
        infoCard = findViewById(R.id.info_card)
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

        loadSettings()
        loadHighScore()
        setupButtonListeners()
        resetUI()
    }

    override fun onResume() {
        super.onResume()
        // Refresh UI in case settings were changed
        loadSettings()
        loadHighScore()
        loadSavedBackground()
        setupForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
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
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun handleNfcTag(intent: Intent) {
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        tag?.let {
            // --- PRIMARY LOGIC ---
            val littleEndianBytes = it.id
            val bigEndianBytes = littleEndianBytes.reversedArray()

            hexValue.text = bytesToHexString(bigEndianBytes)
            decValue.text = bytesToDecString(bigEndianBytes)
            binValue.text = bytesToBinString(bigEndianBytes)
            revHexValue.text = bytesToHexString(littleEndianBytes)
            revDecValue.text = bytesToDecString(littleEndianBytes)
            revBinValue.text = bytesToBinString(littleEndianBytes)

            val tagInfo = parseTagInfo(it)
            nfcTagInfoTextView.text = tagInfo

            var score = 0
            if (isGameifyEnabled) {
                score = calculateScore(bigEndianBytes)
                scoreValueText.text = score.toString()
                if (score > highScore) {
                    highScore = score
                    saveHighScore(highScore)
                    highScoreValueText.text = highScore.toString()
                    showCongratsSnackbar()
                }
                scoreCard.visibility = View.VISIBLE
            } else {
                scoreCard.visibility = View.GONE
            }

            // --- SAVE TO DB ---
            lifecycleScope.launch {
                database.insert(
                    ScannedCard(
                        serialNumberHex = hexValue.text.toString(),
                        decValue = decValue.text.toString(),
                        binValue = binValue.text.toString(),
                        revHexValue = revHexValue.text.toString(),
                        revDecValue = revDecValue.text.toString(),
                        revBinValue = revBinValue.text.toString(),
                        score = score,
                        tagInfo = tagInfo,
                        scanTimestamp = System.currentTimeMillis()
                    )
                )
            }

            // --- UPDATE UI ---
            initialPromptCard.visibility = View.GONE
            promptCard.visibility = View.VISIBLE // Show "Scan another card" prompt
            cardContainer.visibility = View.VISIBLE // Show card details

            setControlsEnabled(true)
            copyFab.show()
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

        cardContainer.visibility = View.GONE
        promptCard.visibility = View.GONE
        initialPromptCard.visibility = View.VISIBLE
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        isGameifyEnabled = prefs.getBoolean("pref_key_gameify", true)
    }

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
        val backgroundType = prefs.getString("pref_key_background_type", "COLOR")
        val backgroundValue = prefs.getString("pref_key_background_value", null)

        when (backgroundType) {
            "IMAGE" -> {
                if (backgroundValue != null) {
                    try {
                        val uri = backgroundValue.toUri()
                        loadBackgroundFromUri(uri)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this, getString(R.string.toast_failed_to_load_saved_background), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "COLOR" -> {
                val color = backgroundValue?.toIntOrNull() ?: Color.DKGRAY
                mainLayout.setBackgroundColor(color)
            }
            else -> {
                mainLayout.setBackgroundColor(Color.DKGRAY)
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

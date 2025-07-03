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
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.pow

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
    private lateinit var signatureView: SignatureView
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
    private lateinit var barcodeScannerButton: ImageButton
    private lateinit var historyButton: ImageButton
    private lateinit var questsButton: ImageButton
    private lateinit var battleButton: ImageButton
    private lateinit var converterButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var copyFab: FloatingActionButton

    // State and Logic Variables
    private var nfcAdapter: NfcAdapter? = null
    private var highScore = 0
    private var isGameifyEnabled = true
    private var hapticsEnabled = true
    private var visualSignaturesEnabled = true
    private lateinit var database: AppDatabase

    private val barcodeScannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val barcodeValue = result.data?.getStringExtra(BarcodeScannerActivity.EXTRA_BARCODE_VALUE)
            if (!barcodeValue.isNullOrEmpty()) {
                handleBarcode(barcodeValue)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize database from the Application class
        database = (application as App).database

        // Initialize all views
        mainLayout = findViewById(R.id.main_layout)
        cardContainer = findViewById(R.id.card_container)
        promptCard = findViewById(R.id.prompt_card)
        initialPromptCard = findViewById(R.id.initial_prompt_card)
        scoreCard = findViewById(R.id.score_card)
        infoCard = findViewById(R.id.info_card)
        signatureView = findViewById(R.id.signature_view)
        hexValue = findViewById(R.id.hex_value)
        decValue = findViewById(R.id.dec_value)
        binValue = findViewById(R.id.bin_value)
        revHexValue = findViewById(R.id.rev_hex_value)
        revDecValue = findViewById(R.id.rev_dec_value)
        revBinValue = findViewById(R.id.rev_bin_value)
        nfcTagInfoTextView = findViewById(R.id.nfc_tag_info)
        scoreValueText = findViewById(R.id.score_value_text)
        highScoreValueText = findViewById(R.id.high_score_value_text)
        barcodeScannerButton = findViewById(R.id.barcode_scanner_button)
        historyButton = findViewById(R.id.history_button)
        questsButton = findViewById(R.id.quests_button)
        battleButton = findViewById(R.id.battle_button)
        converterButton = findViewById(R.id.converter_button)
        settingsButton = findViewById(R.id.settings_button)
        copyFab = findViewById(R.id.fab_copy)

        setupButtonListeners()
        resetUI()
    }

    override fun onResume() {
        super.onResume()
        // Refresh UI in case settings were changed
        loadAndApplySettings()
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

    private fun handleBarcode(barcodeValue: String) {
        try {
            // Treat the barcode string as a decimal number
            val bigIntValue = BigInteger(barcodeValue)

            // Convert the BigInteger to a byte array (big-endian)
            val bigEndianBytes = bigIntValue.toByteArray().let {
                if (it.isNotEmpty() && it[0] == 0.toByte()) it.sliceArray(1 until it.size) else it
            }
            val littleEndianBytes = bigEndianBytes.reversedArray()

            val tagInfo = "BARCODE"
            val battleStats = BattleManager.generateStats(bigEndianBytes, tagInfo)
            val score = calculateScore(bigEndianBytes)

            // Create the ScannedCard with all numeric conversions
            val newCard = ScannedCard(
                serialNumberHex = bytesToHexString(bigEndianBytes),
                decValue = bytesToDecString(bigEndianBytes),
                binValue = bytesToBinString(bigEndianBytes),
                revHexValue = bytesToHexString(littleEndianBytes),
                revDecValue = bytesToDecString(littleEndianBytes),
                revBinValue = bytesToBinString(littleEndianBytes),
                score = score,
                tagInfo = tagInfo,
                battleStats = battleStats,
                scanTimestamp = System.currentTimeMillis()
            )

            updateUiWithCard(newCard, bigEndianBytes)
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Barcode value is not a valid number: $barcodeValue", e)
            Toast.makeText(this, "Scanned barcode is not a number: $barcodeValue", Toast.LENGTH_LONG).show()
        }
    }


    private fun handleNfcTag(intent: Intent) {
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        tag?.let {
            val littleEndianBytes = it.id
            val bigEndianBytes = littleEndianBytes.reversedArray()
            val tagInfo = parseTagInfo(it)
            val battleStats = BattleManager.generateStats(it.id, tagInfo)

            val newCard = ScannedCard(
                serialNumberHex = bytesToHexString(bigEndianBytes),
                decValue = bytesToDecString(bigEndianBytes),
                binValue = bytesToBinString(bigEndianBytes),
                revHexValue = bytesToHexString(littleEndianBytes),
                revDecValue = bytesToDecString(littleEndianBytes),
                revBinValue = bytesToBinString(littleEndianBytes),
                score = calculateScore(bigEndianBytes),
                tagInfo = tagInfo,
                battleStats = battleStats,
                scanTimestamp = System.currentTimeMillis()
            )

            updateUiWithCard(newCard, it.id)
        }
    }

    private fun updateUiWithCard(card: ScannedCard, idBytes: ByteArray) {
        if (visualSignaturesEnabled) {
            signatureView.setCardId(idBytes)
            signatureView.visibility = View.VISIBLE
        } else {
            signatureView.visibility = View.GONE
        }

        hexValue.text = card.serialNumberHex
        decValue.text = card.decValue
        binValue.text = card.binValue
        revHexValue.text = card.revHexValue
        revDecValue.text = card.revDecValue
        revBinValue.text = card.revBinValue
        nfcTagInfoTextView.text = card.tagInfo
        scoreValueText.text = card.score.toString()

        if (isGameifyEnabled) {
            if (card.score > highScore) {
                highScore = card.score
                saveHighScore(highScore)
                highScoreValueText.text = highScore.toString()
                showCongratsSnackbar()
            }
            scoreCard.visibility = View.VISIBLE
        } else {
            scoreCard.visibility = View.GONE
        }

        lifecycleScope.launch {
            database.scannedCardDao().insert(card)
            val allCards = database.scannedCardDao().getAllCardsList()
            QuestManager.checkQuests(mainLayout, this@MainActivity, card, allCards)
        }

        initialPromptCard.visibility = View.GONE
        promptCard.visibility = View.VISIBLE
        cardContainer.visibility = View.VISIBLE

        setControlsEnabled()
        copyFab.show()
    }


    private fun setupButtonListeners() {
        barcodeScannerButton.setOnClickListener {
            val intent = Intent(this, BarcodeScannerActivity::class.java)
            barcodeScannerLauncher.launch(intent)
        }

        historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        questsButton.setOnClickListener {
            startActivity(Intent(this, QuestsActivity::class.java))
        }

        battleButton.setOnClickListener {
            startActivity(Intent(this, BattleArenaActivity::class.java))
        }

        converterButton.setOnClickListener {
            startActivity(Intent(this, ConverterActivity::class.java))
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        copyFab.setOnClickListener {
            val textToCopy = "Hex: ${hexValue.text}\nDecimal: ${decValue.text}"
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("RFID Data", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, getString(R.string.toast_copied_to_clipboard), Toast.LENGTH_SHORT).show()
            if (hapticsEnabled) {
                performHapticFeedback()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun performHapticFeedback() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(50)
        }
    }

    private fun setControlsEnabled() {
        // This function is currently not used for any purpose.
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
        signatureView.setCardId(null)
        signatureView.visibility = View.GONE

        setControlsEnabled()
        copyFab.hide()

        cardContainer.visibility = View.GONE
        promptCard.visibility = View.GONE
        initialPromptCard.visibility = View.VISIBLE
    }

    private fun loadAndApplySettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        isGameifyEnabled = prefs.getBoolean("pref_key_gameify", true)
        hapticsEnabled = prefs.getBoolean("pref_key_haptic_feedback", true)
        visualSignaturesEnabled = prefs.getBoolean("pref_key_visual_signature", true)
        highScore = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_KEY_HIGH_SCORE, 0)
        highScoreValueText.text = highScore.toString()

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
        valueTextViews.forEach { it.textSize = valueSize }

        val labelTextViews = listOf<TextView>(
            findViewById(R.id.hex_label), findViewById(R.id.dec_label), findViewById(R.id.bin_label),
            findViewById(R.id.rev_hex_label), findViewById(R.id.rev_dec_label), findViewById(R.id.rev_bin_label),
            findViewById(R.id.score_label), findViewById(R.id.high_score_label)
        )
        labelTextViews.forEach { it.textSize = labelSize }

        nfcTagInfoTextView.textSize = labelSize
        highScoreValueText.textSize = labelSize
        scoreValueText.textSize = 48f // Keep score text large
    }


    private fun showCongratsSnackbar() {
        Snackbar.make(mainLayout, getString(R.string.congrats_new_high_score), Snackbar.LENGTH_LONG).show()
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
        if (idBytes.isEmpty()) return 0
        val paddedBytes = if (idBytes.size < 4) idBytes + ByteArray(4 - idBytes.size) else idBytes.copyOf(4)
        val intValue = java.nio.ByteBuffer.wrap(paddedBytes).int
        val absValue = abs(intValue.toLong())
        val baseValue = absValue % 1000
        val normalizedValue = baseValue / 999.0
        val skewedValue = normalizedValue.pow(SCORING_EXPONENT)
        val finalValue = skewedValue * 999
        return finalValue.toInt() + 1
    }
}

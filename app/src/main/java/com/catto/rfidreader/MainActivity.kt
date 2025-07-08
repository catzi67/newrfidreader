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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.catto.rfidreader.databinding.ActivityMainBinding
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

    // Use View Binding to safely access views
    private lateinit var binding: ActivityMainBinding

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
        // Inflate the layout using View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = (application as App).database

        setupButtonListeners()
        resetUI()
    }

    override fun onResume() {
        super.onResume()
        loadAndApplySettings()
        setupForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun handleBarcode(barcodeValue: String) {
        try {
            val bigIntValue = BigInteger(barcodeValue)
            val bigEndianBytes = bigIntValue.toByteArray().let {
                if (it.isNotEmpty() && it[0] == 0.toByte()) it.sliceArray(1 until it.size) else it
            }
            val battleStats = BattleManager.generateStats(bigEndianBytes)
            val score = calculateScore(bigEndianBytes)

            val newCard = ScannedCard(
                serialNumberHex = bytesToHexString(bigEndianBytes),
                decValue = bytesToDecString(bigEndianBytes),
                binValue = bytesToBinString(bigEndianBytes),
                revHexValue = bytesToHexString(bigEndianBytes.reversedArray()),
                revDecValue = bytesToDecString(bigEndianBytes.reversedArray()),
                revBinValue = bytesToBinString(bigEndianBytes.reversedArray()),
                score = score,
                tagInfo = "BARCODE",
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
            val bigEndianBytes = it.id.reversedArray()
            val battleStats = BattleManager.generateStats(it.id)

            val newCard = ScannedCard(
                serialNumberHex = bytesToHexString(bigEndianBytes),
                decValue = bytesToDecString(bigEndianBytes),
                binValue = bytesToBinString(bigEndianBytes),
                revHexValue = bytesToHexString(it.id),
                revDecValue = bytesToDecString(it.id),
                revBinValue = bytesToBinString(it.id),
                score = calculateScore(bigEndianBytes),
                tagInfo = parseTagInfo(it),
                battleStats = battleStats,
                scanTimestamp = System.currentTimeMillis()
            )
            updateUiWithCard(newCard, it.id)
        }
    }

    private fun updateUiWithCard(card: ScannedCard, idBytes: ByteArray) {
        binding.signatureView.visibility = if (visualSignaturesEnabled) {
            binding.signatureView.setCardId(idBytes)
            View.VISIBLE
        } else {
            View.GONE
        }

        binding.hexValue.text = card.serialNumberHex
        binding.decValue.text = card.decValue
        binding.binValue.text = card.binValue
        binding.revHexValue.text = card.revHexValue
        binding.revDecValue.text = card.revDecValue
        binding.revBinValue.text = card.revBinValue
        binding.nfcTagInfo.text = card.tagInfo
        binding.scoreValueText.text = card.score.toString()

        if (isGameifyEnabled) {
            if (card.score > highScore) {
                highScore = card.score
                saveHighScore(highScore)
                binding.highScoreValueText.text = highScore.toString()
                showCongratsSnackbar()
            }
            binding.scoreCard.visibility = View.VISIBLE
        } else {
            binding.scoreCard.visibility = View.GONE
        }

        lifecycleScope.launch {
            database.scannedCardDao().upsert(card)
            val allCards = database.scannedCardDao().getAllCardsList()
            QuestManager.checkQuests(binding.mainLayout, this@MainActivity, card, allCards)
        }

        binding.initialPromptCard.visibility = View.GONE
        binding.promptCard.visibility = View.VISIBLE
        binding.cardContainer.visibility = View.VISIBLE
        binding.fabCopy.show()
    }

    private fun setupButtonListeners() {
        binding.barcodeScannerButton.setOnClickListener {
            val intent = Intent(this, BarcodeScannerActivity::class.java)
            barcodeScannerLauncher.launch(intent)
        }
        binding.collectionButton.setOnClickListener {
            startActivity(Intent(this, CollectionActivity::class.java))
        }
        binding.questsButton.setOnClickListener {
            startActivity(Intent(this, QuestsActivity::class.java))
        }
        binding.battleButton.setOnClickListener {
            startActivity(Intent(this, BattleArenaActivity::class.java))
        }
        binding.converterButton.setOnClickListener {
            startActivity(Intent(this, ConverterActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.fabCopy.setOnClickListener {
            val textToCopy = "Hex: ${binding.hexValue.text}\nDecimal: ${binding.decValue.text}"
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

    private fun resetUI() {
        binding.hexValue.text = ""
        binding.decValue.text = ""
        binding.binValue.text = ""
        binding.revHexValue.text = ""
        binding.revDecValue.text = ""
        binding.revBinValue.text = ""
        binding.scoreValueText.text = ""
        binding.nfcTagInfo.text = ""
        binding.signatureView.setCardId(null)
        binding.signatureView.visibility = View.GONE
        binding.fabCopy.hide()
        binding.cardContainer.visibility = View.GONE
        binding.promptCard.visibility = View.GONE
        binding.initialPromptCard.visibility = View.VISIBLE
    }

    private fun loadAndApplySettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        isGameifyEnabled = prefs.getBoolean("pref_key_gameify", true)
        hapticsEnabled = prefs.getBoolean("pref_key_haptic_feedback", true)
        visualSignaturesEnabled = prefs.getBoolean("pref_key_visual_signature", true)
        highScore = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_KEY_HIGH_SCORE, 0)
        binding.highScoreValueText.text = highScore.toString()
        loadSavedBackground()
        applyTextSize()
    }

    private fun applyTextSize() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val textSizePref = prefs.getString("pref_key_text_size", "small")
        val (labelSize, valueSize) = when (textSizePref) {
            "medium" -> 16f to 18f
            "large" -> 18f to 20f
            else -> 14f to 16f
        }
        val valueTextViews = with(binding) {
            listOf(hexValue, decValue, binValue, revHexValue, revDecValue, revBinValue)
        }
        valueTextViews.forEach { it.textSize = valueSize }
        val labelTextViews = with(binding) {
            listOf(hexLabel, decLabel, binLabel, revHexLabel, revDecLabel, revBinLabel, scoreLabel, highScoreLabel)
        }
        labelTextViews.forEach { it.textSize = labelSize }
        binding.nfcTagInfo.textSize = labelSize
        binding.highScoreValueText.textSize = labelSize
        binding.scoreValueText.textSize = 48f
    }

    private fun showCongratsSnackbar() {
        Snackbar.make(binding.mainLayout, getString(R.string.congrats_new_high_score), Snackbar.LENGTH_LONG).show()
    }

    private fun saveHighScore(score: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit { putInt(PREF_KEY_HIGH_SCORE, score) }
    }

    private fun loadSavedBackground() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val backgroundType = prefs.getString("pref_key_background_type", "COLOR")
        val backgroundValue = prefs.getString("pref_key_background_value", null)
        when (backgroundType) {
            "IMAGE" -> {
                if (backgroundValue != null) {
                    try {
                        loadBackgroundFromUri(backgroundValue.toUri())
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this, getString(R.string.toast_failed_to_load_saved_background), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "COLOR" -> {
                val color = backgroundValue?.toIntOrNull() ?: Color.DKGRAY
                binding.mainLayout.setBackgroundColor(color)
            }
            else -> binding.mainLayout.setBackgroundColor(Color.DKGRAY)
        }
    }

    private fun loadBackgroundFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val drawable = Drawable.createFromStream(inputStream, uri.toString())
            binding.mainLayout.background = drawable
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

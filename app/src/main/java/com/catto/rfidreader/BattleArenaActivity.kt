package com.catto.rfidreader

import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.catto.rfidreader.databinding.ActivityBattleArenaBinding
import com.catto.rfidreader.databinding.ViewFighterCardBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BattleArenaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBattleArenaBinding
    private var player1Card: ScannedCard? = null
    private var player2Card: ScannedCard? = null

    private val dao by lazy { (application as App).database.scannedCardDao() }

    private val selectPlayer1Launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val cardId = result.data?.getIntExtra(SelectCardActivity.EXTRA_SELECTED_CARD_ID, -1) ?: -1
            if (cardId != -1) {
                lifecycleScope.launch {
                    player1Card = dao.getCardById(cardId)
                    updateFighterView(binding.player1Card, player1Card)
                }
            }
        }
    }

    private val selectPlayer2Launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val cardId = result.data?.getIntExtra(SelectCardActivity.EXTRA_SELECTED_CARD_ID, -1) ?: -1
            if (cardId != -1) {
                lifecycleScope.launch {
                    player2Card = dao.getCardById(cardId)
                    updateFighterView(binding.player2Card, player2Card)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // This enables the edge-to-edge display.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        binding = ActivityBattleArenaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // This listener applies padding to the root view to prevent it from
        // overlapping with the system bars (status bar, navigation bar).
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        setSupportActionBar(binding.battleArenaToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.player1Card.selectFighterButton.setOnClickListener {
            selectPlayer1Launcher.launch(Intent(this, SelectCardActivity::class.java))
        }

        binding.player2Card.selectFighterButton.setOnClickListener {
            selectPlayer2Launcher.launch(Intent(this, SelectCardActivity::class.java))
        }

        binding.startBattleButton.setOnClickListener {
            startBattle()
        }

        binding.battleLogText.movementMethod = ScrollingMovementMethod()

        updateFighterView(binding.player1Card, null)
        updateFighterView(binding.player2Card, null)
    }

    private fun updateFighterView(fighterBinding: ViewFighterCardBinding, card: ScannedCard?, currentHp: Int? = null) {
        if (card != null) {
            fighterBinding.fighterName.text = card.name ?: "Card #${card.id}"
            fighterBinding.fighterSignature.setCardId(card.serialNumberHex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            card.battleStats?.let {
                val hp = currentHp ?: it.hp
                fighterBinding.fighterStats.text = "HP: $hp | ATK: ${it.attack} | DEF: ${it.defense}"
                fighterBinding.fighterStats.visibility = View.VISIBLE
            }
        } else {
            fighterBinding.fighterName.text = "Select Fighter"
            fighterBinding.fighterSignature.setCardId(null)
            fighterBinding.fighterStats.visibility = View.GONE
        }
    }

    private fun startBattle() {
        val p1 = player1Card
        val p2 = player2Card

        if (p1?.battleStats == null || p2?.battleStats == null) {
            Toast.makeText(this, "Please select two cards to battle.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.startBattleButton.isEnabled = false
        binding.player1Card.selectFighterButton.isEnabled = false
        binding.player2Card.selectFighterButton.isEnabled = false

        binding.battleLogText.text = "" // Clear the log
        log("The battle begins!")

        lifecycleScope.launch {
            val fighter1 = p1.battleStats.copy()
            var hp1 = fighter1.hp

            val fighter2 = p2.battleStats.copy()
            var hp2 = fighter2.hp

            // Reset the HP display at the start of the battle
            updateFighterView(binding.player1Card, p1, hp1)
            updateFighterView(binding.player2Card, p2, hp2)

            var isPlayer1Turn = fighter1.speed >= fighter2.speed

            while (hp1 > 0 && hp2 > 0) {
                delay(1200) // Pause between turns for dramatic effect

                if (isPlayer1Turn) {
                    val damage = BattleManager.calculateDamage(fighter1, fighter2)
                    hp2 -= damage
                    log("${p1.name ?: "Card #${p1.id}"} attacks, dealing $damage damage!")
                    updateFighterView(binding.player2Card, p2, hp2)
                } else {
                    val damage = BattleManager.calculateDamage(fighter2, fighter1)
                    hp1 -= damage
                    log("${p2.name ?: "Card #${p2.id}"} attacks, dealing $damage damage!")
                    updateFighterView(binding.player1Card, p1, hp1)
                }

                isPlayer1Turn = !isPlayer1Turn
            }

            delay(1000)
            val winnerName = if (hp1 > 0) p1.name ?: "Card #${p1.id}" else p2.name ?: "Card #${p2.id}"
            log("\n--- The winner is $winnerName! ---")

            binding.startBattleButton.isEnabled = true
            binding.player1Card.selectFighterButton.isEnabled = true
            binding.player2Card.selectFighterButton.isEnabled = true
        }
    }

    private fun log(message: String) {
        binding.battleLogText.append("\n> $message")
        // Auto-scroll to the bottom
        binding.battleLogScroll.post { binding.battleLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

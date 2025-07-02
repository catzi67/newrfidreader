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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        binding = ActivityBattleArenaBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            fighterBinding.fighterName.text = card.name ?: getString(R.string.card_id_placeholder, card.id)
            fighterBinding.fighterSignature.setCardId(card.serialNumberHex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            card.battleStats?.let {
                val hp = currentHp ?: it.hp
                fighterBinding.fighterStats.text = getString(R.string.battle_stats_full_format, hp, it.attack, it.defense, it.speed, it.luck)
                fighterBinding.fighterStats.visibility = View.VISIBLE
            }
        } else {
            fighterBinding.fighterName.text = getString(R.string.select_fighter)
            fighterBinding.fighterSignature.setCardId(null)
            fighterBinding.fighterStats.visibility = View.GONE
        }
    }

    private fun startBattle() {
        val p1 = player1Card
        val p2 = player2Card
        val p1Stats = p1?.battleStats
        val p2Stats = p2?.battleStats

        if (p1 == null || p2 == null || p1Stats == null || p2Stats == null) {
            Toast.makeText(this, "Please select two cards to battle.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.startBattleButton.isEnabled = false
        binding.player1Card.selectFighterButton.isEnabled = false
        binding.player2Card.selectFighterButton.isEnabled = false

        binding.battleLogText.text = "" // Clear the log
        log("The battle begins!")

        lifecycleScope.launch {
            var hp1 = p1Stats.hp
            var hp2 = p2Stats.hp

            updateFighterView(binding.player1Card, p1, hp1)
            updateFighterView(binding.player2Card, p2, hp2)

            var isPlayer1Turn = p1Stats.speed >= p2Stats.speed

            while (hp1 > 0 && hp2 > 0) {
                delay(1500) // Pause between turns for dramatic effect

                val attackerCard = if (isPlayer1Turn) p1 else p2
                val attackerStats = if (isPlayer1Turn) p1Stats else p2Stats
                val defenderStats = if (isPlayer1Turn) p2Stats else p1Stats
                val attackerName = attackerCard.name ?: getString(R.string.card_id_placeholder, attackerCard.id)

                val result = BattleManager.resolveAttack(attackerStats, defenderStats)
                val turnLog = mutableListOf<String>()

                if (result.isMiss) {
                    turnLog.add(getString(R.string.battle_log_attack_miss, attackerName))
                } else {
                    turnLog.add(getString(R.string.battle_log_attack_deals_damage, attackerName, result.damage))
                    if (result.isCritical) turnLog.add(getString(R.string.battle_log_critical_hit))
                    if (result.isBlocked) turnLog.add(getString(R.string.battle_log_blocked))

                    if (isPlayer1Turn) {
                        hp2 -= result.damage
                    } else {
                        hp1 -= result.damage
                    }

                    if (result.didCounter && (hp1 > 0 && hp2 > 0)) {
                        delay(700)
                        val defenderName = if(isPlayer1Turn) (p2.name ?: getString(R.string.card_id_placeholder, p2.id)) else (p1.name ?: getString(R.string.card_id_placeholder, p1.id))
                        turnLog.add(getString(R.string.battle_log_counter_attack, defenderName, result.counterDamage))
                        if (isPlayer1Turn) {
                            hp1 -= result.counterDamage
                        } else {
                            hp2 -= result.counterDamage
                        }
                    }
                }

                log(turnLog.joinToString(" "))
                updateFighterView(binding.player1Card, p1, hp1)
                updateFighterView(binding.player2Card, p2, hp2)

                isPlayer1Turn = !isPlayer1Turn
            }

            delay(1000)
            val winner = if (hp1 > 0) p1 else p2
            val winnerName = winner.name ?: getString(R.string.card_id_placeholder, winner.id)
            log(getString(R.string.battle_log_winner, winnerName))

            binding.startBattleButton.isEnabled = true
            binding.player1Card.selectFighterButton.isEnabled = true
            binding.player2Card.selectFighterButton.isEnabled = true
        }
    }

    private fun log(message: String) {
        binding.battleLogText.append("\n> $message")
        binding.battleLogScroll.post { binding.battleLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

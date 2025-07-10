package com.catto.rfidreader

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.catto.rfidreader.databinding.ActivityBattleArenaBinding
import com.catto.rfidreader.databinding.ViewFighterCardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

private const val TAG = "BattleArenaActivity"

@SuppressLint("MissingPermission")
class BattleArenaActivity : AppCompatActivity(), WifiP2pManager.ConnectionInfoListener {

    // --- Enums for State Management ---
    private enum class BattleMode { NONE, LOCAL, P2P }
    private enum class P2pUiState { IDLE, MATCHMAKING, BATTLE }
    private enum class P2pMatchmakingState { IDLE, SEEKING, WAITING_AS_HOST }

    // Data class for sending information between P2P devices
    private data class P2pMessage(val card: ScannedCard, val seed: Long? = null)

    private lateinit var binding: ActivityBattleArenaBinding
    private val dao by lazy { (application as App).database.scannedCardDao() }

    // --- State Variables ---
    private var battleMode = BattleMode.NONE
    private var player1Card: ScannedCard? = null
    private var player2Card: ScannedCard? = null
    private var battleSeed: Long = 0L // Seed for synchronized random events in P2P
    private var isP2pInitialized = false
    private var isP2pConnected = false // Explicitly track connection state
    private var matchmakingState = P2pMatchmakingState.IDLE
    private var matchmakingJob: Job? = null
    private var thisDevice: WifiP2pDevice? = null

    // --- P2P and System Service Variables ---
    private val wifiP2pManager: WifiP2pManager by lazy { getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private val wifiManager: WifiManager by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val locationManager: LocationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private lateinit var intentFilter: IntentFilter
    private var serverThread: ServerThread? = null
    private var clientThread: ClientThread? = null
    private var isHost = false

    // --- Activity Result Launchers ---
    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] != true ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && permissions[Manifest.permission.NEARBY_WIFI_DEVICES] != true)) {
            Toast.makeText(this, getString(R.string.p2p_permission_required), Toast.LENGTH_LONG).show()
            setUiState(P2pUiState.IDLE)
        } else {
            if (checkP2pPrerequisites()) {
                startP2pMode()
            }
        }
    }

    private val selectPlayer1Launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val cardId = result.data?.getIntExtra(CollectionActivity.EXTRA_SELECTED_CARD_ID, -1) ?: -1
            if (cardId != -1) {
                lifecycleScope.launch {
                    player1Card = dao.getCardById(cardId)
                    updateFighterView(binding.player1Card, player1Card)
                    checkBattleReady()
                }
            }
        }
    }

    private val selectPlayer2Launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val cardId = result.data?.getIntExtra(CollectionActivity.EXTRA_SELECTED_CARD_ID, -1) ?: -1
            if (cardId != -1) {
                lifecycleScope.launch {
                    player2Card = dao.getCardById(cardId)
                    updateFighterView(binding.player2Card, player2Card)
                    checkBattleReady()
                }
            }
        }
    }

    // --- Activity Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        binding = ActivityBattleArenaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindow()
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        if (isP2pInitialized) {
            receiver?.let { registerReceiver(it, intentFilter) }
        }
    }

    override fun onPause() {
        super.onPause()
        if (isP2pInitialized) {
            receiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Receiver not registered", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectAndCleanup()
    }

    override fun onSupportNavigateUp(): Boolean {
        disconnectAndCleanup()
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // --- UI Setup and Management ---
    private fun setupWindow() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }
        setSupportActionBar(binding.battleArenaToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupUI() {
        binding.battleLogText.movementMethod = ScrollingMovementMethod()
        setUiState(P2pUiState.IDLE)

        binding.localBattleButton.setOnClickListener {
            battleMode = BattleMode.LOCAL
            disconnectAndCleanup()
            setUiState(P2pUiState.BATTLE)
        }
        binding.findP2pBattleButton.setOnClickListener {
            battleMode = BattleMode.P2P
            checkAndRequestPermissions()
        }

        binding.player1Card.selectFighterButton.setOnClickListener { launchCardSelection(selectPlayer1Launcher) }
        binding.player2Card.selectFighterButton.setOnClickListener { launchCardSelection(selectPlayer2Launcher) }

        binding.resetP2pButton.setOnClickListener {
            Toast.makeText(this, getString(R.string.toast_returning_to_menu), Toast.LENGTH_SHORT).show()
            disconnectAndCleanup()
            setUiState(P2pUiState.IDLE)
        }
    }

    private fun setUiState(state: P2pUiState) {
        binding.modeSelectionContainer.isVisible = state == P2pUiState.IDLE
        binding.fightersContainer.isVisible = state == P2pUiState.BATTLE
        binding.battleLogScroll.isVisible = state == P2pUiState.BATTLE || state == P2pUiState.MATCHMAKING
        binding.resetP2pButton.isVisible = state == P2pUiState.MATCHMAKING || (state == P2pUiState.BATTLE && battleMode == BattleMode.P2P)


        when (state) {
            P2pUiState.IDLE -> {
                binding.battleArenaToolbar.title = getString(R.string.title_activity_battle_setup)
                binding.battleArenaToolbar.subtitle = null
                player1Card = null
                player2Card = null
                updateFighterView(binding.player1Card, null)
                updateFighterView(binding.player2Card, null)
            }
            P2pUiState.MATCHMAKING -> {
                binding.battleArenaToolbar.title = getString(R.string.title_activity_p2p_battle)
                binding.battleLogText.text = ""
                when (matchmakingState) {
                    P2pMatchmakingState.SEEKING -> binding.battleArenaToolbar.subtitle = getString(R.string.p2p_status_seeking)
                    P2pMatchmakingState.WAITING_AS_HOST -> binding.battleArenaToolbar.subtitle = getString(R.string.p2p_status_waiting_as_host)
                    else -> {}
                }
            }
            P2pUiState.BATTLE -> {
                binding.battleArenaToolbar.title = getString(R.string.title_activity_battle_arena)
                binding.battleArenaToolbar.subtitle = null
                updateFighterView(binding.player1Card, player1Card)
                updateFighterView(binding.player2Card, player2Card)
                binding.player2Card.selectFighterButton.isVisible = battleMode == BattleMode.LOCAL

                // Set the correct placeholder text based on the battle mode
                val placeholder = if (battleMode == BattleMode.LOCAL) {
                    getString(R.string.battle_log_placeholder)
                } else {
                    getString(R.string.p2p_prompt_select_fighter)
                }
                binding.battleLogText.text = if (player1Card != null && (player2Card != null || battleMode == BattleMode.P2P)) "" else placeholder
            }
        }
    }


    private fun updateFighterView(fighterBinding: ViewFighterCardBinding, card: ScannedCard?, currentHp: Int? = null) {
        if (card != null) {
            fighterBinding.fighterName.text = card.name ?: getString(R.string.card_id_placeholder, card.id)
            fighterBinding.fighterSignature.setCardId(hexStringToByteArray(card.serialNumberHex))
            card.battleStats?.let {
                val hp = currentHp ?: it.hp
                fighterBinding.fighterStats.text = getString(R.string.battle_stats_full_format, hp, it.attack, it.defense, it.speed, it.luck)
                fighterBinding.fighterStats.visibility = View.VISIBLE
            }
            fighterBinding.selectFighterButton.visibility = View.GONE
        } else {
            fighterBinding.fighterName.text = getString(R.string.select_fighter)
            fighterBinding.fighterSignature.setCardId(null)
            fighterBinding.fighterStats.visibility = View.GONE
            fighterBinding.selectFighterButton.visibility = View.VISIBLE
            fighterBinding.selectFighterButton.isEnabled = true
        }
    }

    // --- Battle Logic ---
    private fun checkBattleReady() {
        if (player1Card == null) return

        when (battleMode) {
            BattleMode.LOCAL -> {
                if (player2Card != null) startBattle()
            }
            BattleMode.P2P -> {
                startMatchmaking()
            }
            else -> {}
        }
    }


    private fun startBattle() {
        binding.player1Card.selectFighterButton.isEnabled = false
        binding.player2Card.selectFighterButton.isEnabled = false
        binding.battleLogText.text = ""
        log(getString(R.string.battle_log_start))

        val p1 = player1Card!!
        val p2 = player2Card!!
        val p1Stats = p1.battleStats!!
        val p2Stats = p2.battleStats!!
        val seed = if (battleMode == BattleMode.LOCAL) System.currentTimeMillis() else this.battleSeed
        val random = Random(seed)

        lifecycleScope.launch {
            var hp1 = p1Stats.hp
            var hp2 = p2Stats.hp
            updateFighterView(binding.player1Card, p1, hp1)
            updateFighterView(binding.player2Card, p2, hp2)
            var isPlayer1Turn = p1Stats.speed >= p2Stats.speed

            while (hp1 > 0 && hp2 > 0) {
                delay(1500)
                val (attackerCard, attackerStats, defenderStats) = if (isPlayer1Turn) Triple(p1, p1Stats, p2Stats) else Triple(p2, p2Stats, p1Stats)
                val attackerName = attackerCard.name ?: getString(R.string.card_id_placeholder, attackerCard.id)
                val result = BattleManager.resolveAttack(attackerStats, defenderStats, random)
                val turnLog = mutableListOf<String>()

                if (result.isMiss) {
                    turnLog.add(getString(R.string.battle_log_attack_miss, attackerName))
                } else {
                    turnLog.add(getString(R.string.battle_log_attack_deals_damage, attackerName, result.damage))
                    if (result.isSuperEffective) turnLog.add(getString(R.string.battle_log_super_effective))
                    if (result.isNotVeryEffective) turnLog.add(getString(R.string.battle_log_not_effective))
                    if (result.isCritical) turnLog.add(getString(R.string.battle_log_critical_hit))
                    if (result.isBlocked) turnLog.add(getString(R.string.battle_log_blocked))
                    if (isPlayer1Turn) hp2 -= result.damage else hp1 -= result.damage

                    if (result.didCounter && hp1 > 0 && hp2 > 0) {
                        delay(700)
                        val defenderName = if(isPlayer1Turn) (p2.name ?: "Card #${p2.id}") else (p1.name ?: "Card #${p1.id}")
                        turnLog.add(getString(R.string.battle_log_counter_attack, defenderName, result.counterDamage))
                        if (isPlayer1Turn) hp1 -= result.counterDamage else hp2 -= result.counterDamage
                    }
                }
                log(turnLog.joinToString(" "))
                updateFighterView(binding.player1Card, p1, hp1)
                updateFighterView(binding.player2Card, p2, hp2)
                isPlayer1Turn = !isPlayer1Turn
            }
            delay(1000)
            val (winner, loser) = if (hp1 > 0) (p1 to p2) else (p2 to p1)
            log(getString(R.string.battle_log_winner, winner.name ?: "Card #${winner.id}"))
            updateCardStatsAfterBattle(winner, loser)

            if (battleMode == BattleMode.P2P) {
                log(getString(R.string.battle_log_p2p_finished))
                disconnectAndCleanup()
                binding.resetP2pButton.text = getString(R.string.button_back_to_menu)
                binding.resetP2pButton.isVisible = true
            } else {
                log(getString(R.string.battle_log_local_finished))
                binding.player1Card.selectFighterButton.isEnabled = true
                binding.player2Card.selectFighterButton.isEnabled = true
            }
        }
    }

    private suspend fun updateCardStatsAfterBattle(winner: ScannedCard, loser: ScannedCard) {
        val localPlayerCard = player1Card ?: return

        if (battleMode == BattleMode.LOCAL) {
            val dbWinner = dao.getCardBySerialNumber(winner.serialNumberHex)
            val dbLoser = dao.getCardBySerialNumber(loser.serialNumberHex)
            if (dbWinner != null && dbLoser != null) {
                dbWinner.wins++
                dbLoser.losses++
                val (newWinnerRating, newLoserRating) = calculateEloRating(dbWinner.eloRating, dbLoser.eloRating)
                dbWinner.eloRating = newWinnerRating
                dbLoser.eloRating = newLoserRating
                dao.update(dbWinner)
                dao.update(dbLoser)
            }
        } else {
            val localIsWinner = winner.serialNumberHex == localPlayerCard.serialNumberHex
            val localIsLoser = loser.serialNumberHex == localPlayerCard.serialNumberHex

            if (localIsWinner) {
                val dbWinner = dao.getCardBySerialNumber(localPlayerCard.serialNumberHex)
                if (dbWinner != null) {
                    dbWinner.wins++
                    val (newWinnerRating, _) = calculateEloRating(dbWinner.eloRating, loser.eloRating)
                    dbWinner.eloRating = newWinnerRating
                    dao.update(dbWinner)
                }
            } else if (localIsLoser) {
                val dbLoser = dao.getCardBySerialNumber(localPlayerCard.serialNumberHex)
                if (dbLoser != null) {
                    dbLoser.losses++
                    val (_, newLoserRating) = calculateEloRating(winner.eloRating, dbLoser.eloRating)
                    dbLoser.eloRating = newLoserRating
                    dao.update(dbLoser)
                }
            }
        }
    }


    private fun calculateEloRating(winnerRating: Int, loserRating: Int, kFactor: Int = 32): Pair<Int, Int> {
        val expectedWinner = 1.0 / (1.0 + 10.0.pow((loserRating - winnerRating) / 400.0))
        val newWinnerRating = winnerRating + kFactor * (1 - expectedWinner)
        val newLoserRating = loserRating + kFactor * (0 - expectedWinner)
        return Pair(newWinnerRating.roundToInt(), newLoserRating.roundToInt())
    }

    private fun log(message: String) {
        binding.battleLogText.append("\n> $message")
        binding.battleLogScroll.post { binding.battleLogScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // --- P2P Methods ---
    private fun checkP2pPrerequisites(): Boolean {
        val isWifiEnabled = wifiManager.isWifiEnabled
        val isLocationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            true
        }

        if (!isWifiEnabled || !isLocationEnabled) {
            val messageBuilder = StringBuilder(getString(R.string.p2p_requirements_prompt))
            if (!isWifiEnabled) messageBuilder.append(getString(R.string.p2p_requirements_wifi))
            if (!isLocationEnabled) messageBuilder.append(getString(R.string.p2p_requirements_location))

            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_title_p2p_requirements))
                .setMessage(messageBuilder.toString())
                .setPositiveButton(getString(R.string.button_go_to_settings)) { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                    setUiState(P2pUiState.IDLE)
                }
                .show()
            return false
        }
        return true
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            if (checkP2pPrerequisites()) {
                startP2pMode()
            }
        }
    }

    private fun startP2pMode() {
        if (!isP2pInitialized) {
            setupP2P()
        }
        setUiState(P2pUiState.BATTLE)
        binding.player2Card.selectFighterButton.isVisible = false // Player 2 is remote
    }

    private fun setupP2P() {
        if (isP2pInitialized) return

        channel = wifiP2pManager.initialize(this, mainLooper, null)
        channel?.also {
            receiver = WifiDirectBroadcastReceiver()
            intentFilter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            registerReceiver(receiver, intentFilter)
            isP2pInitialized = true
        } ?: run {
            Toast.makeText(this, getString(R.string.toast_p2p_init_failed), Toast.LENGTH_LONG).show()
            setUiState(P2pUiState.IDLE)
        }
    }

    private fun startMatchmaking() {
        if (matchmakingState != P2pMatchmakingState.IDLE) return
        setUiState(P2pUiState.MATCHMAKING)
        log(getString(R.string.p2p_status_seeking))

        // Start with a clean slate by removing any existing group.
        wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                initiateDiscovery()
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "removeGroup failed but proceeding anyway. Reason: $reason")
                initiateDiscovery()
            }
        })
    }

    private fun initiateDiscovery() {
        matchmakingState = P2pMatchmakingState.SEEKING
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log(getString(R.string.p2p_log_discovery_started))
                // Start a timeout to become a host if no one is found
                matchmakingJob = lifecycleScope.launch {
                    delay(7000) // 7-second timeout
                    if (matchmakingState == P2pMatchmakingState.SEEKING) {
                        log(getString(R.string.p2p_log_seeking_timeout))
                        wifiP2pManager.stopPeerDiscovery(channel, null)
                        matchmakingState = P2pMatchmakingState.WAITING_AS_HOST
                        setUiState(P2pUiState.MATCHMAKING)
                        createP2pGroup()
                    }
                }
            }
            override fun onFailure(reason: Int) {
                log(getString(R.string.p2p_log_discovery_failed, reason))
                setUiState(P2pUiState.IDLE)
            }
        })
    }


    private fun createP2pGroup() {
        wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log(getString(R.string.p2p_log_group_created))
            }
            override fun onFailure(reason: Int) {
                log(getString(R.string.p2p_log_group_failed, reason))
                Toast.makeText(this@BattleArenaActivity, getString(R.string.toast_host_failed), Toast.LENGTH_LONG).show()
                setUiState(P2pUiState.IDLE)
            }
        })
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC // Specify WPS method for a more reliable handshake
            groupOwnerIntent = 0 // Explicitly want to be a client
        }
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@BattleArenaActivity, getString(R.string.toast_connection_request_sent, device.deviceName), Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(reason: Int) {
                val reasonText = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> getString(R.string.toast_p2p_unsupported)
                    WifiP2pManager.ERROR -> getString(R.string.toast_p2p_framework_error)
                    WifiP2pManager.BUSY -> getString(R.string.toast_p2p_busy)
                    else -> getString(R.string.toast_p2p_unknown_error)
                }
                Toast.makeText(this@BattleArenaActivity, getString(R.string.toast_connection_failed, reasonText), Toast.LENGTH_LONG).show()
            }
        })
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        if (matchmakingState != P2pMatchmakingState.SEEKING) return@PeerListListener

        val otherPeers = peerList.deviceList.filter { it.deviceAddress != thisDevice?.deviceAddress }
        if (otherPeers.isNotEmpty()) {
            matchmakingJob?.cancel() // Found someone, cancel the "become host" timeout
            wifiP2pManager.stopPeerDiscovery(channel, null)
            matchmakingState = P2pMatchmakingState.IDLE // Stop further matchmaking attempts

            val ownAddress = thisDevice?.deviceAddress ?: ""
            val opponent = otherPeers.first() // Connect to the first one found for simplicity

            // Simple deterministic logic: device with the lower MAC address connects
            if (ownAddress.compareTo(opponent.deviceAddress, ignoreCase = true) < 0) {
                log(getString(R.string.p2p_log_automatching_client))
                connectToPeer(opponent)
            } else {
                log(getString(R.string.p2p_log_automatching_host))
                matchmakingState = P2pMatchmakingState.WAITING_AS_HOST
                setUiState(P2pUiState.MATCHMAKING)
                createP2pGroup()
            }
        }
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        val groupOwnerAddress: InetAddress = info.groupOwnerAddress ?: return
        isP2pConnected = true
        setUiState(P2pUiState.BATTLE)

        if (info.isGroupOwner) {
            isHost = true
            log(getString(R.string.p2p_log_host_waiting))
            serverThread = ServerThread()
            serverThread?.start()
        } else {
            isHost = false
            log(getString(R.string.p2p_log_client_sending))
            clientThread = ClientThread(groupOwnerAddress)
            clientThread?.start()
        }
    }

    inner class WifiDirectBroadcastReceiver : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION") // Suppress warnings for NetworkInfo, isConnected, and getParcelableExtra
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        Toast.makeText(context, getString(R.string.toast_p2p_disabled), Toast.LENGTH_LONG).show()
                        disconnectAndCleanup()
                        setUiState(P2pUiState.IDLE)
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (isP2pInitialized) wifiP2pManager.requestPeers(channel, peerListListener)
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    thisDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (!isP2pInitialized) return

                    val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                    if (networkInfo?.isConnected == true) {
                        wifiP2pManager.requestConnectionInfo(channel, this@BattleArenaActivity)
                    } else {
                        if (isP2pConnected) {
                            isP2pConnected = false
                            log(getString(R.string.p2p_log_connection_lost))
                            setUiState(P2pUiState.IDLE)
                        }
                    }
                }
            }
        }
    }

    private fun disconnectAndCleanup() {
        matchmakingJob?.cancel()
        matchmakingState = P2pMatchmakingState.IDLE
        isP2pConnected = false

        serverThread?.interrupt()
        clientThread?.interrupt()
        serverThread = null
        clientThread = null

        if (!isP2pInitialized) return

        channel?.also { chan ->
            wifiP2pManager.stopPeerDiscovery(chan, null)
            wifiP2pManager.cancelConnect(chan, null)
            wifiP2pManager.removeGroup(chan, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "P2P group removed successfully.") }
                override fun onFailure(reason: Int) { Log.d(TAG, "Failed to remove P2P group: $reason") }
            })
        }
        Log.d(TAG, "P2P resources have been cleaned up.")
    }

    inner class ServerThread : Thread() {
        private var serverSocket: ServerSocket? = null
        private var socket: Socket? = null
        override fun run() {
            try {
                serverSocket = ServerSocket()
                serverSocket!!.reuseAddress = true
                serverSocket!!.bind(InetSocketAddress(8888))
                socket = serverSocket!!.accept() // Blocking call

                val inputStream = socket!!.getInputStream()
                val outputStream = socket!!.getOutputStream()
                val clientMsg = Gson().fromJson(readMessage(inputStream), P2pMessage::class.java)
                player2Card = clientMsg.card
                battleSeed = System.currentTimeMillis()
                val hostMsg = P2pMessage(card = player1Card!!, seed = battleSeed)
                writeMessage(outputStream, Gson().toJson(hostMsg))
                runOnUiThread {
                    updateFighterView(binding.player2Card, player2Card)
                    setUiState(P2pUiState.BATTLE)
                    startBattle()
                }
            } catch (e: IOException) {
                Log.e(TAG, "ServerThread has been interrupted or an IO error occurred.", e)
            } finally {
                try {
                    socket?.close()
                    serverSocket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing server sockets in finally", e)
                }
            }
        }
    }

    inner class ClientThread(private val hostAddress: InetAddress) : Thread() {
        private var socket: Socket? = null
        override fun run() {
            try {
                socket = Socket()
                socket!!.connect(InetSocketAddress(hostAddress, 8888), 5000)
                val outputStream = socket!!.getOutputStream()
                val inputStream = socket!!.getInputStream()
                val clientMsg = P2pMessage(card = player1Card!!)
                writeMessage(outputStream, Gson().toJson(clientMsg))
                val hostMsg = Gson().fromJson(readMessage(inputStream), P2pMessage::class.java)
                player2Card = hostMsg.card
                battleSeed = hostMsg.seed!!
                runOnUiThread {
                    updateFighterView(binding.player2Card, player2Card)
                    setUiState(P2pUiState.BATTLE)
                    startBattle()
                }
            } catch (e: IOException) {
                Log.e(TAG, "ClientThread IOException", e)
            } finally {
                try {
                    socket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing client socket in finally", e)
                }
            }
        }
    }

    private fun readMessage(inputStream: InputStream): String {
        val buffer = ByteArray(1024)
        val bytes = inputStream.read(buffer)
        return String(buffer, 0, bytes)
    }

    private fun writeMessage(outputStream: OutputStream, message: String) {
        outputStream.write(message.toByteArray())
    }

    private fun launchCardSelection(launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
        val intent = Intent(this, CollectionActivity::class.java).apply {
            putExtra(CollectionActivity.EXTRA_SELECTION_MODE, true)
        }
        launcher.launch(intent)
    }
}

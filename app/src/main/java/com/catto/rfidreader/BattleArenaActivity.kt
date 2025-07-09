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
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.catto.rfidreader.databinding.ActivityBattleArenaBinding
import com.catto.rfidreader.databinding.ViewFighterCardBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
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
    private enum class BattleMode { NONE, LOCAL, P2P_HOST, P2P_CLIENT }
    private enum class P2pUiState { IDLE, DISCOVERY, CONNECTING, BATTLE }

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

    // --- P2P and System Service Variables ---
    private val wifiP2pManager: WifiP2pManager by lazy { getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager }
    private val wifiManager: WifiManager by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val locationManager: LocationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private lateinit var intentFilter: IntentFilter
    private val peers = mutableListOf<WifiP2pDevice>()
    private lateinit var peerListAdapter: PeerListAdapter
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
        binding.hostP2pButton.setOnClickListener {
            battleMode = BattleMode.P2P_HOST
            checkAndRequestPermissions()
        }
        binding.joinP2pButton.setOnClickListener {
            battleMode = BattleMode.P2P_CLIENT
            checkAndRequestPermissions()
        }

        binding.player1Card.selectFighterButton.setOnClickListener { launchCardSelection(selectPlayer1Launcher) }
        binding.player2Card.selectFighterButton.setOnClickListener { launchCardSelection(selectPlayer2Launcher) }

        binding.resetP2pButton.setOnClickListener {
            Toast.makeText(this, "Returning to menu...", Toast.LENGTH_SHORT).show()
            disconnectAndCleanup()
            setUiState(P2pUiState.IDLE)
        }
    }

    private fun setUiState(state: P2pUiState) {
        val isP2pMode = state == P2pUiState.DISCOVERY || state == P2pUiState.CONNECTING
        binding.resetP2pButton.isVisible = isP2pMode
        if (isP2pMode) {
            binding.resetP2pButton.text = "Reset P2P" // Reset text for these states
        }


        // Set default visibility for all components controlled by the state machine
        binding.modeSelectionContainer.isVisible = false
        binding.fightersContainer.isVisible = false
        binding.battleLogScroll.isVisible = false
        binding.peersRecyclerView.isVisible = false

        when (state) {
            P2pUiState.IDLE -> {
                binding.battleArenaToolbar.title = getString(R.string.title_activity_battle_setup)
                binding.battleArenaToolbar.subtitle = null
                binding.modeSelectionContainer.isVisible = true

                // Reset player cards and update UI
                player1Card = null
                player2Card = null
                updateFighterView(binding.player1Card, null)
                updateFighterView(binding.player2Card, null)
            }
            P2pUiState.DISCOVERY -> {
                binding.battleArenaToolbar.title = getString(R.string.title_activity_p2p_battle)
                binding.battleArenaToolbar.subtitle = getString(R.string.p2p_status_finding_opponents)
                // Hide the fighter container to prevent it from overlapping with the peer list
                binding.fightersContainer.isVisible = false
                binding.peersRecyclerView.isVisible = true
            }
            P2pUiState.CONNECTING -> {
                binding.battleArenaToolbar.title = getString(R.string.title_activity_p2p_battle)
                binding.battleArenaToolbar.subtitle = "Connecting..."
                binding.fightersContainer.isVisible = true
                binding.battleLogScroll.isVisible = true
                binding.battleLogText.text = ""
            }
            P2pUiState.BATTLE -> {
                binding.battleArenaToolbar.title = getString(R.string.title_activity_battle_arena)
                binding.battleArenaToolbar.subtitle = null
                binding.fightersContainer.isVisible = true
                binding.battleLogScroll.isVisible = true

                // Always update views to reflect the current card state
                updateFighterView(binding.player1Card, player1Card)
                updateFighterView(binding.player2Card, player2Card)

                // Special handling for local vs P2P player 2 button visibility
                if (battleMode != BattleMode.LOCAL) {
                    binding.player2Card.selectFighterButton.isVisible = false
                }

                if ((battleMode == BattleMode.LOCAL && player1Card != null && player2Card != null) || (battleMode != BattleMode.LOCAL && player2Card != null)) {
                    binding.battleLogText.text = ""
                } else {
                    binding.battleLogText.text = getString(R.string.battle_log_placeholder)
                }
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
        }
    }

    // --- Battle Logic ---
    private fun checkBattleReady() {
        if (battleMode == BattleMode.LOCAL) {
            if (player1Card != null && player2Card != null) {
                startBattle()
            }
        } else if (battleMode == BattleMode.P2P_HOST) {
            if (player1Card != null) {
                startHosting()
            }
        } else if (battleMode == BattleMode.P2P_CLIENT) {
            if (player1Card != null) {
                startDiscovery()
            }
        }
    }


    private fun startBattle() {
        binding.player1Card.selectFighterButton.isEnabled = false
        binding.player2Card.selectFighterButton.isEnabled = false
        binding.battleLogText.text = ""
        log("The battle begins!")

        val p1 = player1Card!!
        val p2 = player2Card!!
        val p1Stats = p1.battleStats!!
        val p2Stats = p2.battleStats!!
        val seed = if (battleMode == BattleMode.LOCAL) System.currentTimeMillis() else this.battleSeed
        val random = Random(seed)

        lifecycleScope.launch(Dispatchers.Main) {
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

            // After battle, disconnect in the background but keep the UI
            if (battleMode != BattleMode.LOCAL) {
                log("Battle finished. Press 'Back to Menu' to play again.")
                // Disconnect P2P in the background without changing the UI state
                disconnectAndCleanup()
                // Show a button to let the user return to the main menu
                binding.resetP2pButton.text = "Back to Menu"
                binding.resetP2pButton.isVisible = true
            } else {
                // For local battles, just re-enable the select buttons for a new match
                log("Battle finished! Select new fighters to play again.")
                binding.player1Card.selectFighterButton.isEnabled = true
                binding.player2Card.selectFighterButton.isEnabled = true
            }
        }
    }

    private suspend fun updateCardStatsAfterBattle(winner: ScannedCard, loser: ScannedCard) {
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
            var message = "To use P2P battles, please enable:"
            if (!isWifiEnabled) message += "\n- Wi-Fi"
            if (!isLocationEnabled) message += "\n- Location Services"

            MaterialAlertDialogBuilder(this)
                .setTitle("P2P Requirements")
                .setMessage(message)
                .setPositiveButton("Go to Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                }
                .setNegativeButton("Cancel") { dialog, _ ->
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
        log("Please select your fighter to begin P2P.")
    }

    private fun setupP2P() {
        if (isP2pInitialized) return

        channel = wifiP2pManager.initialize(this, mainLooper, null)
        channel?.also {
            receiver = WifiDirectBroadcastReceiver()
            intentFilter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            registerReceiver(receiver, intentFilter)
            peerListAdapter = PeerListAdapter { device -> connectToPeer(device) }
            binding.peersRecyclerView.adapter = peerListAdapter
            isP2pInitialized = true
        } ?: run {
            Toast.makeText(this, "Failed to initialize Wi-Fi P2P.", Toast.LENGTH_LONG).show()
            setUiState(P2pUiState.IDLE)
        }
    }

    private fun startHosting() {
        if (!isP2pInitialized) return
        setUiState(P2pUiState.CONNECTING)
        log(getString(R.string.p2p_status_hosting))
        wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                createP2pGroup()
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "removeGroup failed with reason $reason, but proceeding to create group.")
                createP2pGroup()
            }
        })
    }

    private fun createP2pGroup() {
        wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Group created successfully. Waiting for connections...")
            }
            override fun onFailure(reason: Int) {
                log("Fatal: Failed to create group. Reason: $reason")
                Toast.makeText(this@BattleArenaActivity, "Could not start hosting. Please try resetting P2P.", Toast.LENGTH_LONG).show()
                setUiState(P2pUiState.IDLE)
            }
        })
    }

    private fun startDiscovery() {
        if (!isP2pInitialized) return
        setUiState(P2pUiState.DISCOVERY)
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                log("Peer discovery started...")
            }
            override fun onFailure(reason: Int) {
                log("Peer discovery failed. Reason: $reason")
                setUiState(P2pUiState.IDLE)
            }
        })
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@BattleArenaActivity, "Connection request sent to ${device.deviceName}", Toast.LENGTH_SHORT).show()
            }

            override fun onFailure(reason: Int) {
                val reasonText = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P is not supported on this device."
                    WifiP2pManager.ERROR -> "Framework error. Ensure Wi-Fi & Location are enabled and try again."
                    WifiP2pManager.BUSY -> "The framework is busy. Please try again."
                    else -> "An unknown error occurred."
                }
                Toast.makeText(this@BattleArenaActivity, "Connection failed: $reasonText", Toast.LENGTH_LONG).show()
            }
        })
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        peers.clear()
        peers.addAll(peerList.deviceList)
        peerListAdapter.submitList(peers.toList())
        if (binding.peersRecyclerView.isVisible) {
            binding.battleArenaToolbar.subtitle = if (peers.isEmpty()) "Searching..." else "Found ${peers.size} opponent(s)"
        }
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        val groupOwnerAddress: InetAddress = info.groupOwnerAddress ?: return
        isP2pConnected = true // We have connection info, so we are connected.
        setUiState(P2pUiState.CONNECTING)
        if (info.groupFormed && info.isGroupOwner) {
            isHost = true
            log("You are the host. Waiting for opponent to connect...")
            serverThread = ServerThread()
            serverThread?.start()
        } else if (info.groupFormed) {
            isHost = false
            log("Connected to host. Sending card...")
            clientThread = ClientThread(groupOwnerAddress)
            clientThread?.start()
        }
    }

    inner class WifiDirectBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        Toast.makeText(context, "Wi-Fi P2P is disabled. Please enable it in settings.", Toast.LENGTH_LONG).show()
                        disconnectAndCleanup()
                        setUiState(P2pUiState.IDLE)
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (isP2pInitialized) wifiP2pManager.requestPeers(channel, peerListListener)
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (!isP2pInitialized) return

                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)

                    if (networkInfo?.isConnected == true) {
                        // A connection is established. Request details.
                        wifiP2pManager.requestConnectionInfo(channel, this@BattleArenaActivity)
                    } else {
                        // isConnected is false. This is a disconnect event.
                        // We only care if we were previously connected.
                        if (isP2pConnected) {
                            isP2pConnected = false // Update our state flag
                            log("Connection lost. Returning to menu.")
                            setUiState(P2pUiState.IDLE)
                        }
                        // If isP2pConnected was already false, we do nothing.
                        // This avoids resetting the UI during initial discovery phases.
                    }
                }
            }
        }
    }

    private fun disconnectAndCleanup() {
        if (!isP2pInitialized) return

        isP2pConnected = false // Reset connection flag

        serverThread?.interrupt()
        clientThread?.interrupt()
        serverThread = null
        clientThread = null

        receiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Receiver was not registered or already unregistered.", e)
            }
        }

        channel?.also { chan ->
            wifiP2pManager.stopPeerDiscovery(chan, null)
            wifiP2pManager.cancelConnect(chan, null)
            wifiP2pManager.removeGroup(chan, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "P2P group removed successfully.") }
                override fun onFailure(reason: Int) { Log.d(TAG, "Failed to remove P2P group: $reason") }
            })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    chan.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing channel", e)
                }
            }
        }

        isP2pInitialized = false
        channel = null
        receiver = null
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

    class PeerListAdapter(private val onItemClicked: (WifiP2pDevice) -> Unit) : RecyclerView.Adapter<PeerListAdapter.PeerViewHolder>() {
        private var peers = emptyList<WifiP2pDevice>()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder = PeerViewHolder.create(parent)
        override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
            val peer = peers[position]
            holder.bind(peer)
            holder.itemView.setOnClickListener { onItemClicked(peer) }
        }
        override fun getItemCount(): Int = peers.size
        @SuppressLint("NotifyDataSetChanged")
        fun submitList(newPeers: List<WifiP2pDevice>) {
            peers = newPeers
            notifyDataSetChanged()
        }
        //
        class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val nameText: TextView = itemView.findViewById(R.id.peer_name_text)
            fun bind(device: WifiP2pDevice) { nameText.text = device.deviceName }
            companion object { fun create(parent: ViewGroup): PeerViewHolder = PeerViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.list_item_peer, parent, false)) }
        }
    }
}

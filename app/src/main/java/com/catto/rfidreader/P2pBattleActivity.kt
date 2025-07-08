package com.catto.rfidreader

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.*
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.catto.rfidreader.databinding.ActivityP2pBattleBinding
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

private const val TAG = "P2pBattleActivity"
private const val MESSAGE_READ = 1

@SuppressLint("MissingPermission") // Permissions are checked with checkAndRequestPermissions()
class P2pBattleActivity : AppCompatActivity(), WifiP2pManager.ConnectionInfoListener {

    private lateinit var binding: ActivityP2pBattleBinding
    private val wifiP2pManager: WifiP2pManager by lazy { getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager }
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: BroadcastReceiver
    private lateinit var intentFilter: IntentFilter
    private val peers = mutableListOf<WifiP2pDevice>()
    private lateinit var peerListAdapter: PeerListAdapter

    private var localCard: ScannedCard? = null
    private var opponentCard: ScannedCard? = null

    private var serverThread: ServerThread? = null
    private var clientThread: ClientThread? = null
    private lateinit var handler: Handler

    private var isHost = false

    private val selectCardLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val cardId = result.data?.getIntExtra(SelectCardActivity.EXTRA_SELECTED_CARD_ID, -1) ?: -1
            if (cardId != -1) {
                lifecycleScope.launch {
                    localCard = (application as App).database.scannedCardDao().getCardById(cardId)
                    binding.hostButton.isEnabled = true
                    binding.joinButton.isEnabled = true
                    binding.statusText.text = getString(R.string.p2p_card_selected, localCard?.name)
                }
            }
        } else {
            if (localCard == null) {
                binding.statusText.text = getString(R.string.p2p_select_card_prompt)
            }
        }
    }

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] != true ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && permissions[Manifest.permission.NEARBY_WIFI_DEVICES] != true)) {
            Toast.makeText(this, getString(R.string.p2p_permission_required), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        binding = ActivityP2pBattleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.p2pBattleRootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        setSupportActionBar(binding.p2pToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        checkAndRequestPermissions()
        setupHandler()

        channel = wifiP2pManager.initialize(this, mainLooper, null)
        setupReceiver()

        peerListAdapter = PeerListAdapter { device -> connectToPeer(device) }
        binding.peersRecyclerView.adapter = peerListAdapter

        binding.hostButton.setOnClickListener {
            binding.statusText.text = getString(R.string.p2p_status_hosting)
            discoverPeers()
        }
        binding.joinButton.setOnClickListener { discoverPeers() }

        binding.statusText.setOnClickListener {
            if (localCard == null) {
                selectCardLauncher.launch(Intent(this, SelectCardActivity::class.java))
            }
        }

        binding.hostButton.isEnabled = false
        binding.joinButton.isEnabled = false
        binding.statusText.text = getString(R.string.p2p_select_card_prompt)
        selectCardLauncher.launch(Intent(this, SelectCardActivity::class.java))
    }

    private fun setupHandler() {
        handler = Handler(mainLooper) { msg ->
            when (msg.what) {
                MESSAGE_READ -> {
                    val readBuff = msg.obj as ByteArray
                    val tempMsg = String(readBuff, 0, msg.arg1)
                    opponentCard = Gson().fromJson(tempMsg, ScannedCard::class.java)

                    if (isHost) {
                        val localCardJson = Gson().toJson(localCard)
                        // Perform network write on a background thread to avoid NetworkOnMainThreadException
                        thread {
                            serverThread?.write(localCardJson.toByteArray())
                        }
                    }
                    startBattleIfReady()
                    true
                }
                else -> false
            }
        }
    }

    private fun startBattleIfReady() {
        if (localCard != null && opponentCard != null) {
            val intent = Intent(this, BattleArenaActivity::class.java).apply {
                val player1Json = if (isHost) Gson().toJson(localCard) else Gson().toJson(opponentCard)
                val player2Json = if (isHost) Gson().toJson(opponentCard) else Gson().toJson(localCard)
                putExtra(BattleArenaActivity.EXTRA_PLAYER_1_CARD, player1Json)
                putExtra(BattleArenaActivity.EXTRA_PLAYER_2_CARD, player2Json)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun setupReceiver() {
        receiver = WifiDirectBroadcastReceiver()
        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@P2pBattleActivity, getString(R.string.p2p_connecting_to, device.deviceName), Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reason: Int) {
                Toast.makeText(this@P2pBattleActivity, getString(R.string.p2p_connection_failed), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun discoverPeers() {
        binding.statusText.text = getString(R.string.p2p_status_discovering)
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@P2pBattleActivity, getString(R.string.p2p_discovery_initiated), Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reasonCode: Int) {
                Toast.makeText(this@P2pBattleActivity, getString(R.string.p2p_discovery_failed, reasonCode), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peerList ->
        peers.clear()
        peers.addAll(peerList.deviceList)
        peerListAdapter.submitList(peers.toList())
        if (peers.isEmpty()) Log.d(TAG, "No peers found.")
    }

    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        val groupOwnerAddress: InetAddress? = info.groupOwnerAddress
        if (groupOwnerAddress == null) {
            Log.e(TAG, "Connection info available but groupOwnerAddress is null.")
            return
        }

        if (info.groupFormed && info.isGroupOwner) {
            isHost = true
            binding.statusText.text = getString(R.string.p2p_host_waiting)
            if (serverThread == null) {
                serverThread = ServerThread(handler)
                serverThread!!.start()
            }
        } else if (info.groupFormed) {
            isHost = false
            binding.statusText.text = getString(R.string.p2p_client_sending)
            if (clientThread == null && localCard != null) {
                val localCardJson = Gson().toJson(localCard)
                clientThread = ClientThread(groupOwnerAddress, localCardJson, handler)
                clientThread!!.start()
            }
        }
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
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        serverThread?.interrupt()
        clientThread?.interrupt()
        wifiP2pManager.removeGroup(channel, null)
    }

    inner class WifiDirectBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> wifiP2pManager.requestPeers(channel, peerListListener)
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> wifiP2pManager.requestConnectionInfo(channel, this@P2pBattleActivity)
            }
        }
    }

    class ServerThread(private val handler: Handler) : Thread() {
        private var serverSocket: ServerSocket? = null
        private var socket: Socket? = null
        private var outputStream: OutputStream? = null

        override fun run() {
            try {
                serverSocket = ServerSocket(8888)
                socket = serverSocket!!.accept()
                val inputStream = socket!!.getInputStream()
                outputStream = socket!!.getOutputStream()
                val buffer = ByteArray(1024)
                val bytes = inputStream.read(buffer)
                if (bytes > 0) {
                    handler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget()
                }
            } catch (e: IOException) {
                Log.e(TAG, "ServerThread IOException", e)
            }
        }

        fun write(bytes: ByteArray) {
            try {
                outputStream?.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "ServerThread write error", e)
            }
        }

        override fun interrupt() {
            super.interrupt()
            try {
                socket?.close()
                serverSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing sockets", e)
            }
        }
    }

    class ClientThread(private val hostAddress: InetAddress, private val data: String, private val handler: Handler) : Thread() {
        private var socket: Socket? = null

        override fun run() {
            socket = Socket()
            try {
                socket!!.connect(InetSocketAddress(hostAddress, 8888), 5000)
                val outputStream = socket!!.getOutputStream()
                val inputStream = socket!!.getInputStream()
                outputStream.write(data.toByteArray())

                val buffer = ByteArray(1024)
                val bytes = inputStream.read(buffer)
                if (bytes > 0) {
                    handler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget()
                }
            } catch (e: IOException) {
                Log.e(TAG, "ClientThread IOException", e)
            } finally {
                try {
                    socket?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing client socket", e)
                }
            }
        }

        override fun interrupt() {
            super.interrupt()
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing client socket", e)
            }
        }
    }
}

class PeerListAdapter(private val onItemClicked: (WifiP2pDevice) -> Unit) :
    RecyclerView.Adapter<PeerListAdapter.PeerViewHolder>() {
    private var peers = emptyList<WifiP2pDevice>()
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_peer, parent, false)
        return PeerViewHolder(view)
    }
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
    class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.peer_name_text)
        fun bind(device: WifiP2pDevice) {
            nameText.text = device.deviceName
        }
    }
}

package com.catto.rfidreader

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private val historyViewModel: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // --- ADD THIS to enable edge-to-edge display ---
        WindowCompat.setDecorFitsSystemWindows(window, false)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val recyclerView = findViewById<RecyclerView>(R.id.history_recycler_view)
        val adapter = HistoryAdapter()
        recyclerView.adapter = adapter

        // --- ADD THIS LISTENER to handle the status bar overlap ---
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply the insets as padding to the RecyclerView
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)

            // Return the insets to fulfill the listener's contract
            windowInsets
        }

        historyViewModel.allCards.observe(this) { cards ->
            cards?.let { adapter.submitList(it) }
        }
    }
}

// The classes below are unchanged, but are included here to ensure the file is complete.

class HistoryAdapter : ListAdapter<ScannedCard, HistoryAdapter.CardViewHolder>(CardsComparator()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        return CardViewHolder.create(parent)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val current = getItem(position)
        holder.bind(current)
    }

    class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val serialNumberView: TextView = itemView.findViewById(R.id.serial_number_text)
        private val timestampView: TextView = itemView.findViewById(R.id.timestamp_text)
        private val tagInfoView: TextView = itemView.findViewById(R.id.tag_info_text)
        private val shareButton: ImageButton = itemView.findViewById(R.id.share_history_button)

        fun bind(card: ScannedCard) {
            val context = itemView.context
            tagInfoView.text = card.tagInfo
            serialNumberView.text = context.getString(R.string.history_sn_formatted, card.serialNumberHex)
            timestampView.text = context.getString(R.string.history_scanned_formatted, formatTimestamp(card.scanTimestamp))

            shareButton.setOnClickListener {
                val shareText = """
                    NFC Card Scan from History:
                    Hex: ${card.serialNumberHex}
                    Decimal: ${card.decValue}
                    Binary: ${card.binValue}
                    ---
                    Reversed Hex: ${card.revHexValue}
                    Reversed Dec: ${card.revDecValue}
                    Reversed Bin: ${card.revBinValue}
                    ---
                    Score: ${card.score}
                    ---
                    ${card.tagInfo}
                """.trimIndent()

                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, "Share Card Details")
                context.startActivity(shareIntent)
            }
        }

        private fun formatTimestamp(timestamp: Long): String {
            val sdf = SimpleDateFormat("dd-MMM-yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

        companion object {
            fun create(parent: ViewGroup): CardViewHolder {
                val view: View = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_card, parent, false)
                return CardViewHolder(view)
            }
        }
    }

    class CardsComparator : DiffUtil.ItemCallback<ScannedCard>() {
        override fun areItemsTheSame(oldItem: ScannedCard, newItem: ScannedCard): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: ScannedCard, newItem: ScannedCard): Boolean {
            return oldItem == newItem
        }
    }
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as App).database.scannedCardDao()
    val allCards = dao.getAllCards().asLiveData()
}
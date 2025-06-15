package com.example.newrfidreader

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

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
        private val shareButton: ImageButton = itemView.findViewById(R.id.share_history_button) // <-- NEW

        fun bind(card: ScannedCard) {
            val context = itemView.context
            //serialNumberView.text = "${context.getString(R.string.history_sn_formatted)} ${card.serialNumberHex}"
            tagInfoView.text = card.tagInfo
            // --- WORKAROUND: Manually concatenate the strings in Kotlin ---
            serialNumberView.text = "SN: " + card.serialNumberHex
            timestampView.text = "Scanned: " + formatTimestamp(card.scanTimestamp)

            //timestampView.text = "${context.getString(R.string.history_scanned_formatted)} ${formatTimestamp(card.scanTimestamp)}"

            // --- NEW CLICK LISTENER ---
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
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
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
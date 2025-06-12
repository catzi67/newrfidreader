package com.example.newrfidreader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        fun bind(card: ScannedCard) {
            val context = itemView.context // Get context from the item view

            // Pass the serial number as an argument to the formatted string resource
            serialNumberView.text = context.getString(R.string.history_sn_formatted, card.serialNumberHex)

            tagInfoView.text = card.tagInfo

            // Pass the formatted timestamp as an argument
            timestampView.text = context.getString(R.string.history_scanned_formatted, formatTimestamp(card.scanTimestamp))
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
package com.catto.rfidreader

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
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
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private val historyViewModel: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val recyclerView = findViewById<RecyclerView>(R.id.history_recycler_view)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val textSizePref = prefs.getString("pref_key_text_size", "small") ?: "small"
        val adapter = HistoryAdapter(textSizePref)

        recyclerView.adapter = adapter

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        historyViewModel.allCards.observe(this) { cards ->
            cards?.let { adapter.submitList(it) }
        }

        // Set up ItemTouchHelper for swipe-to-delete functionality.
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false // We don't want to handle move gestures.
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val card = adapter.currentList[position]
                historyViewModel.delete(card)

                // Show a Snackbar with an "Undo" action.
                Snackbar.make(recyclerView, getString(R.string.card_deleted), Snackbar.LENGTH_LONG)
                    .setAction(getString(R.string.undo)) {
                        historyViewModel.insert(card) // Re-insert the card if "Undo" is clicked.
                    }
                    .show()
            }
        }

        val itemTouchHelper = ItemTouchHelper(itemTouchHelperCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }
}

class HistoryAdapter(private val textSizePref: String) : ListAdapter<ScannedCard, HistoryAdapter.CardViewHolder>(CardsComparator()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        return CardViewHolder.create(parent)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val current = getItem(position)
        holder.bind(current, textSizePref)
    }

    class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardNameView: TextView = itemView.findViewById(R.id.card_name_text)
        private val serialNumberView: TextView = itemView.findViewById(R.id.serial_number_text)
        private val timestampView: TextView = itemView.findViewById(R.id.timestamp_text)
        private val tagInfoView: TextView = itemView.findViewById(R.id.tag_info_text)
        private val shareButton: ImageButton = itemView.findViewById(R.id.share_history_button)

        fun bind(card: ScannedCard, textSizePref: String) {
            val context = itemView.context

            if (card.name.isNullOrEmpty()) {
                cardNameView.visibility = View.GONE
            } else {
                cardNameView.text = card.name
                cardNameView.visibility = View.VISIBLE
            }

            tagInfoView.text = card.tagInfo
            serialNumberView.text = context.getString(R.string.history_sn_formatted, card.serialNumberHex)
            timestampView.text = context.getString(R.string.history_scanned_formatted, formatTimestamp(card.scanTimestamp))

            val (labelSize, valueSize) = when (textSizePref) {
                "medium" -> 14f to 16f
                "large" -> 16f to 18f
                else -> 12f to 14f // "small"
            }

            cardNameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSize + 2f)
            serialNumberView.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSize)
            timestampView.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize)
            tagInfoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize)


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

            itemView.setOnClickListener {
                val intent = Intent(context, EditCardActivity::class.java).apply {
                    putExtra(EditCardActivity.EXTRA_CARD_ID, card.id)
                }
                context.startActivity(intent)
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

    // Function to delete a card.
    fun delete(card: ScannedCard) = viewModelScope.launch {
        dao.delete(card)
    }

    // Function to re-insert a card for the "Undo" action.
    fun insert(card: ScannedCard) = viewModelScope.launch {
        dao.insert(card)
    }
}

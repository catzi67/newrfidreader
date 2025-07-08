package com.catto.rfidreader

import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.*
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.catto.rfidreader.databinding.ActivityCollectionBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.text.SimpleDateFormat
import java.util.*

enum class SortOrder { RATING, DATE }

class CollectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionBinding
    private val viewModel: CollectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        binding = ActivityCollectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.collectionRootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        setSupportActionBar(binding.collectionToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val adapter = CollectionAdapter(
            onCardClicked = { card ->
                val intent = Intent(this, EditCardActivity::class.java).apply {
                    putExtra(EditCardActivity.EXTRA_CARD_ID, card.id)
                }
                startActivity(intent)
            },
            onShareClicked = { card -> shareCard(card) }
        )

        binding.collectionRecyclerView.adapter = adapter

        viewModel.cards.observe(this) { cards ->
            cards?.let { adapter.submitList(it) }
        }

        viewModel.sortOrder.asLiveData().observe(this) { sortOrder ->
            adapter.updateSortOrder(sortOrder)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_collection, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            R.id.sort_by_rating -> {
                viewModel.setSortOrder(SortOrder.RATING)
                true
            }
            R.id.sort_by_date -> {
                viewModel.setSortOrder(SortOrder.DATE)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun shareCard(card: ScannedCard) {
        val shareText = """
            NFC Card: ${card.name ?: ""}
            Hex: ${card.serialNumberHex}
            Rating: ${card.eloRating} (${card.wins}W - ${card.losses}L)
            Scanned: ${SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).format(Date(card.scanTimestamp))}
        """.trimIndent()
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        startActivity(Intent.createChooser(sendIntent, null))
    }
}

class CollectionAdapter(
    private val onCardClicked: (ScannedCard) -> Unit,
    private val onShareClicked: (ScannedCard) -> Unit
) : ListAdapter<ScannedCard, CollectionAdapter.CardViewHolder>(CardsComparator()) {

    private var currentSortOrder: SortOrder = SortOrder.RATING

    fun updateSortOrder(newSortOrder: SortOrder) {
        currentSortOrder = newSortOrder
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_collection, parent, false)
        return CardViewHolder(view, onCardClicked, onShareClicked)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position), position + 1, currentSortOrder)
    }

    class CardViewHolder(
        itemView: View,
        private val onCardClicked: (ScannedCard) -> Unit,
        private val onShareClicked: (ScannedCard) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val rankText: TextView = itemView.findViewById(R.id.rank_text)
        private val cardNameText: TextView = itemView.findViewById(R.id.card_name_text)
        private val serialNumberText: TextView = itemView.findViewById(R.id.serial_number_text)
        private val signatureView: SignatureView = itemView.findViewById(R.id.signature_view)
        private val ratingValue: TextView = itemView.findViewById(R.id.rating_value)
        private val recordValue: TextView = itemView.findViewById(R.id.record_value)
        private val timestampText: TextView = itemView.findViewById(R.id.timestamp_text)
        private val shareButton: ImageButton = itemView.findViewById(R.id.share_button)

        fun bind(card: ScannedCard, position: Int, sortOrder: SortOrder) {
            cardNameText.text = card.name ?: "Card #${card.id}"
            serialNumberText.text = card.serialNumberHex
            ratingValue.text = card.eloRating.toString()
            recordValue.text = "${card.wins}W - ${card.losses}L"
            timestampText.text = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault()).format(Date(card.scanTimestamp))
            signatureView.setCardId(hexStringToByteArray(card.serialNumberHex))

            if (sortOrder == SortOrder.RATING) {
                rankText.visibility = View.VISIBLE
                rankText.text = position.toString()

                val context = itemView.context
                val background = ContextCompat.getDrawable(context, R.drawable.rank_circle_background)?.mutate()
                val rankColor = when (position) {
                    1 -> Color.parseColor("#FFD700") // Gold
                    2 -> Color.parseColor("#C0C0C0") // Silver
                    3 -> Color.parseColor("#CD7F32") // Bronze
                    else -> {
                        val typedValue = TypedValue()
                        context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)
                        typedValue.data
                    }
                }
                background?.setTint(rankColor)
                rankText.background = background

            } else {
                rankText.visibility = View.GONE
            }

            itemView.setOnClickListener { onCardClicked(card) }
            shareButton.setOnClickListener { onShareClicked(card) }
        }
    }

    class CardsComparator : DiffUtil.ItemCallback<ScannedCard>() {
        override fun areItemsTheSame(oldItem: ScannedCard, newItem: ScannedCard): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ScannedCard, newItem: ScannedCard): Boolean = oldItem == newItem
    }
}

class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as App).database.scannedCardDao()
    val sortOrder = MutableStateFlow(SortOrder.RATING)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val cards: LiveData<List<ScannedCard>> = sortOrder.flatMapLatest { order ->
        when (order) {
            SortOrder.RATING -> dao.getLeaderboard()
            SortOrder.DATE -> dao.getAllCards()
        }
    }.asLiveData()

    fun setSortOrder(order: SortOrder) {
        sortOrder.value = order
    }
}

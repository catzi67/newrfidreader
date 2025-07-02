package com.catto.rfidreader

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class SelectCardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_CARD_ID = "extra_selected_card_id"
    }

    private val selectCardViewModel: SelectCardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_select_card)

        val toolbar: Toolbar = findViewById(R.id.select_card_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val rootLayout: ConstraintLayout = findViewById(R.id.select_card_root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        val recyclerView = findViewById<RecyclerView>(R.id.select_card_recycler_view)
        val adapter = SelectCardAdapter { card ->
            val resultIntent = Intent()
            resultIntent.putExtra(EXTRA_SELECTED_CARD_ID, card.id)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
        recyclerView.adapter = adapter

        selectCardViewModel.allCards.observe(this) { cards ->
            cards?.let { adapter.submitList(it) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class SelectCardAdapter(private val onCardSelected: (ScannedCard) -> Unit) : ListAdapter<ScannedCard, SelectCardAdapter.CardViewHolder>(CardsComparator()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        return CardViewHolder.create(parent)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val current = getItem(position)
        holder.bind(current, onCardSelected)
    }

    class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameView: TextView = itemView.findViewById(R.id.select_card_name)
        private val statsView: TextView = itemView.findViewById(R.id.select_card_stats)

        fun bind(card: ScannedCard, onCardSelected: (ScannedCard) -> Unit) {
            nameView.text = card.name ?: "Card #${card.id}"
            card.battleStats?.let {
                statsView.text = itemView.context.getString(R.string.select_card_stats_format, it.hp, it.attack, it.defense, it.speed, it.elementType.name)
            }
            itemView.setOnClickListener { onCardSelected(card) }
        }

        companion object {
            fun create(parent: ViewGroup): CardViewHolder {
                val view: View = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_select_card, parent, false)
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

class SelectCardViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as App).database.scannedCardDao()
    val allCards = dao.getAllCards().asLiveData()
}

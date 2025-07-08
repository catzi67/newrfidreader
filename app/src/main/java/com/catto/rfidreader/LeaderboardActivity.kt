package com.catto.rfidreader

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.catto.rfidreader.databinding.ActivityLeaderboardBinding

class LeaderboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLeaderboardBinding
    private val leaderboardViewModel: LeaderboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // This enables the edge-to-edge display.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        binding = ActivityLeaderboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // This listener applies padding to the root view to prevent it from
        // overlapping with the system bars (status bar, navigation bar).
        ViewCompat.setOnApplyWindowInsetsListener(binding.leaderboardRootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        setSupportActionBar(binding.leaderboardToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val adapter = LeaderboardAdapter()
        binding.leaderboardRecyclerView.adapter = adapter

        leaderboardViewModel.leaderboard.observe(this) { leaderboard ->
            leaderboard?.let { adapter.submitList(it) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class LeaderboardAdapter : ListAdapter<ScannedCard, LeaderboardAdapter.LeaderboardViewHolder>(LeaderboardComparator()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LeaderboardViewHolder {
        return LeaderboardViewHolder.create(parent)
    }

    override fun onBindViewHolder(holder: LeaderboardViewHolder, position: Int) {
        val current = getItem(position)
        holder.bind(current, position + 1)
    }

    class LeaderboardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rankText: TextView = itemView.findViewById(R.id.rank_text)
        private val cardNameText: TextView = itemView.findViewById(R.id.card_name_text)
        private val recordText: TextView = itemView.findViewById(R.id.record_text)
        private val ratingText: TextView = itemView.findViewById(R.id.rating_text)
        private val statsText: TextView = itemView.findViewById(R.id.stats_text)

        fun bind(card: ScannedCard, rank: Int) {
            val context = itemView.context
            rankText.text = "$rank."
            cardNameText.text = card.name ?: context.getString(R.string.card_id_placeholder, card.id)
            recordText.text = context.getString(R.string.leaderboard_record_format, card.wins, card.losses)
            ratingText.text = card.eloRating.toString()

            card.battleStats?.let { stats ->
                statsText.text = context.getString(R.string.leaderboard_stats_format, stats.hp, stats.attack, stats.defense, stats.speed, stats.luck)
                statsText.visibility = View.VISIBLE
            } ?: run {
                statsText.visibility = View.GONE
            }
        }

        companion object {
            fun create(parent: ViewGroup): LeaderboardViewHolder {
                val view: View = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_leaderboard, parent, false)
                return LeaderboardViewHolder(view)
            }
        }
    }

    class LeaderboardComparator : DiffUtil.ItemCallback<ScannedCard>() {
        override fun areItemsTheSame(oldItem: ScannedCard, newItem: ScannedCard): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ScannedCard, newItem: ScannedCard): Boolean {
            return oldItem == newItem
        }
    }
}

class LeaderboardViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as App).database.scannedCardDao()
    val leaderboard = dao.getLeaderboard().asLiveData()
}

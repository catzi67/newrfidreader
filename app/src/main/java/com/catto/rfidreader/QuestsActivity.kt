package com.catto.rfidreader

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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

class QuestsActivity : AppCompatActivity() {

    private val questsViewModel: QuestsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quests)

        val toolbar: Toolbar = findViewById(R.id.quests_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val rootLayout: ConstraintLayout = findViewById(R.id.quests_root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        val recyclerView = findViewById<RecyclerView>(R.id.quests_recycler_view)
        val adapter = QuestAdapter()
        recyclerView.adapter = adapter

        questsViewModel.allQuests.observe(this) { quests ->
            quests?.let { adapter.submitList(it) }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

class QuestAdapter : ListAdapter<Quest, QuestAdapter.QuestViewHolder>(QuestsComparator()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuestViewHolder {
        return QuestViewHolder.create(parent)
    }

    override fun onBindViewHolder(holder: QuestViewHolder, position: Int) {
        val current = getItem(position)
        holder.bind(current)
    }

    class QuestViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.quest_title)
        private val descriptionView: TextView = itemView.findViewById(R.id.quest_description)
        private val completedIcon: ImageView = itemView.findViewById(R.id.quest_completed_icon)

        fun bind(quest: Quest) {
            titleView.text = quest.title
            descriptionView.text = quest.description
            if (quest.isCompleted) {
                completedIcon.visibility = View.VISIBLE
                itemView.alpha = 0.7f
            } else {
                completedIcon.visibility = View.GONE
                itemView.alpha = 1.0f
            }
        }

        companion object {
            fun create(parent: ViewGroup): QuestViewHolder {
                val view: View = LayoutInflater.from(parent.context)
                    .inflate(R.layout.list_item_quest, parent, false)
                return QuestViewHolder(view)
            }
        }
    }

    class QuestsComparator : DiffUtil.ItemCallback<Quest>() {
        override fun areItemsTheSame(oldItem: Quest, newItem: Quest): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: Quest, newItem: Quest): Boolean {
            return oldItem == newItem
        }
    }
}

class QuestsViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = (application as App).database.questDao()
    val allQuests = dao.getAllQuests().asLiveData()
}

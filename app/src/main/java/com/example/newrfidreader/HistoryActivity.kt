package com.example.newrfidreader

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.RecyclerView

class HistoryActivity : AppCompatActivity() {

    private val historyViewModel: HistoryViewModel by viewModels {
        HistoryViewModelFactory((application as App).database.scannedCardDao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val recyclerView = findViewById<RecyclerView>(R.id.history_recycler_view)
        val adapter = HistoryAdapter()
        recyclerView.adapter = adapter

        historyViewModel.allCards.observe(this) { cards ->
            cards?.let { adapter.submitList(it) }
        }
    }
}

// We need a ViewModel to hold the data
class HistoryViewModel(private val dao: ScannedCardDao) : ViewModel() {
    val allCards = dao.getAllCards().asLiveData()
}

// And a Factory to create the ViewModel
class HistoryViewModelFactory(private val dao: ScannedCardDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(dao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
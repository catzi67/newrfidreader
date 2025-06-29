package com.catto.rfidreader

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class EditCardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CARD_ID = "extra_card_id"
    }

    private lateinit var nameEditText: TextInputEditText
    private lateinit var notesEditText: TextInputEditText
    private lateinit var saveButton: Button
    private lateinit var dao: ScannedCardDao
    private var currentCard: ScannedCard? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_card)

        val toolbar: Toolbar = findViewById(R.id.edit_card_toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val rootLayout: ConstraintLayout = findViewById(R.id.edit_card_root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        nameEditText = findViewById(R.id.name_edit_text)
        notesEditText = findViewById(R.id.notes_edit_text)
        saveButton = findViewById(R.id.save_button)
        dao = (application as App).database.scannedCardDao()

        val cardId = intent.getIntExtra(EXTRA_CARD_ID, -1)
        if (cardId == -1) {
            finish() // Close if no valid ID is provided
            return
        }

        lifecycleScope.launch {
            currentCard = dao.getCardById(cardId)
            currentCard?.let {
                nameEditText.setText(it.name)
                notesEditText.setText(it.notes)
            }
        }

        saveButton.setOnClickListener {
            saveCardDetails()
        }
    }

    private fun saveCardDetails() {
        currentCard?.let { card ->
            card.name = nameEditText.text.toString()
            card.notes = notesEditText.text.toString()

            lifecycleScope.launch {
                dao.update(card)
                Toast.makeText(this@EditCardActivity, "Card details saved", Toast.LENGTH_SHORT).show()
                finish() // Go back to the previous screen
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}

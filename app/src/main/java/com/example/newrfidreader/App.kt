package com.example.newrfidreader

import android.app.Application

class App : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
}
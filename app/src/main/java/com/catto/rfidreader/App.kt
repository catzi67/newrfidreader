package com.catto.rfidreader

import android.app.Application

class App : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
}
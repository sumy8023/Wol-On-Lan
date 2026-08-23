package com.example.wolquicktile.watch

import android.app.Application
import com.example.wolquicktile.watch.data.WatchPreferencesRepository

class WatchApp : Application() {
    val preferencesRepository: WatchPreferencesRepository by lazy {
        WatchPreferencesRepository(this)
    }
}

package com.ribau.baixador

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class BaixadorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        History.markInterrupted(this)
    }
}

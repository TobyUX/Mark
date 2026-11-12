package com.shotagent.mark

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RUN_CHANNEL,
                "Agent runs",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val RUN_CHANNEL = "runs"
    }
}

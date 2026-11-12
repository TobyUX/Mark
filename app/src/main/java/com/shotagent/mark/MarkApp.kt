package com.shotagent.mark

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    RUN_CHANNEL,
                    "Agent runs",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    WALK_CHANNEL,
                    "Walkthrough",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        const val RUN_CHANNEL = "runs"
        const val WALK_CHANNEL = "walkthrough"
    }
}

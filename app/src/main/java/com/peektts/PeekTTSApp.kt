package com.peektts

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PeekTTSApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AssistantService.CHANNEL_ID,
                "PeekTTS 语音助手",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "语音助手后台运行通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

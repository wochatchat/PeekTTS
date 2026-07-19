package com.peektts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AssistantService : Service() {

    private var engine: AssistantEngine? = null

    override fun onCreate() {
        super.onCreate()
        engine = AssistantEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                if (engine?.isRunning == true) {
                    stopAssistant()
                } else {
                    startAssistant()
                }
            }
            ACTION_START -> startAssistant()
            ACTION_STOP -> stopAssistant()
        }

        // If no action, default to start
        if (intent?.action == null) {
            startAssistant()
        }

        return START_STICKY
    }

    private fun startAssistant() {
        startForeground(NOTIFICATION_ID, createNotification("语音助手运行中", "随时可以说话"))

        val modelManager = ModelManager(this)
        if (!modelManager.areModelsReady()) {
            broadcastState("error")
            broadcastModelStatus("请先下载模型", -1)
            return
        }

        engine?.start()
    }

    private fun stopAssistant() {
        engine?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(title: String, content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun broadcastState(state: String) {
        val intent = Intent(ACTION_STATE_CHANGED)
        intent.putExtra("state", state)
        sendBroadcast(intent)
    }

    fun broadcastConversation(role: String, text: String) {
        val intent = Intent(ACTION_CONVERSATION_UPDATE)
        intent.putExtra("role", role)
        intent.putExtra("text", text)
        sendBroadcast(intent)
    }

    fun broadcastModelStatus(status: String, progress: Int) {
        val intent = Intent(ACTION_MODEL_STATUS)
        intent.putExtra("status", status)
        intent.putExtra("progress", progress)
        sendBroadcast(intent)
    }

    companion object {
        const val CHANNEL_ID = "peektts_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_TOGGLE = "com.peektts.TOGGLE"
        const val ACTION_START = "com.peektts.START"
        const val ACTION_STOP = "com.peektts.STOP"
        const val ACTION_STATE_CHANGED = "com.peektts.STATE_CHANGED"
        const val ACTION_CONVERSATION_UPDATE = "com.peektts.CONVERSATION"
        const val ACTION_MODEL_STATUS = "com.peektts.MODEL_STATUS"
    }
}

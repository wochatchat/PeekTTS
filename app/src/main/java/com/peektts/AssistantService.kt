package com.peektts

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class AssistantService : Service() {

    private val tag = "AssistantService"
    private var engine: AssistantEngine? = null

    override fun onCreate() {
        super.onCreate()
        engine = AssistantEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
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
                null -> startAssistant()
            }
        } catch (e: Throwable) {
            Log.e(tag, "onStartCommand failed", e)
            broadcastState("error")
            broadcastModelStatus("启动失败: ${e.message ?: e.javaClass.simpleName}", -1)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_STICKY
    }

    private fun startAssistant() {
        // Android 14+ (API 34): foregroundServiceType=microphone 要求先持有 RECORD_AUDIO 运行时权限，
        // 否则 startForeground 会抛 SecurityException 导致 App 闪退。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                broadcastState("error")
                broadcastModelStatus("缺少麦克风权限,请授权后重试", -1)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
        }

        val notification = createNotification("语音助手运行中", "随时可以说话")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val modelManager = ModelManager(this)
        if (!modelManager.areModelsReady()) {
            broadcastState("error")
            broadcastModelStatus("请先下载模型", -1)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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

    fun broadcastModelStatus(status: String, progress: Int, detail: String = "") {
        val intent = Intent(ACTION_MODEL_STATUS)
        intent.putExtra("status", status)
        intent.putExtra("progress", progress)
        intent.putExtra("detail", detail)
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

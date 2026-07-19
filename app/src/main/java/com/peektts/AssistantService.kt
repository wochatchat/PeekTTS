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
        try {
            engine = AssistantEngine(this)
        } catch (t: Throwable) {
            CrashLogger.error(tag = "AssistantService", message = "AssistantEngine 构造失败（可能 native 库加载失败）", throwable = t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
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

            START_STICKY
        } catch (t: Throwable) {
            CrashLogger.error(tag = "AssistantService", message = "onStartCommand 抛异常, action=${intent?.action}", throwable = t)
            START_NOT_STICKY
        }
    }

    private fun startAssistant() {
        var foregroundStarted = false
        try {
            startForeground(NOTIFICATION_ID, createNotification("语音助手运行中", "随时可以说话"))
            foregroundStarted = true
        } catch (t: Throwable) {
            CrashLogger.error(tag = "AssistantService", message = "startForeground 失败 (foregroundServiceType=microphone, sdk=${android.os.Build.VERSION.SDK_INT}, permission 已在 manifest 声明)", throwable = t)
            broadcastState("error")
            openLogActivity()
            return
        }

        val modelManager = ModelManager(this)
        if (!modelManager.areModelsReady()) {
            CrashLogger.log("WARN", "AssistantService", "model not ready, please download models first")
            broadcastState("error")
            broadcastModelStatus("请先下载模型", -1)
            // 模型未就绪不是崩溃；但 stopForeground 以便释放通知
            if (foregroundStarted) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
            stopSelf()
            return
        }

        try {
            engine?.start()
        } catch (t: Throwable) {
            CrashLogger.error(tag = "AssistantService", message = "engine.start() 抛异常, 打开 LogActivity 给用户看堆栈", throwable = t)
            broadcastState("error")
            openLogActivity()
        }
    }

    /** 把 LogActivity 拉到前台让用户看崩溃日志 */
    private fun openLogActivity() {
        try {
            val it = Intent(this, LogActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(it)
        } catch (t: Throwable) {
            CrashLogger.error(tag = "AssistantService", message = "启动 LogActivity 也失败了", throwable = t)
        }
    }

    private fun stopAssistant() {
        try {
            engine?.stop()
        } catch (t: Throwable) {
            CrashLogger.error(tag = "AssistantService", message = "engine.stop() 抛异常", throwable = t)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        try {
            engine?.release()
        } catch (t: Throwable) {
            CrashLogger.error(tag = "AssistantService", message = "engine.release() 抛异常", throwable = t)
        }
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

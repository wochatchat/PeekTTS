package com.peektts

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 全局崩溃 / 日志收集器。
 *
 * - 在 Application.onCreate 注册 defaultUncaughtExceptionHandler，
 *   未捕获异常（包括 Service 内崩溃）会被写入内存缓冲 + 持久化文件，然后交给原 handler 让进程死亡。
 * - 业务代码可以通过 CrashLogger.log(...) 把 try/catch 抓到的异常 + 日志也写进来。
 * - LogActivity 拉取全部日志并展示，便于用户截图反馈。
 */
object CrashLogger {

    private const val MAX_ENTRIES = 800
    private const val LOG_FILE_NAME = "peektts_crash.log"

    private val entries = ConcurrentLinkedDeque<Entry>()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var appContext: Context? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var booted = false

    data class Entry(
        val time: String,
        val level: String,   // CRASH / ERROR / WARN / INFO
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    )

    @Synchronized
    fun install(context: Context) {
        if (booted) return
        appContext = context.applicationContext
        booted = true

        // 启动时把磁盘上保存的崩溃日志读回内存，让 LogActivity 可看历史
        loadPersistedLogs()

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                append(
                    level = "CRASH",
                    tag = "Uncaught",
                    message = "Uncaught exception on thread=${thread.name}",
                    throwable = throwable
                )
                persistNow()
            } catch (_: Throwable) {
                // 在崩溃处理里自己再崩就完了，吞掉
            } finally {
                // 交给系统默认处理（让进程死亡 + 弹"keeps stopping"）
                previousHandler?.uncaughtException(thread, throwable)
            }
        }

        log("INFO", "App", "CrashLogger installed, device=${Build.MANUFACTURER}/${Build.MODEL}, sdk=${Build.VERSION.SDK_INT}, process=${Process.myPid()}")
    }

    fun log(level: String = "INFO", tag: String = "App", message: String, throwable: Throwable? = null) {
        append(level, tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        append("ERROR", tag, message, throwable)
    }

    /** 获取全部日志（最早 -> 最新），用于 LogActivity 展示 */
    fun snapshot(): List<Entry> = entries.toList()

    fun clear() {
        entries.clear()
        appContext?.let { ctx ->
            runCatching { File(ctx.filesDir, LOG_FILE_NAME).delete() }
        }
    }

    @Synchronized
    private fun append(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val time = timeFormat.format(Date())
        entries.addLast(Entry(time, level, tag, message, throwable))
        // 限制条数防止无界增长
        while (entries.size > MAX_ENTRIES) entries.pollFirst()
        // crash / error 立即落盘，info 不落盘（太多）
        if (level == "CRASH" || level == "ERROR") {
            persistNow()
        }
    }

    @Synchronized
    private fun persistNow() {
        val ctx = appContext ?: return
        runCatching {
            val f = File(ctx.filesDir, LOG_FILE_NAME)
            f.bufferedWriter().use { w ->
                entries.forEach { e ->
                    w.appendLine(formatEntry(e))
                }
            }
        }
    }

    private fun loadPersistedLogs() {
        val ctx = appContext ?: return
        runCatching {
            val f = File(ctx.filesDir, LOG_FILE_NAME)
            if (!f.exists()) return@runCatching
            f.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    entries.addLast(parseEntry(line))
                }
            }
            while (entries.size > MAX_ENTRIES) entries.pollFirst()
        }
    }

    private fun formatEntry(e: Entry): String {
        val base = "[${e.time}] ${e.level}/${e.tag}: ${e.message}"
        return if (e.throwable != null) {
            val sw = StringWriter()
            e.throwable.printStackTrace(PrintWriter(sw))
            "$base\n${sw.toString().trimEnd()}"
        } else base
    }

    private fun parseEntry(line: String): Entry {
        // 简单解析：[时间] 级别/Tag: 消息  (后续 stack trace 行头加 \t)
        val regex = Regex("^\\[(.+?)\\]\\s+(\\w+)/(\\w+?):\\s+(.*)$")
        val m = regex.find(line)
        return if (m != null) {
            Entry(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
        } else {
            Entry("?", "STACK", "", line)
        }
    }

    /** 把 entry 渲染成单段多行文本，给 LogActivity 显示 */
    fun renderForDisplay(e: Entry): String = formatEntry(e)
}

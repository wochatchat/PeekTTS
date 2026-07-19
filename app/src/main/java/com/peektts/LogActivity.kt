package com.peektts

import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 把 CrashLogger 累积的所有日志 / 崩溃报告展示出来，便于截图反馈。
 *  - 顶部带「刷新 / 清空 / 复制 / 分享」按钮
 *  - 由于 Service 也走未捕获异常进入 CrashLogger，这里同样能看到 Service 内崩溃栈
 *  - 自动滚动到底部展示最新日志
 */
class LogActivity : AppCompatActivity() {

    private lateinit var tvContent: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnClear: Button
    private lateinit var btnCopy: Button
    private lateinit var btnShare: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        title = "PeekTTS 运行日志"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tvContent = findViewById(R.id.tvLogContent)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnClear = findViewById(R.id.btnClear)
        btnCopy = findViewById(R.id.btnCopy)
        btnShare = findViewById(R.id.btnShare)

        tvContent.movementMethod = ScrollingMovementMethod.getInstance()
        tvContent.textSize = 11f
        tvContent.typeface = android.graphics.Typeface.MONOSPACE

        render()

        btnRefresh.setOnClickListener { render() }
        btnClear.setOnClickListener {
            CrashLogger.clear()
            render()
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }
        btnCopy.setOnClickListener {
            val text = buildText()
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("PeekTTS log", text))
            Toast.makeText(this, "已复制到剪贴板（${text.length} 字）", Toast.LENGTH_SHORT).show()
        }
        btnShare.setOnClickListener {
            val text = buildText()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "PeekTTS 运行日志")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(send, "分享日志"))
        }
    }

    private fun buildText(): SpannableStringBuilder {
        val snap = CrashLogger.snapshot()
        val sb = SpannableStringBuilder()
        sb.append("=== PeekTTS 运行日志（共 ${snap.size} 条）===\n\n")
        snap.forEach { e ->
            val sectionStart = sb.length
            sb.append(CrashLogger.renderForDisplay(e))
            val sectionEnd = sb.length
            // 按级别染不同颜色，便于快速找到 CRASH/ERROR
            val color = when (e.level) {
                "CRASH" -> 0xFFFF6B6B.toInt()       // 亮红
                "ERROR" -> 0xFFFF9A4D.toInt()        // 橙
                "WARN" -> 0xFFFFD166.toInt()         // 黄
                "INFO" -> 0xFFEAEAEA.toInt()         // 浅白
                else -> 0xFFB6B6B6.toInt()
            }
            sb.setSpan(
                ForegroundColorSpan(color),
                sectionStart, sectionEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append("\n\n")
        }
        sb.append("=== END ===")
        return sb
    }

    private fun render() {
        tvContent.text = buildText()
        tvContent.post {
            tvContent.scrollTo(0, tvContent.height)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

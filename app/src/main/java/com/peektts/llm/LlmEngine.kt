package com.peektts.llm

import android.util.Log

/**
 * LlmEngine — 大语言模型推理引擎
 *
 * Phase 1 (当前): 简单规则回复 + 意图识别占位
 * Phase 2 (后续): 集成 llama.cpp + Qwen2.5-1.5B (GGUF Q4)
 *
 * 当前实现提供基本的对话能力，后续替换为本地 LLM 推理。
 */
class LlmEngine {

    private val tag = "LlmEngine"
    private val conversationHistory = mutableListOf<Pair<String, String>>()  // (role, content)

    // System prompt — 助手人设
    private val systemPrompt = """你是一个智能语音助手，名叫小Peek。
请用简洁的口语化方式回答用户的问题，每次回答不超过3句话。
如果用户的问题需要打开某个应用或执行某个操作，请在回答开头加上[ACTION:操作名]。"""

    /**
     * 生成回复
     *
     * Phase 1: 简单规则引擎，识别常见意图并返回预设回复
     * Phase 2: 将替换为 llama.cpp + Qwen2.5 本地推理
     */
    fun generateResponse(userInput: String): String {
        Log.i(tag, "Processing: $userInput")
        conversationHistory.add("user" to userInput)

        val response = ruleBasedResponse(userInput.trim())
        conversationHistory.add("assistant" to response)

        // Keep history limited
        if (conversationHistory.size > 20) {
            conversationHistory.subList(0, conversationHistory.size - 20).clear()
        }

        return response
    }

    /**
     * 规则引擎 — 常见意图识别
     * 后续替换为 LLM 推理
     */
    private fun ruleBasedResponse(input: String): String {
        val lower = input.lowercase()

        // 打开应用意图
        when {
            lower.contains("打开") || lower.contains("启动") || lower.contains("开") -> {
                val app = extractAppName(input)
                return if (app.isNotEmpty()) {
                    "[ACTION:open_app:$app] 好的，正在为你打开$app。"
                } else {
                    "你想打开什么应用呢？"
                }
            }
            lower.contains("几点") || lower.contains("时间") -> {
                val now = java.text.SimpleDateFormat("HH点mm分", java.util.Locale.CHINA)
                    .format(java.util.Date())
                return "现在是$now。"
            }
            lower.contains("日期") || lower.contains("今天几号") || lower.contains("星期") -> {
                val now = java.text.SimpleDateFormat("yyyy年M月d日 EEEE", java.util.Locale.CHINA)
                    .format(java.util.Date())
                return "今天是$now。"
            }
            lower.contains("天气") -> {
                return "[ACTION:weather] 我需要联网查看天气，正在为你打开天气页面。"
            }
            lower.contains("设闹钟") || lower.contains("闹钟") || lower.contains("提醒") -> {
                return "[ACTION:alarm] 好的，我来帮你设置闹钟。"
            }
            lower.contains("你好") || lower.contains("嗨") || lower.contains("hi") || lower.contains("hello") -> {
                return "你好！我是小Peek，有什么可以帮你的吗？"
            }
            lower.contains("谢谢") || lower.contains("感谢") -> {
                return "不客气，随时为你服务！"
            }
            lower.contains("再见") || lower.contains("拜拜") -> {
                return "再见！随时叫我。"
            }
            lower.contains("你是谁") || lower.contains("你叫什么") -> {
                return "我是小Peek，你的语音助手。我可以帮你打开应用、查看时间、设置闹钟等等。"
            }
            lower.contains("能做什么") || lower.contains("功能") -> {
                return "我可以帮你打开应用、查看时间日期、设置闹钟、查天气，以及陪你聊天。直接对我说话就行，不需要说唤醒词。"
            }
        }

        // 默认回复
        val defaultReplies = listOf(
            "我听到了你说：$input。这个问题我还需要学习一下。",
            "嗯，让我想想... 你说的「$input」，我目前还不太理解，换个说法试试？",
            "收到！关于「$input」，我正在学习中，暂时还不能完美回答。",
        )
        return defaultReplies.random()
    }

    private fun extractAppName(input: String): String {
        val apps = listOf("微信", "QQ", "支付宝", "淘宝", "京东", "抖音", "快手",
            "浏览器", "相机", "电话", "短信", "设置", "音乐", "视频",
            "地图", "日历", "计算器", "时钟", "备忘录", "邮件")
        for (app in apps) {
            if (input.contains(app)) return app
        }
        return ""
    }

    fun clearHistory() {
        conversationHistory.clear()
    }
}

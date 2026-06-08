package com.kane.dispatchlistener

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LineAccessibilityService : AccessibilityService() {

    private val seenMessages = mutableMapOf<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingCheck = false

    // 快取「目前是否在目標群組畫面」，避免在其他群組時誤抓
    private var inTargetGroup = false

    override fun onServiceConnected() {
        Log.d("LineA11y", "無障礙服務已連線")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != "jp.naver.line.android") return

        when (event.eventType) {
            // 切換畫面時更新 inTargetGroup 旗標
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                mainHandler.post {
                    val root = rootInActiveWindow
                    inTargetGroup = if (root != null) {
                        isInTargetGroup(root).also { root.recycle() }
                    } else false
                    Log.d("LineA11y", "畫面切換，inTargetGroup=$inTargetGroup")
                    if (inTargetGroup) markExistingAsRead()
                }
            }
            // 畫面內容變化：只在目標群組內才掃描
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!inTargetGroup) return
                if (!pendingCheck) {
                    pendingCheck = true
                    mainHandler.postDelayed({
                        pendingCheck = false
                        checkForNewOrders()
                    }, 500)
                }
            }
        }
    }

    private fun markExistingAsRead() {
        val root = rootInActiveWindow ?: return
        try {
            val now = System.currentTimeMillis()
            findDispatchTexts(root).forEach { text -> seenMessages[text] = now }
            Log.d("LineA11y", "標記現有訊息 ${seenMessages.size} 筆為已讀")
        } finally {
            root.recycle()
        }
    }

    private fun checkForNewOrders() {
        val root = rootInActiveWindow ?: return
        try {
            // 再次確認（防止畫面已切走）
            if (!isInTargetGroup(root)) {
                inTargetGroup = false
                return
            }

            val now = System.currentTimeMillis()
            val newTexts = findDispatchTexts(root).filter { text ->
                val lastSeen = seenMessages[text] ?: 0L
                now - lastSeen > 30_000
            }

            for (text in newTexts) {
                seenMessages[text] = now
                Log.d("LineA11y", "偵測到新派單（無障礙）：$text")
                OrderQueue.add(text)
            }

            seenMessages.entries.removeAll { now - it.value > 120_000 }
        } finally {
            root.recycle()
        }
    }

    private fun isInTargetGroup(root: AccessibilityNodeInfo): Boolean {
        // 用完整群組名稱比對，比只比對「調度室」更精確，避免在其他 LINE 畫面誤判
        val target = LineNotificationService.targetGroupName
        var nodes = root.findAccessibilityNodeInfosByText(target)
        if (nodes.isNotEmpty()) {
            nodes.forEach { it.recycle() }
            return true
        }
        // 備用：比對較短但仍具唯一性的片段（群組名稱中文部分）
        nodes = root.findAccessibilityNodeInfosByText("海口-")
        val found = nodes.isNotEmpty()
        nodes.forEach { it.recycle() }
        return found
    }

    private fun findDispatchTexts(root: AccessibilityNodeInfo): List<String> {
        val results = mutableListOf<String>()
        val excludes = LineNotificationService.excludeKeywords

        // 策略一：地區關鍵字命中
        for (keyword in LineNotificationService.keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                val text = node.text?.toString()?.trim() ?: ""
                node.recycle()
                if (text.isNotBlank() && text.contains("/") && parseAddress(text) != null
                    && excludes.none { text.contains(it) }) {
                    results.add(text)
                }
            }
        }

        // 策略二：派單格式命中（含「/」+能解析地址），補抓沒有地區關鍵字的單
        val slashNodes = root.findAccessibilityNodeInfosByText("/")
        for (node in slashNodes) {
            val text = node.text?.toString()?.trim() ?: ""
            node.recycle()
            if (text.isNotBlank() && text.contains("/") && parseAddress(text) != null
                && excludes.none { text.contains(it) }) {
                results.add(text)
            }
        }

        // 相同地址只保留最短那筆
        return results.groupBy { parseAddress(it) ?: it }
            .values
            .map { group -> group.minByOrNull { it.length }!! }
    }

    override fun onInterrupt() {}
}

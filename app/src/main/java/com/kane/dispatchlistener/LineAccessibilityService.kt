package com.kane.dispatchlistener

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LineAccessibilityService : AccessibilityService() {

    // key = 訊息文字, value = 最後處理的 timestamp
    private val seenMessages = mutableMapOf<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingCheck = false

    override fun onServiceConnected() {
        Log.d("LineA11y", "無障礙服務已連線")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != "jp.naver.line.android") return

        when (event.eventType) {
            // 切換畫面（進入新群組）：先標記畫面上現有訊息為「已看過」，避免舊單重複加入
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                mainHandler.post { markExistingAsRead() }
            }
            // 畫面內容變化（新訊息出現）：debounce 500ms 後掃描
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
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
            findDispatchTexts(root).forEach { text ->
                seenMessages[text] = now
            }
            Log.d("LineA11y", "標記現有訊息 ${seenMessages.size} 筆為已讀")
        } finally {
            root.recycle()
        }
    }

    private fun checkForNewOrders() {
        val root = rootInActiveWindow ?: return
        try {
            if (!isInTargetGroup(root)) return

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

            // 清理超過 2 分鐘的記錄
            seenMessages.entries.removeAll { now - it.value > 120_000 }
        } finally {
            root.recycle()
        }
    }

    private fun isInTargetGroup(root: AccessibilityNodeInfo): Boolean {
        // LINE 標題列會顯示群組名稱，找到"調度室"代表在派車群組內
        val nodes = root.findAccessibilityNodeInfosByText("調度室")
        val found = nodes.isNotEmpty()
        nodes.forEach { it.recycle() }
        return found
    }

    private fun findDispatchTexts(root: AccessibilityNodeInfo): List<String> {
        val results = mutableListOf<String>()
        val keywords = LineNotificationService.keywords

        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                val text = node.text?.toString()?.trim() ?: ""
                node.recycle()
                // 必須含「/」（派單格式），且 parseAddress 能解析出地址（非隨意聊天）
                if (text.isNotBlank() && text.contains("/") && parseAddress(text) != null) {
                    results.add(text)
                }
            }
        }
        return results.distinct()
    }

    override fun onInterrupt() {}
}

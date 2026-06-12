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

    // 進入群組時的貼圖/分隔線數量；數量增加才觸發清除，避免舊貼圖誤觸
    private var knownStickerCount = 0

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
                // 記錄事件來源節點（幫助識別動態貼圖的 accessibility 文字）
                val src = event.source
                if (src != null) {
                    val t = src.text?.toString()?.trim() ?: ""
                    val d = src.contentDescription?.toString()?.trim() ?: ""
                    if (t.isNotBlank() || d.isNotBlank()) {
                        Log.d("LineA11y", "事件來源 text='$t' desc='$d' cls=${src.className}")
                    }
                    src.recycle()
                }
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

            // 計算進群時畫面上有幾個貼圖/分隔線；後續偵測以此為基準，數量增加才算新的
            val stickerNodes = root.findAccessibilityNodeInfosByText("貼圖")
            val dividerNodes = root.findAccessibilityNodeInfosByText("分隔線")
            knownStickerCount = stickerNodes.size + dividerNodes.size
            stickerNodes.forEach { it.recycle() }
            dividerNodes.forEach { it.recycle() }

            // 把通知服務目前的貼圖時間一併記入，防止跨服務同步在進群後立即誤觸
            seenMessages["§sticker"] = maxOf(OrderQueue.lastStickerTime, now)

            Log.d("LineA11y", "標記現有訊息 ${seenMessages.size} 筆，進群時貼圖/分隔數=$knownStickerCount")
        } finally {
            root.recycle()
        }
    }

    private fun checkForNewOrders() {
        val root = rootInActiveWindow ?: return
        try {
            if (!isInTargetGroup(root)) {
                inTargetGroup = false
                return
            }

            val now = System.currentTimeMillis()

            // 跨服務同步：通知路徑偵測到分隔貼圖時，無障礙服務的 seenMessages 也要清除
            val notifStickerTime = OrderQueue.lastStickerTime
            val a11yStickerTime = seenMessages["§sticker"] ?: 0L
            if (notifStickerTime > a11yStickerTime) {
                seenMessages.entries.removeAll { it.key != "§sticker" }
                seenMessages["§sticker"] = notifStickerTime
                Log.d("LineA11y", "收到通知路徑分隔訊號，清除無障礙已讀記錄")
            }

            // 分隔線貼圖偵測（使用者在 LINE 內，通知可能不會送出）
            if (detectStickerAndClear(root, now)) {
                seenMessages.entries.removeAll { now - it.value > 120_000 }
            }

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

    // 在無障礙樹中尋找分隔線貼圖；比進群時「多出來」的才算新的，立即清空並回傳 true
    private fun detectStickerAndClear(root: AccessibilityNodeInfo, now: Long): Boolean {
        val stickerKey = "§sticker"
        // "貼圖" 已涵蓋 "動態貼圖"（子字串匹配）；"Sticker" 補抓英文標籤
        val searches = listOf("貼圖", "Sticker")
        val allNodes = searches.flatMap { root.findAccessibilityNodeInfosByText(it) }
        val dividerNodes = root.findAccessibilityNodeInfosByText("分隔線")
        val currentCount = allNodes.size + dividerNodes.size
        allNodes.forEach { it.recycle() }
        dividerNodes.forEach { it.recycle() }

        if (currentCount == 0) {
            // 貼圖全數滾出畫面 → 重置基準，確保下一個新貼圖能被偵測
            if (knownStickerCount > 0) {
                knownStickerCount = 0
                Log.d("LineA11y", "貼圖滾出畫面，重置基準為 0")
            }
            return false
        }
        // 數量減少（部分舊貼圖滾出視野）→ 同步下調基準
        if (currentCount < knownStickerCount) {
            knownStickerCount = currentCount
            Log.d("LineA11y", "貼圖基準下調至 $currentCount")
            return false
        }
        // 數量未增加 → 是進群前就存在的舊貼圖，不觸發
        if (currentCount <= knownStickerCount) return false

        // 2 秒防抖：防止 LINE 對同一則貼圖多次重繪 UI 導致重複觸發
        val lastSeen = seenMessages[stickerKey] ?: 0L
        if (now - lastSeen < 2_000) return false

        knownStickerCount = currentCount  // 更新基準，避免連續貼圖互相干擾
        seenMessages[stickerKey] = now
        seenMessages.entries.removeAll { it.key != stickerKey }
        val nm = getSystemService(android.app.NotificationManager::class.java)
        OrderQueue.orders.value.forEach { nm?.cancel(it.raw.hashCode()) }
        OrderQueue.clear()
        OrderQueue.lastStickerTime = now  // 同步給通知服務路徑
        Log.d("LineA11y", "偵測到新分隔（貼圖或分隔線），currentCount=$currentCount，清除所有訂單與已讀記錄")
        return true
    }

    // 收集無障礙樹中所有非空 text / contentDescription（限數量以免 log 爆炸）
    private fun collectAllNodeTexts(node: AccessibilityNodeInfo, max: Int): Set<String> {
        val results = mutableSetOf<String>()
        fun traverse(n: AccessibilityNodeInfo) {
            if (results.size >= max) return
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            if (!t.isNullOrBlank()) results.add("T:$t")
            if (!d.isNullOrBlank() && d != t) results.add("D:$d")
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                traverse(child)
                child.recycle()
            }
        }
        traverse(node)
        return results
    }

    private fun isInTargetGroup(root: AccessibilityNodeInfo): Boolean {
        val name = LineNotificationService.targetGroupName
        // 優先用視窗標題（最可靠，不受訊息文字干擾）
        for (window in windows) {
            val title = window.title?.toString()
            window.recycle()
            if (title != null && title.contains(name)) return true
        }
        // 備用：LINE 若未提供視窗標題，改在無障礙樹搜尋群組名稱
        val nodes = root.findAccessibilityNodeInfosByText(name)
        val found = nodes.isNotEmpty()
        nodes.forEach { it.recycle() }
        return found
    }

    private fun findDispatchTexts(root: AccessibilityNodeInfo): List<String> {
        val results = mutableListOf<String>()
        val excludes = LineNotificationService.excludeKeywords

        // 策略一：地區關鍵字命中（同時要求含派單符號，避免「收北區」等非派單訊息被抓）
        for (keyword in LineNotificationService.keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                val editable = node.isEditable
                val text = node.text?.toString()?.trim() ?: ""
                node.recycle()
                if (editable) continue  // 過濾 LINE 輸入框（未送出的草稿）
                val hasDispatchMarker = text.contains("♒️") || text.contains("🏵️")
                if (!hasDispatchMarker) continue
                // 策略一不要求含「/」：LINE 有時把訊息切成子節點，關鍵字節點的 text 可能只有地名片段
                if (text.isNotBlank() && parseAddress(text) != null
                    && excludes.none { text.contains(it) } && !isArrivalReport(text)
                    && !hasPlateNumber(text)) {
                    results.add(text)
                }
            }
        }

        // 策略二：派單格式命中（含「/」+能解析地址+派單符號），補抓沒有地區關鍵字的單
        val slashNodes = root.findAccessibilityNodeInfosByText("/")
        for (node in slashNodes) {
            val editable = node.isEditable
            val text = node.text?.toString()?.trim() ?: ""
            node.recycle()
            if (editable) continue  // 過濾 LINE 輸入框
            val hasDispatchMarker = text.contains("♒️") || text.contains("🏵️")
            if (!hasDispatchMarker) continue
            if (text.isNotBlank() && text.contains("/") && parseAddress(text) != null
                && excludes.none { text.contains(it) } && !isArrivalReport(text)
                && !hasPlateNumber(text)) {
                results.add(text)
            }
        }

        // 相同地址只保留最短那筆
        return results.groupBy { parseAddress(it) ?: it }
            .values
            .map { group -> group.minByOrNull { it.length }!! }
    }

    // 司機回報「/到」模式：最後一個欄位是純到達確認，不是新派單
    private fun isArrivalReport(text: String): Boolean {
        val lastField = text.split("/").lastOrNull()?.trim() ?: return false
        return lastField == "到" || lastField == "到了" || lastField == "已到" || lastField == "抵達"
    }

    override fun onInterrupt() {}
}

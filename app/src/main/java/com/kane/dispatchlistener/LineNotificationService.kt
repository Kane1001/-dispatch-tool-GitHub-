package com.kane.dispatchlistener

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class LineNotificationService : NotificationListenerService() {

    companion object {
        const val LINE_PACKAGE = "jp.naver.line.android"
        const val CHANNEL_ID = "dispatch_alert"
        const val CHANNEL_NAME = "派車警示"

        var targetGroupName = "♒️ʜᴄ海口-𝟔月調度室🏎️"
        var keywords = mutableListOf("西屯", "北屯", "南屯", "中區", "東區", "西區", "南區", "北區", "大里", "太平", "豐原", "高鐵", "彰化", "南投", "草屯", "伸港", "苗栗", "沙鹿", "清水", "大甲", "烏日", "霧峰", "龍井", "梧棲", "潭子", "大雅", "后里", "神岡", "大肚", "外埔", "大安", "東勢")
        var excludeKeywords = mutableListOf("出發", "已接", "到了", "已到", "抵達", "客上", "⬆️", "🔼", "↑", "小宇")
        var allowedSenders = mutableListOf("𝓡𝓪𝓽𝓽𝓲✨")
    }

    // 防止重複處理：5 分鐘 TTL（LINE 同時多則訊息時 textLines 裡會有舊訊息）
    private val processedTexts = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != LINE_PACKAGE) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        Log.d("LineNotify", "title=$title | text=$text")

        // LINE 批次派單時，中間幾筆通知 title 只帶發送者名字（不帶群組名）
        // 也要允許已知調度員直發的訊息（title = 調度員名稱）
        val fromGroup = title.contains(targetGroupName)
        val fromDispatcher = !title.contains("：") && run {
            val t = title.replace(" ", "")
            t.contains("調度") || allowedSenders.any { t.contains(it.replace(" ", ""), ignoreCase = true) }
        }
        if (!fromGroup && !fromDispatcher) return

        val chatId = extras.getString("line.chat.id")
        val now = System.currentTimeMillis()
        processedTexts.entries.removeAll { now - it.value > 300_000 }

        // LINE 同時收多則訊息時，可能以單一通知送出，textLines 含各別訊息內容
        // 格式：["發送者: 訊息內容", "發送者: 訊息內容", ...]
        val textLines = extras.getCharSequenceArray("android.textLines")

        if (textLines != null && textLines.size > 1) {
            Log.d("LineNotify", "多訊息通知，共 ${textLines.size} 行")

            // 群組已驗證，不再過濾發送者——由後段 hasPlateNumber/excludeKeywords/keyword 過濾內容
            // 格式："發送者: 內容"（英文冒號+空格）或 "發送者：內容"（中文全形冒號）
            val messages = textLines.mapNotNull { line ->
                val s = line.toString().trim()
                if (s.isBlank()) return@mapNotNull null
                val enIdx = s.indexOf(": ")
                val cnIdx = s.indexOf("：")
                when {
                    enIdx > 0 -> s.substring(enIdx + 2)
                    cnIdx > 0 -> s.substring(cnIdx + 1)
                    else -> s  // 若無冒號分隔符，直接使用整行內容
                }
            }

            // 步驟一：若有分隔貼圖，先清空（避免新單加完後被清掉）
            val hasSticker = messages.any { t ->
                t.contains("貼圖") || t.contains("Sticker", ignoreCase = true) ||
                        t.contains("分隔線") || t.contains("(emoji)")
            }
            if (hasSticker) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                OrderQueue.orders.value.forEach { nm.cancel(it.raw.hashCode()) }
                OrderQueue.clear()
                processedTexts.clear()
                OrderQueue.lastStickerTime = now  // 通知無障礙服務同步清除
                Log.d("LineNotify", "偵測到分隔貼圖（多訊息），清除所有訂單")
            }

            // 步驟二：加入非貼圖訊息
            for (msgText in messages) {
                if (msgText.contains("貼圖") || msgText.contains("Sticker", ignoreCase = true) || msgText.contains("(emoji)")) continue
                processMessageText(msgText, title, chatId, sbn)
            }
        } else {
            // 單訊息通知
            // 已從目標群組通過驗證（fromGroup = true）時不再過濾發送者，由 hasPlateNumber/excludeKeywords 過濾內容
            // 非群組訊息（fromDispatcher 路徑）才需驗證發送者
            if (!fromGroup) {
                val sender = title.substringAfterLast("：")
                val senderNorm = sender.replace(" ", "")
                val senderAllowed = sender.contains("調度") ||
                    allowedSenders.any { senderNorm.contains(it.replace(" ", ""), ignoreCase = true) }
                if (!senderAllowed) return
            }
            processMessageText(text, title, chatId, sbn)
        }
    }

    private fun processMessageText(text: String, title: String, chatId: String?, sbn: StatusBarNotification) {
        val now = System.currentTimeMillis()

        // 貼圖偵測優先：不受 processedTexts TTL 限制，確保每次貼圖都能清除
        // LINE 自訂表情貼（emoji store）在通知裡顯示成 "(emoji)" 佔位符
        val isSticker = text.contains("貼圖") || text.contains("Sticker", ignoreCase = true) ||
                text.contains("分隔線") || text.contains("(emoji)")
        if (isSticker) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            OrderQueue.orders.value.forEach { nm.cancel(it.raw.hashCode()) }
            OrderQueue.clear()
            processedTexts.clear()
            OrderQueue.lastStickerTime = now  // 通知無障礙服務同步清除
            Log.d("LineNotify", "偵測到分隔貼圖，清除所有訂單")
            return
        }

        // LINE 有時把近幾則訊息（派單＋他人回覆）捆成一個通知送出
        // 若 text 為多行且其中一行含派單符號，只取那行；但同時檢查其他行有無「已被接走」信號
        val dispatchLine = if (text.contains("\n"))
            text.lines().firstOrNull { it.contains("♒️") || it.contains("🏵️") }
        else null
        val effectiveText = dispatchLine ?: text

        // 多行捆包：若其他行含車牌或接客關鍵字，代表此單已被接走，跳過
        if (dispatchLine != null) {
            val otherLines = text.lines().filter { !it.contains("♒️") && !it.contains("🏵️") }
            val platePattern = Regex("""^\d{4}[^\d/]""")
            val alreadyTaken = otherLines.any { line ->
                platePattern.containsMatchIn(line.trim()) ||
                excludeKeywords.any { kw -> line.contains(kw) }
            }
            if (alreadyTaken) {
                Log.d("LineNotify", "捆包通知：其他行顯示此單已被接走，跳過")
                return
            }
        }

        // 5 分鐘內相同訂單文字不重複處理（只對一般訊息生效）
        val lastSeen = processedTexts[effectiveText] ?: 0L
        if (now - lastSeen < 300_000) return
        processedTexts[effectiveText] = now

        if (excludeKeywords.any { effectiveText.contains(it) }) return
        if (hasPlateNumber(effectiveText)) return

        val lastField = effectiveText.split("/").lastOrNull()?.trim() ?: ""
        if (lastField == "到" || lastField == "到了" || lastField == "已到" || lastField == "抵達") return

        // 派單格式必須含有派單符號（♒️/🏵️），避免非派單訊息被誤抓
        val hasDispatchMarker = effectiveText.contains("♒️") || effectiveText.contains("🏵️")
        if (!hasDispatchMarker) return

        val matched = keywords.any { effectiveText.contains(it) } ||
                      (effectiveText.contains("/") && parseAddress(effectiveText) != null)
        if (!matched) return

        Log.d("LineNotify", "派單加入隊列：$effectiveText")
        OrderQueue.add(effectiveText, chatId, sbn.notification.contentIntent)
        showAlert(title, effectiveText)
    }

    private fun showAlert(title: String, text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚗 有單！$title")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(text.hashCode(), notification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}

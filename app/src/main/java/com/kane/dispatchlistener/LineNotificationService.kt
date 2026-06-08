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

        // 你要監聽的群組關鍵字（可在 MainActivity 設定）
        var targetGroupName = "♒️ʜᴄ海口-𝟔月調度室🏎️"

        // 觸發通知的關鍵字（符合任一個就通知）
        var keywords = mutableListOf("西屯", "北屯", "南屯", "大里", "太平", "豐原", "高鐵", "彰化", "南投", "草屯", "伸港", "苗栗", "沙鹿", "清水", "大甲")

        // 排除關鍵字（含有這些字就不抓，例如司機回覆「出發」或接單符號）
        var excludeKeywords = mutableListOf("出發", "已接", "到了", "已到", "抵達", "客上", "⬆️", "🔼", "↑")

        // 額外允許的發送者帳號（不含「調度」但仍要抓的帳號，空格會被忽略）
        var allowedSenders = mutableListOf("RATTI")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != LINE_PACKAGE) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: return
        val text = extras.getCharSequence("android.text")?.toString() ?: return

        Log.d("LineNotify", "title=$title | text=$text")

        // 檢查是否來自目標群組
        if (!title.contains(targetGroupName)) return

        // 檢查是否為調度發送（名稱含「調度」，或在白名單內）
        val sender = title.substringAfterLast("：")
        val senderNorm = sender.replace(" ", "")
        val senderAllowed = sender.contains("調度") ||
            allowedSenders.any { senderNorm.contains(it.replace(" ", ""), ignoreCase = true) }
        if (!senderAllowed) return

        // 調度員丟貼圖 = 分隔線，本輪派車結束 → 清空所有舊單
        val isSticker = text.contains("貼圖") || text.contains("Sticker", ignoreCase = true)
        if (isSticker) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            OrderQueue.orders.value.forEach { nm.cancel(it.raw.hashCode()) }
            OrderQueue.clear()
            Log.d("LineNotify", "偵測到分隔貼圖，清除所有訂單")
            return
        }

        // 排除回覆類訊息（如司機出發回覆）
        if (excludeKeywords.any { text.contains(it) }) return

        // 有地區關鍵字，或訊息格式像派單（含 / 且能解析出地址）
        val matched = keywords.any { text.contains(it) } ||
                      (text.contains("/") && parseAddress(text) != null)
        if (!matched) return

        // 加入訂單列並觸發通知
        val chatId = extras.getString("line.chat.id")
        OrderQueue.add(text, chatId, sbn.notification.contentIntent)
        showAlert(title, text)
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
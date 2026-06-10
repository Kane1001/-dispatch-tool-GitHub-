package com.kane.dispatchlistener

import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

enum class OrderStatus { CALCULATING, DONE, FAILED }

data class OrderItem(
    val id: Long,
    val raw: String,
    val address: String?,
    val reserveTime: String?,
    val chatId: String? = null,
    val contentIntent: PendingIntent? = null,
    val minutes: Int? = null,
    val distance: String? = null,
    val status: OrderStatus = OrderStatus.CALCULATING,
    val report: String = "",
    val resolvedDest: String? = null
)

object OrderQueue {
    private val _orders = MutableStateFlow<List<OrderItem>>(emptyList())
    val orders = _orders.asStateFlow()

    // 通知路徑偵測到分隔貼圖時更新，讓無障礙服務同步清除 seenMessages
    @Volatile var lastStickerTime: Long = 0L

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun add(raw: String, chatId: String? = null, contentIntent: PendingIntent? = null) {
        val now = System.currentTimeMillis()
        val parsedAddr = parseAddress(raw)
        if (_orders.value.any { it.raw == raw }) return
        val item = OrderItem(
            id = now,
            raw = raw,
            address = parsedAddr,
            reserveTime = parseReserveTime(raw),
            chatId = chatId,
            contentIntent = contentIntent
        )
        _orders.value = _orders.value + item
        persist()
    }

    fun update(id: Long, minutes: Int?, status: OrderStatus, report: String, resolvedDest: String? = null, distance: String? = null) {
        _orders.value = _orders.value.map {
            if (it.id == id) it.copy(minutes = minutes, distance = distance, status = status, report = report, resolvedDest = resolvedDest)
            else it
        }
    }

    fun remove(id: Long) {
        _orders.value = _orders.value.filter { it.id != id }
        persist()
    }

    fun clear() {
        _orders.value = emptyList()
        persist()
    }

    fun restore(context: Context) {
        appContext = context.applicationContext
        val str = context.getSharedPreferences("dispatch_queue", Context.MODE_PRIVATE)
            .getString("orders", null) ?: return
        val arr = try { JSONArray(str) } catch (e: Exception) { return }
        val raws = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        if (raws.isEmpty()) return
        val base = System.currentTimeMillis()
        _orders.value = raws.mapIndexed { i, raw ->
            OrderItem(
                id = base - (raws.size - i) * 1000L,
                raw = raw,
                address = parseAddress(raw),
                reserveTime = parseReserveTime(raw)
            )
        }
        android.util.Log.d("OrderQueue", "還原 ${raws.size} 筆訂單")
    }

    private fun persist() {
        val ctx = appContext ?: return
        val arr = JSONArray()
        _orders.value.forEach { arr.put(it.raw) }
        ctx.getSharedPreferences("dispatch_queue", Context.MODE_PRIVATE)
            .edit().putString("orders", arr.toString()).apply()
    }
}

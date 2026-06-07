package com.kane.dispatchlistener

import android.app.PendingIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OrderStatus { CALCULATING, DONE, FAILED }

data class OrderItem(
    val id: Long,
    val raw: String,
    val address: String?,
    val reserveTime: String?,
    val chatId: String? = null,
    val contentIntent: PendingIntent? = null,
    val minutes: Int? = null,
    val status: OrderStatus = OrderStatus.CALCULATING,
    val report: String = "",
    val resolvedDest: String? = null
)

object OrderQueue {
    private val _orders = MutableStateFlow<List<OrderItem>>(emptyList())
    val orders = _orders.asStateFlow()

    fun add(raw: String, chatId: String? = null, contentIntent: PendingIntent? = null) {
        val now = System.currentTimeMillis()
        val parsedAddr = parseAddress(raw)
        // 相同文字 → 跳過
        if (_orders.value.any { it.raw == raw }) return
        // 相同地址（格式差異導致文字略不同的同一張單）→ 跳過
        if (parsedAddr != null && _orders.value.any { it.address == parsedAddr }) return
        val item = OrderItem(
            id = now,
            raw = raw,
            address = parsedAddr,
            reserveTime = parseReserveTime(raw),
            chatId = chatId,
            contentIntent = contentIntent
        )
        _orders.value = _orders.value + item
    }

    fun update(id: Long, minutes: Int?, status: OrderStatus, report: String, resolvedDest: String? = null) {
        _orders.value = _orders.value.map {
            if (it.id == id) it.copy(minutes = minutes, status = status, report = report, resolvedDest = resolvedDest)
            else it
        }
    }

    fun remove(id: Long) {
        _orders.value = _orders.value.filter { it.id != id }
    }

    fun clear() {
        _orders.value = emptyList()
    }
}

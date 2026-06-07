package com.kane.dispatchlistener

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.kane.dispatchlistener.ui.theme.DispatchListenerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume

const val MAPS_API_KEY = "AIzaSyBV2J4zwqtWexO3gFg2UAEDL6YuSauSHm0"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DispatchListenerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

fun parseAddress(orderText: String): String? {
    val line = orderText.trim().split("\n").firstOrNull() ?: return null
    val parts = line.split("/").map { it.trim() }.filter { it.isNotEmpty() }
    val addrPattern = Regex("[區市鄉鎮村里].*[路街道巷弄]|[路街道巷弄].*[0-9０-９]+號")
    val pureNumber = Regex("^[^\\u4e00-\\u9fff a-zA-Z]+$")
    val numberSuffix = Regex("^[^\\u4e00-\\u9fff]+\\d+$")

    for (p in parts) {
        if (pureNumber.containsMatchIn(p)) continue
        if (numberSuffix.matches(p)) continue
        if (addrPattern.containsMatchIn(p)) return p
    }
    for (p in parts) {
        if (pureNumber.containsMatchIn(p)) continue
        if (numberSuffix.matches(p)) continue
        if (!Regex("[\\u4e00-\\u9fffa-zA-Z]").containsMatchIn(p)) continue
        if (p.length < 2) continue
        return p
    }
    return null
}

fun parseReserveTime(orderText: String): String? {
    val timeRegex = Regex("(\\d{1,2}):(\\d{2})")
    val line = orderText.trim().split("\n").firstOrNull() ?: return null
    val parts = line.split("/")
    return parts.firstOrNull { timeRegex.containsMatchIn(it) && !it.contains("區") && !it.contains("路") }
        ?.let { timeRegex.find(it)?.value }
}

// 非台中縣市的關鍵字 → 縣市前綴（派單第一欄的地區名）
private val outOfTcAreaMap = mapOf(
    "彰化" to "彰化縣",
    "伸港" to "彰化縣伸港鄉",
    "南投" to "南投縣",
    "草屯" to "南投縣草屯鎮",
    "苗栗" to "苗栗縣"
)

fun buildDestination(address: String, raw: String = ""): String {
    val tcDistricts = listOf("西屯","南屯","北屯","豐原","大里","太平","大甲","清水","沙鹿","梧棲",
        "后里","神岡","潭子","大雅","新社","石岡","東勢","和平","烏日","大肚","龍井","霧峰",
        "外埔","大安","中區","東區","西區","南區","北區")

    // 地址本身已含縣市資訊，直接用
    if (address.contains("市") || address.contains("縣")) return address

    // 從訂單第一欄（地區）判斷是否為非台中縣市
    val areaHint = raw.split("/").firstOrNull()?.trim() ?: ""
    for ((keyword, prefix) in outOfTcAreaMap) {
        if (areaHint.contains(keyword) || address.contains(keyword)) {
            val dest = if (address.contains(Regex("[路街道巷弄]"))) "$prefix$address"
                       else "$prefix $address"
            android.util.Log.d("DispatchDest", "非台中地區：$dest（原始：$address）")
            return dest
        }
    }

    // 台中各區（中區/東區/西區/南區/北區本身已有「區」字，不重複補）
    for (d in tcDistricts) {
        if (address.startsWith(d) && !address.startsWith(d + "區")) {
            val districtFull = if (d.endsWith("區")) "台中市$d" else "台中市${d}區"
            val dest = "$districtFull${address.drop(d.length)}"
            android.util.Log.d("DispatchDest", "台中地區：$dest（原始：$address）")
            return dest
        }
    }

    val dest = if (address.contains(Regex("[路街道巷弄]"))) "台中市$address" else "$address 台中"
    android.util.Log.d("DispatchDest", "預設台中：$dest（原始：$address）")
    return dest
}

fun convertChineseNum(str: String): String {
    val map = mapOf("一" to 1,"二" to 2,"三" to 3,"四" to 4,"五" to 5,
        "六" to 6,"七" to 7,"八" to 8,"九" to 9,"十" to 10,
        "十一" to 11,"十二" to 12,"十三" to 13,"十四" to 14,"十五" to 15,
        "十六" to 16,"十七" to 17,"十八" to 18,"十九" to 19,"二十" to 20)
    var result = str
    for ((k, v) in map.entries.sortedByDescending { it.key.length }) {
        result = result.replace("${k}巷", "${v}巷")
            .replace("${k}弄", "${v}弄")
            .replace("${k}號", "${v}號")
    }
    return result
}

suspend fun getRouteMinutes(originLat: Double, originLon: Double, destination: String, raw: String = ""): Int? {
    return withContext(Dispatchers.IO) {
        try {
            val dest = convertChineseNum(buildDestination(destination, raw))
            val url = "https://maps.googleapis.com/maps/api/distancematrix/json" +
                    "?origins=${encode("$originLat,$originLon")}" +
                    "&destinations=${encode(dest)}" +
                    "&mode=driving" +
                    "&language=zh-TW" +
                    "&key=$MAPS_API_KEY"
            val response = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string() ?: return@withContext null
            val element = JSONObject(body)
                .getJSONArray("rows").getJSONObject(0)
                .getJSONArray("elements").getJSONObject(0)
            if (element.getString("status") != "OK") return@withContext null
            val seconds = element.getJSONObject("duration").getInt("value")
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val buffer = if ((hour in 7..8) || (hour in 17..18)) 2 else 0
            (seconds / 60) + buffer
        } catch (e: Exception) {
            android.util.Log.e("DispatchAPI", "error: ${e.message}", e)
            null
        }
    }
}

fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

fun buildReport(orderText: String, mins: Int?, reserveTime: String?): String {
    val firstLine = orderText.trim()
    val line3 = when {
        mins == null -> "報單"
        reserveTime != null -> {
            val now = java.util.Calendar.getInstance()
            val arrive = java.util.Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis() + mins * 60 * 1000L
            }
            val (rh, rm) = reserveTime.split(":").map { it.toInt() }
            val reserve = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, rh)
                set(java.util.Calendar.MINUTE, rm)
                set(java.util.Calendar.SECOND, 0)
            }
            if (reserve.before(now)) reserve.add(java.util.Calendar.DATE, 1)
            if (arrive.before(reserve) || arrive == reserve) "準" else "來不及"
        }
        else -> "$mins"
    }
    return "$firstLine\n8392 白Tesla 3\n$line3"
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val orders by OrderQueue.orders.collectAsState()
    val dispatchedIds = remember { mutableSetOf<Long>() }

    var groupName by remember { mutableStateOf(LineNotificationService.targetGroupName) }
    var keywordInput by remember { mutableStateOf(LineNotificationService.keywords.joinToString("、")) }
    var isListening by remember { mutableStateOf(false) }
    var isAccessibilityEnabled by remember { mutableStateOf(false) }
    var currentLat by remember { mutableStateOf<Double?>(null) }
    var currentLon by remember { mutableStateOf<Double?>(null) }
    var locationLabel by remember { mutableStateOf("尚未定位") }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) locationLabel = "需要定位權限"
    }

    // 持續定位：app 開著就每 4 秒自動更新
    DisposableEffect(fusedLocationClient) {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    currentLat = loc.latitude
                    currentLon = loc.longitude
                    locationLabel = "%.5f, %.5f".format(loc.latitude, loc.longitude)
                }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
            .setMinUpdateIntervalMillis(2000L)
            .setMinUpdateDistanceMeters(10f)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, callback, android.os.Looper.getMainLooper())
            locationLabel = "定位中..."
        } else {
            locationLabel = "需要定位權限"
        }
        onDispose { fusedLocationClient.removeLocationUpdates(callback) }
    }

    LaunchedEffect(Unit) {
        isListening = isNotificationListenerEnabled(context)
        isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // 自動計算：GPS 或訂單任一更新就重新觸發
    LaunchedEffect(Unit) {
        snapshotFlow { currentLat to orders }.collect { (lat, orderList) ->
            if (lat == null) return@collect
            val lon = currentLon ?: return@collect
            orderList.filter { it.status == OrderStatus.CALCULATING && it.id !in dispatchedIds }
                .forEach { order ->
                    dispatchedIds.add(order.id)
                    scope.launch {
                        if (order.address == null) {
                            OrderQueue.update(order.id, null, OrderStatus.FAILED, "")
                            return@launch
                        }
                        val resolvedDest = convertChineseNum(buildDestination(order.address, order.raw))
                        val rawMins = getRouteMinutes(lat, lon, order.address, order.raw)
                        val mins = if (rawMins != null && rawMins <= 1) 3 else rawMins
                        val report = buildReport(order.raw, mins, order.reserveTime)
                        OrderQueue.update(order.id, mins,
                            if (mins != null) OrderStatus.DONE else OrderStatus.FAILED, report, resolvedDest)
                    }
                }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🚗 派車監聽器", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (orders.isNotEmpty()) {
                    TextButton(onClick = {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        orders.forEach { nm.cancel(it.raw.hashCode()) }
                        OrderQueue.clear()
                        dispatchedIds.clear()
                    }) {
                        Text("清除全部", color = Color.Gray)
                    }
                }
            }
        }

        // 監聽狀態
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = if (isListening) Color(0xFF1B5E20) else Color(0xFFB71C1C)
            )) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isListening) "✅ 監聽中" else "❌ 尚未授權",
                        color = Color.White, fontWeight = FontWeight.Bold
                    )
                    if (!isListening) {
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("去授權") }
                    }
                }
            }
        }

        // 無障礙服務狀態
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = if (isAccessibilityEnabled) Color(0xFF1A237E) else Color(0xFF4A148C)
            )) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isAccessibilityEnabled) "♿ 無障礙：已啟用（LINE 前景也能抓單）"
                        else "♿ 無障礙：未啟用（在 LINE 內看不到新單）",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isAccessibilityEnabled) {
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("去授權") }
                    }
                }
            }
        }

        // GPS
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D47A1))) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📍 $locationLabel", color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.weight(1f))
                    Text("即時更新", color = Color(0xFF90CAF9), fontSize = 11.sp)
                }
            }
        }

        // 設定
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it; LineNotificationService.targetGroupName = it },
                    label = { Text("監聽群組") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = keywordInput,
                    onValueChange = {
                        keywordInput = it
                        LineNotificationService.keywords = it.split("、", "，", ",")
                            .map { k -> k.trim() }.filter { k -> k.isNotEmpty() }.toMutableList()
                    },
                    label = { Text("觸發關鍵字（用、分隔）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 訂單列表
        if (orders.isEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF263238))) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("等待派車通知...", color = Color.Gray, fontSize = 16.sp)
                    }
                }
            }
        } else {
            item {
                Text("待處理訂單（${orders.size} 張）",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            items(orders.sortedBy { it.minutes ?: Int.MAX_VALUE }, key = { it.id }) { order ->
                OrderCard(order = order, context = context, onDismiss = {
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .cancel(order.raw.hashCode())
                    OrderQueue.remove(order.id)
                    dispatchedIds.remove(order.id)
                })
            }
        }
    }
}

@Composable
fun OrderCard(order: OrderItem, context: Context, onDismiss: () -> Unit) {
    val isHighValue = order.raw.contains("低消300")
    val cardColor = when {
        isHighValue -> Color(0xFF1B5E20)
        order.status == OrderStatus.CALCULATING -> Color(0xFF37474F)
        order.status == OrderStatus.DONE -> Color(0xFF0D47A1)
        else -> Color(0xFF4E342E)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (order.status) {
                    OrderStatus.CALCULATING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text("計算中...", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    OrderStatus.DONE -> {
                        val lastLine = order.report.split("\n").lastOrNull() ?: ""
                        val label = when (lastLine) {
                            "準" -> "${order.minutes} 分 · 準 ✅"
                            "來不及" -> "${order.minutes} 分 · 來不及 ⚠️"
                            else -> "${order.minutes} 分鐘"
                        }
                        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    OrderStatus.FAILED -> Text("❌ 無法計算", color = Color.White, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onDismiss) {
                    Text("✕", color = Color.White, fontSize = 16.sp)
                }
            }

            if (order.address != null) {
                Text("📍 ${order.address}", color = Color(0xFFB0BEC5), fontSize = 13.sp)
            }
            if (order.resolvedDest != null) {
                Text("🗺 ${order.resolvedDest}", color = Color(0xFF4DB6AC), fontSize = 12.sp)
            }

            val preview = order.raw.take(80) + if (order.raw.length > 80) "…" else ""
            Text(preview, color = Color(0xFF90A4AE), fontSize = 12.sp)

            if (order.status == OrderStatus.DONE && order.report.isNotEmpty()) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("report", order.report))
                        context.packageManager.getLaunchIntentForPackage("jp.naver.line.android")
                            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) }
                            ?.let { context.startActivity(it) }
                        Toast.makeText(context, "已複製！點 LINE 通知進調度室後長按貼上", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📋 複製並開啟群組")
                }
            }
        }
    }
}

fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?: return false
    val cn = ComponentName(context, LineNotificationService::class.java)
    return flat.contains(cn.flattenToString())
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val cn = ComponentName(context, LineAccessibilityService::class.java)
    return flat.contains(cn.flattenToString())
}

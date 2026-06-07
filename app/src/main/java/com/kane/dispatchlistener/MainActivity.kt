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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

const val MAPS_API_KEY = "AIzaSyBV2J4zwqtWexO3gFg2UAEDL6YuSauSHm0"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val orderFromNotification = intent.getStringExtra("order_text") ?: ""
        setContent {
            DispatchListenerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(initialOrder = orderFromNotification)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
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
    // fallback: 地標名稱
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

fun buildDestination(address: String): String {
    val tcDistricts = listOf("西屯","南屯","北屯","豐原","大里","太平","大甲","清水","沙鹿","梧棲",
        "后里","神岡","潭子","大雅","新社","石岡","東勢","和平","烏日","大肚","龍井","霧峰",
        "外埔","大安","中區","東區","西區","南區","北區")
    if (address.contains("市") || address.contains("縣")) return address
    for (d in tcDistricts) {
        if (address.startsWith(d) && !address.startsWith(d + "區")) {
            return "台中市${d}區${address.drop(d.length)}"
        }
    }
    return if (address.contains(Regex("[路街道巷弄]"))) "台中市$address" else "$address 台中"
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

suspend fun getRouteMinutes(originLat: Double, originLon: Double, destination: String): Int? {
    return withContext(Dispatchers.IO) {
        try {
            val dest = convertChineseNum(buildDestination(destination))
            val originStr = "$originLat,$originLon"
            val url = "https://maps.googleapis.com/maps/api/distancematrix/json" +
                    "?origins=${encode(originStr)}" +
                    "&destinations=${encode(dest)}" +
                    "&mode=driving" +
                    "&language=zh-TW" +
                    "&key=$MAPS_API_KEY"
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            android.util.Log.d("DispatchAPI", "response: $body")
            val json = JSONObject(body)
            val element = json
                .getJSONArray("rows")
                .getJSONObject(0)
                .getJSONArray("elements")
                .getJSONObject(0)
            val status = element.getString("status")
            if (status != "OK") return@withContext null
            val seconds = element.getJSONObject("duration").getInt("value")
            // 尖峰時段 +2 分鐘
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
            val arriveMs = System.currentTimeMillis() + mins * 60 * 1000L
            val arrive = java.util.Calendar.getInstance().apply { timeInMillis = arriveMs }
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
fun MainScreen(initialOrder: String = "") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf(LineNotificationService.targetGroupName) }
    var keywordInput by remember { mutableStateOf(LineNotificationService.keywords.joinToString("、")) }
    var orderText by remember { mutableStateOf(initialOrder) }
    var reportText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var currentLat by remember { mutableStateOf<Double?>(null) }
    var currentLon by remember { mutableStateOf<Double?>(null) }
    var locationLabel by remember { mutableStateOf("尚未定位") }

    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch {
                try {
                    val loc = suspendCancellableCoroutine<Location?> { cont ->
                        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 0)
                            .setMaxUpdates(1)
                            .build()
                        val callback = object : LocationCallback() {
                            override fun onLocationResult(result: LocationResult) {
                                fusedLocationClient.removeLocationUpdates(this)
                                cont.resume(result.lastLocation)
                            }
                        }
                        fusedLocationClient.requestLocationUpdates(
                            request, callback, android.os.Looper.getMainLooper()
                        )
                        cont.invokeOnCancellation { fusedLocationClient.removeLocationUpdates(callback) }
                    }
                    if (loc != null) {
                        currentLat = loc.latitude
                        currentLon = loc.longitude
                        locationLabel = "%.6f, %.6f".format(loc.latitude, loc.longitude)
                    } else {
                        locationLabel = "定位失敗"
                    }
                } catch (e: Exception) {
                    locationLabel = "定位失敗"
                }
            }
        } else {
            locationLabel = "需要定位權限"
        }
    }

    fun requestLocation() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                try {
                    locationLabel = "定位中..."
                    val loc: Location = fusedLocationClient
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .await()
                    currentLat = loc.latitude
                    currentLon = loc.longitude
                    locationLabel = "%.6f, %.6f".format(loc.latitude, loc.longitude)
                } catch (e: Exception) {
                    locationLabel = "定位失敗"
                }
            }
        } else {
            locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LaunchedEffect(Unit) {
        isListening = isNotificationListenerEnabled(context)
        requestLocation()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("🚗 派車監聽器", fontSize = 24.sp, fontWeight = FontWeight.Bold) }

        // 監聽狀態
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = if (isListening) Color(0xFF1B5E20) else Color(0xFFB71C1C)
            )) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isListening) "✅ 監聽中" else "❌ 尚未授權",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (!isListening) {
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("去授權") }
                    }
                }
            }
        }

        // GPS 定位
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D47A1))) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("📍 我的位置", color = Color.White, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = { requestLocation() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("更新位置") }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(locationLabel, color = Color.White, fontSize = 13.sp)
                }
            }
        }

        // 群組名稱
        item {
            Text("監聽群組名稱", fontWeight = FontWeight.Bold)
            OutlinedTextField(value = groupName, onValueChange = {
                groupName = it
                LineNotificationService.targetGroupName = it
            }, label = { Text("例如：Q77") }, modifier = Modifier.fillMaxWidth())
        }

        // 關鍵字
        item {
            Text("觸發關鍵字（用、分隔）", fontWeight = FontWeight.Bold)
            OutlinedTextField(value = keywordInput, onValueChange = {
                keywordInput = it
                LineNotificationService.keywords =
                    it.split("、", "，", ",").map { k -> k.trim() }
                        .filter { k -> k.isNotEmpty() }.toMutableList()
            }, label = { Text("例如：西屯、北屯、大里") }, modifier = Modifier.fillMaxWidth())
        }

        // 訂單
        item {
            Text("收到的訂單", fontWeight = FontWeight.Bold)
            OutlinedTextField(value = orderText, onValueChange = { orderText = it },
                label = { Text("自動帶入或手動貼上") },
                modifier = Modifier.fillMaxWidth(), minLines = 3)
        }

        // 計算按鈕
        item {
            Button(onClick = {
                val address = parseAddress(orderText)
                if (address == null) {
                    statusMsg = "❌ 無法解析地址，請確認訂單格式"
                    return@Button
                }
                if (currentLat == null || currentLon == null) {
                    statusMsg = "❌ 請先更新位置"
                    return@Button
                }
                val reserveTime = parseReserveTime(orderText)
                isLoading = true
                statusMsg = "計算中..."
                scope.launch {
                    val mins = getRouteMinutes(currentLat!!, currentLon!!, address)
                    isLoading = false
                    reportText = buildReport(orderText, mins, reserveTime)
                    statusMsg = if (mins != null) "📍 距離目的地約 $mins 分鐘" else "⚠️ 無法取得導航時間"
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.height(20.dp))
                } else {
                    Text("🗺 計算時間並產生報單", fontSize = 16.sp)
                }
            }
        }

        if (statusMsg.isNotEmpty()) {
            item { Text(statusMsg, fontWeight = FontWeight.Bold) }
        }

        if (reportText.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D47A1))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("報單內容", color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(reportText, color = Color.White, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("report", reportText))
                            Toast.makeText(context, "已複製！貼到 LINE 發送", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("📋 複製報單文字")
                        }
                    }
                }
            }
        }
    }
}

fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    val cn = ComponentName(context, LineNotificationService::class.java)
    return flat.contains(cn.flattenToString())
}
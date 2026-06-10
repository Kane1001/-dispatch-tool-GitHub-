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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.foundation.clickable
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

// iOS 風格色系
private val BG         = Color(0xFFF2F2F7)  // iOS systemGroupedBackground
private val CARD       = Color(0xFFFFFFFF)  // iOS card white
private val SEP        = Color(0xFFE5E5EA)  // iOS separator
private val LABEL      = Color(0xFF1C1C1E)  // iOS label (near-black)
private val LABEL2     = Color(0xFF6D6D72)  // iOS secondaryLabel
private val IOS_BLUE   = Color(0xFF007AFF)
private val IOS_GREEN  = Color(0xFF34C759)
private val IOS_RED    = Color(0xFFFF3B30)
private val IOS_ORANGE = Color(0xFFFF9500)
private val IOS_PURPLE = Color(0xFF5856D6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DispatchListenerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BG) {
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

fun hasPlateNumber(text: String): Boolean {
    val lines = text.trim().split("\n")
    val firstLine = lines.firstOrNull() ?: return false
    val fullPattern = Regex("""^\d{4}[\s/]|[A-Z]{2,3}-\d{3,4}|\d{3,4}-[A-Z]{2,3}""")
    if (fullPattern.containsMatchIn(firstLine)) return true
    val segPlate = Regex("""^\d{4}[^\d/]""")
    if (firstLine.split("/").any { seg -> segPlate.containsMatchIn(seg.trim()) }) return true
    // 多行訊息第二行起若有車牌格式，判定為司機回報（例：「7666黑 賓士」）
    return lines.drop(1).any { line -> segPlate.containsMatchIn(line.trim()) }
}

private val tcDistricts = listOf("西屯","南屯","北屯","豐原","大里","太平","大甲","清水","沙鹿","梧棲",
    "后里","神岡","潭子","大雅","新社","石岡","東勢","和平","烏日","大肚","龍井","霧峰",
    "外埔","大安","中區","東區","西區","南區","北區")

private val outOfTcAreaMap = mapOf(
    "彰化" to "彰化縣",
    "伸港" to "彰化縣伸港鄉",
    "南投" to "南投縣",
    "草屯" to "南投縣草屯鎮",
    "苗栗" to "苗栗縣"
)

fun buildDestination(address: String, raw: String = ""): String {

    if (address.contains("市") || address.contains("縣")) return address

    // 常見地標直接對應（高鐵、火車站等不帶路名的目的地）
    val landmarks = mapOf(
        "台中高鐵" to "台灣高速鐵路台中站",
        "烏日高鐵" to "台灣高速鐵路台中站",
        "高鐵" to "台灣高速鐵路台中站",
        "台中火車站" to "台中火車站",
        "火車站" to "台中火車站",
        "清泉崗" to "台中清泉崗機場",
        "機場" to "台中清泉崗機場",
        "台中港" to "台中港",
        "台中客運" to "台中客運轉運站"
    )
    for ((lm, full) in landmarks) {
        if (address.contains(lm)) {
            android.util.Log.d("DispatchDest", "地標對應：$full（原始：$address）")
            return full
        }
    }

    val areaHint = raw.split("/").firstOrNull()?.trim() ?: ""
    for ((keyword, prefix) in outOfTcAreaMap) {
        if (areaHint.contains(keyword) || address.contains(keyword)) {
            val dest = if (address.contains(Regex("[路街道巷弄]"))) "$prefix$address"
                       else "$prefix $address"
            android.util.Log.d("DispatchDest", "非台中地區：$dest（原始：$address）")
            return dest
        }
    }

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

// 回傳 Triple：(分鐘, 距離文字, Google 解析後的目的地地址)
suspend fun getRouteMinutes(originLat: Double, originLon: Double, destination: String, raw: String = ""): Triple<Int, String, String?>? {
    return withContext(Dispatchers.IO) {
        try {
            val gpsCoord = parseGpsCoord(raw)
            val destEncoded = if (gpsCoord != null) {
                encode(gpsCoord)  // 直接用 GPS 座標，不經過 buildDestination
            } else {
                encode(convertChineseNum(buildDestination(destination, raw)))
            }
            val url = "https://maps.googleapis.com/maps/api/distancematrix/json" +
                    "?origins=${encode("$originLat,$originLon")}" +
                    "&destinations=$destEncoded" +
                    "&mode=driving" +
                    "&departure_time=now" +
                    "&language=zh-TW" +
                    "&key=$MAPS_API_KEY"
            val response = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
            val body = response.body?.string() ?: return@withContext null
            val root = JSONObject(body)
            val element = root
                .getJSONArray("rows").getJSONObject(0)
                .getJSONArray("elements").getJSONObject(0)
            if (element.getString("status") != "OK") return@withContext null
            val rawMins = (element.optJSONObject("duration_in_traffic") ?: element.getJSONObject("duration")).getInt("value") / 60
            val distanceText = element.getJSONObject("distance").getString("text")
            val resolvedAddr = root.getJSONArray("destination_addresses").optString(0)
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val isPeak = hour in 7..9 || hour in 17..19  // 07:00-10:00, 17:00-20:00
            val buffer = when {
                rawMins < 7 -> 1   // 短程不管尖峰，只加 1
                isPeak -> 2        // 長程尖峰加 2
                else -> 0          // 長程離峰不加
            }
            Triple(rawMins + buffer, distanceText, resolvedAddr.ifBlank { null })
        } catch (e: Exception) {
            android.util.Log.e("DispatchAPI", "error: ${e.message}", e)
            null
        }
    }
}

fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

// 解析 DMS 格式 GPS 座標（如 24°06'38.3"N 120°40'11.7"E），回傳 "lat,lon" 十進位字串
fun parseGpsCoord(text: String): String? {
    val re = Regex("""(\d+)°(\d+)'([\d.]+)"([NS])\s+(\d+)°(\d+)'([\d.]+)"([EW])""")
    val m = re.find(text) ?: return null
    val g = m.groupValues
    val lat = g[1].toDouble() + g[2].toDouble() / 60 + g[3].toDouble() / 3600
    val lon = g[5].toDouble() + g[6].toDouble() / 60 + g[7].toDouble() / 3600
    return "%.6f,%.6f".format(
        if (g[4] == "S") -lat else lat,
        if (g[8] == "W") -lon else lon
    )
}

fun buildReport(orderText: String, mins: Int?, reserveTime: String?, resolvedDest: String? = null, googleAddr: String? = null): String {
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
    // 地址沒有區名時，從 Google 回傳的地址萃取實際導航區域，附在報單最後作為責任依據
    val addressHasDistrict = resolvedDest != null &&
        (tcDistricts.any { resolvedDest.contains(it) } || outOfTcAreaMap.values.any { resolvedDest.contains(it) })
    val districtSuffix = if (!addressHasDistrict && googleAddr != null) {
        val matched = tcDistricts.firstOrNull { googleAddr.contains(it) }
            ?: outOfTcAreaMap.values.firstOrNull { googleAddr.contains(it) }
        if (matched != null) "\n導航：$matched" else ""
    } else ""
    return "$firstLine\n8392 白Tesla 3\n$line3$districtSuffix"
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val orders by OrderQueue.orders.collectAsState()
    val dispatchedIds = remember { mutableSetOf<Long>() }

    var groupName by remember { mutableStateOf(LineNotificationService.targetGroupName) }
    var keywordInput by remember { mutableStateOf(LineNotificationService.keywords.joinToString("、")) }
    var allowedSenderInput by remember { mutableStateOf(LineNotificationService.allowedSenders.joinToString("、")) }
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

    // 每 2 秒重新偵測服務授權狀態，確保從設定頁返回後燈號立即更新
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000L)
            isListening = isNotificationListenerEnabled(context)
            isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            val now = System.currentTimeMillis()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            OrderQueue.orders.value
                .filter { now - it.id > 30 * 60 * 1000L }
                .forEach { order ->
                    nm.cancel(order.raw.hashCode())
                    OrderQueue.remove(order.id)
                }
        }
    }

    LaunchedEffect(Unit) {
        var lastRecalcLat: Double? = null
        var lastRecalcLon: Double? = null
        var lastRecalcTime = 0L

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
                        val gpsCoord = parseGpsCoord(order.raw)
                        val resolvedDest = if (gpsCoord != null) "GPS $gpsCoord"
                            else convertChineseNum(buildDestination(order.address, order.raw))
                        val result = getRouteMinutes(lat, lon, order.address, order.raw)
                        val mins = result?.first?.let { if (it <= 1) 3 else it }
                        val dist = result?.second
                        val googleAddr = result?.third
                        val report = buildReport(order.raw, mins, order.reserveTime, resolvedDest, googleAddr)
                        OrderQueue.update(order.id, mins,
                            if (mins != null) OrderStatus.DONE else OrderStatus.FAILED, report, resolvedDest, dist)
                    }
                }

            val doneOrders = orderList.filter { it.status == OrderStatus.DONE && it.address != null }
            if (doneOrders.isEmpty()) return@collect

            val now = System.currentTimeMillis()
            val distanceMoved = if (lastRecalcLat != null && lastRecalcLon != null) {
                val result = FloatArray(1)
                Location.distanceBetween(lastRecalcLat!!, lastRecalcLon!!, lat, lon, result)
                result[0]
            } else Float.MAX_VALUE

            if (distanceMoved >= 50f || now - lastRecalcTime >= 30_000L) {
                lastRecalcLat = lat
                lastRecalcLon = lon
                lastRecalcTime = now
                doneOrders.forEach { order ->
                    scope.launch {
                        val result = getRouteMinutes(lat, lon, order.address!!, order.raw)
                        val mins = result?.first?.let { if (it <= 1) 3 else it }
                        val dist = result?.second
                        val googleAddr = result?.third
                        if (mins != null) {
                            val gpsCoord2 = parseGpsCoord(order.raw)
                            val resolvedDest = if (gpsCoord2 != null) "GPS $gpsCoord2"
                                else convertChineseNum(buildDestination(order.address!!, order.raw))
                            val report = buildReport(order.raw, mins, order.reserveTime, resolvedDest, googleAddr)
                            OrderQueue.update(order.id, mins, OrderStatus.DONE, report, resolvedDest, dist)
                        }
                    }
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 標題
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        "⚡ 閃報",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        color = LABEL,
                        letterSpacing = (-1).sp
                    )
                    Text(
                        "HC 調度系統 · 台中",
                        fontSize = 12.sp,
                        color = LABEL2,
                        letterSpacing = 0.5.sp
                    )
                }
                if (orders.isNotEmpty()) {
                    TextButton(onClick = {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        orders.forEach { nm.cancel(it.raw.hashCode()) }
                        OrderQueue.clear()
                        dispatchedIds.clear()
                    }) {
                        Text("清除全部", color = IOS_RED, fontSize = 14.sp)
                    }
                }
            }
        }

        // 狀態列（單行燈號）
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CARD),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 通知監聽燈號（可點擊跳設定）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(
                                if (isListening) IOS_GREEN else Color(0xFFD1D1D6), CircleShape
                            ))
                            Text(
                                "通知",
                                fontSize = 12.sp,
                                color = if (isListening) LABEL else LABEL2
                            )
                        }

                        // 前景掃描燈號（可點擊跳設定）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(
                                if (isAccessibilityEnabled) IOS_PURPLE else Color(0xFFD1D1D6), CircleShape
                            ))
                            Text(
                                "前景",
                                fontSize = 12.sp,
                                color = if (isAccessibilityEnabled) LABEL else LABEL2
                            )
                        }
                    }

                    // 定位
                    Text(locationLabel, color = LABEL2, fontSize = 11.sp)
                }
            }
        }

        // 設定（可折疊）
        item {
            var expanded by remember { mutableStateOf(false) }
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = CARD),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("設定", color = LABEL, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "收起 ▲" else "展開 ▼", color = IOS_BLUE, fontSize = 13.sp)
                        }
                    }
                    if (expanded) {
                        val fieldColors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IOS_BLUE,
                            unfocusedBorderColor = SEP,
                            focusedLabelColor = IOS_BLUE,
                            unfocusedLabelColor = LABEL2,
                            cursorColor = IOS_BLUE,
                            focusedTextColor = LABEL,
                            unfocusedTextColor = LABEL
                        )
                        Column(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = groupName,
                                onValueChange = { groupName = it; LineNotificationService.targetGroupName = it },
                                label = { Text("監聽群組") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = fieldColors
                            )
                            OutlinedTextField(
                                value = keywordInput,
                                onValueChange = {
                                    keywordInput = it
                                    LineNotificationService.keywords = it.split("、", "，", ",")
                                        .map { k -> k.trim() }.filter { k -> k.isNotEmpty() }.toMutableList()
                                },
                                label = { Text("觸發關鍵字（用、分隔）") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = fieldColors
                            )
                            OutlinedTextField(
                                value = allowedSenderInput,
                                onValueChange = {
                                    allowedSenderInput = it
                                    LineNotificationService.allowedSenders = it.split("、", "，", ",")
                                        .map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.toMutableList()
                                },
                                label = { Text("額外允許帳號（用、分隔，空格會忽略）") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                colors = fieldColors
                            )
                        }
                    }
                }
            }
        }

        // 訂單列表
        if (orders.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CARD),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("📡", fontSize = 28.sp)
                            Text(
                                "待命中，掃描頻道",
                                color = LABEL2,
                                fontSize = 14.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    "待處理　${orders.size}　張",
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    color = LABEL2,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
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
    val isHighValue = order.raw.contains("低消")
    val cardBg = when {
        isHighValue -> Color(0xFFFFF8F0)
        order.status == OrderStatus.FAILED -> Color(0xFFFFF0F0)
        else -> CARD
    }
    val etaColor = when {
        isHighValue -> IOS_ORANGE
        order.status == OrderStatus.FAILED -> IOS_RED
        else -> IOS_BLUE
    }
    val btnColor = if (isHighValue) IOS_ORANGE else IOS_BLUE

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
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
                            color = LABEL2,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text("計算中...", color = LABEL2, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                    OrderStatus.DONE -> {
                        val lastLine = order.report.split("\n").lastOrNull() ?: ""
                        val timeLabel = when (lastLine) {
                            "準" -> "${order.minutes} 分  ·  準 ✅"
                            "來不及" -> "${order.minutes} 分  ·  來不及 ⚠️"
                            else -> "${order.minutes} 分鐘"
                        }
                        Column {
                            Text(
                                timeLabel,
                                color = etaColor,
                                fontWeight = FontWeight.Black,
                                fontSize = 26.sp,
                                letterSpacing = (-0.5).sp
                            )
                            if (order.distance != null) {
                                Text(order.distance, color = LABEL2, fontSize = 12.sp)
                            }
                        }
                    }
                    OrderStatus.FAILED -> Text("無法計算路徑", color = IOS_RED,
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("✕", color = LABEL2, fontSize = 18.sp)
                }
            }

            if (order.address != null) {
                Text("📍  ${order.address}", color = LABEL, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
            if (order.resolvedDest != null) {
                val hasDistrict = tcDistricts.any { order.resolvedDest.contains(it) } ||
                    outOfTcAreaMap.values.any { order.resolvedDest.contains(it) }
                Text(
                    if (hasDistrict) "🗺  ${order.resolvedDest}"
                    else "⚠️  ${order.resolvedDest}（未含區名，請確認）",
                    color = if (hasDistrict) IOS_BLUE else IOS_ORANGE,
                    fontSize = 13.sp
                )
            }

            val preview = order.raw.take(80) + if (order.raw.length > 80) "…" else ""
            Text(preview, color = LABEL2, fontSize = 12.sp)

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
                    colors = ButtonDefaults.buttonColors(containerColor = btnColor),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📋  複製並開啟群組", fontSize = 13.sp)
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

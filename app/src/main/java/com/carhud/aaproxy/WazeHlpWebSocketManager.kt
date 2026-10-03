package com.carhud.aaproxy

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.java_websocket.WebSocket as JvmWebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.HandshakeBuilder
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.TimeUnit

object WazeHlpWebSocketManager {
    private const val TAG = "WazeHlpWs"
    const val WS_PORT = 8766
    const val WS_URL = "ws://127.0.0.1:8766/hlp"
    const val WS_PROTOCOL = "hlp.v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var clientJob: Job? = null
    private var webSocketClient: WebSocket? = null
    private var server: WazeHlpServer? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _statusText = MutableStateFlow("Chờ Waze Mod kết nối...")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    class HlpDraft : Draft_6455(Collections.emptyList(), listOf(Protocol("hlp.v1"), Protocol(""))) {
        override fun postProcessHandshakeResponseAsServer(
            request: ClientHandshake,
            response: ServerHandshakeBuilder
        ): HandshakeBuilder {
            val res = super.postProcessHandshakeResponseAsServer(request, response)
            res.put("Connection", "Upgrade")
            res.put("Upgrade", "websocket")
            val reqProto = request.getFieldValue("Sec-WebSocket-Protocol")
            if (!reqProto.isNullOrBlank()) {
                res.put("Sec-WebSocket-Protocol", reqProto.split(",")[0].trim())
            }
            return res
        }
        override fun copyInstance(): Draft = HlpDraft()
    }

    private class WazeHlpServer(port: Int) : WebSocketServer(
        InetSocketAddress(port),
        listOf(HlpDraft(), Draft_6455())
    ) {
        init {
            isReuseAddr = true
            connectionLostTimeout = 30
        }

        override fun onStart() {
            Log.i(TAG, "Waze HLP WebSocket Server started on 0.0.0.0:$WS_PORT")
            if (!_isConnected.value) {
                _statusText.value = "Server đang lắng nghe cổng $WS_PORT (Sẵn sàng)"
            }
        }

        override fun onOpen(conn: JvmWebSocket?, handshake: ClientHandshake?) {
            val remoteAddr = conn?.remoteSocketAddress?.toString() ?: "Client"
            val res = handshake?.resourceDescriptor ?: ""
            Log.i(TAG, "Waze Mod connected from $remoteAddr, resource=$res")
            _isConnected.value = true
            _statusText.value = "Đã kết nối Waze Mod ($remoteAddr)"
            VietmapStateRepository.updateConnection(true)
        }

        override fun onClose(conn: JvmWebSocket?, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "Waze Mod client closed: code=$code, reason=$reason, remote=$remote")
            if (connections.isEmpty()) {
                _isConnected.value = false
                _statusText.value = "Chờ Waze Mod kết nối (Cổng $WS_PORT)..."
                VietmapStateRepository.updateConnection(false)
            }
        }

        override fun onMessage(conn: JvmWebSocket?, message: String?) {
            if (message.isNullOrBlank()) return
            Log.d(TAG, "WAZE_RAW(server)=$message")
            handleJsonMessage(message)
        }

        override fun onError(conn: JvmWebSocket?, ex: Exception?) {
            Log.w(TAG, "Waze Mod server error: ${ex?.message}")
            if (conn == null && ex is java.net.BindException) {
                Log.e(TAG, "Port $WS_PORT already bound. Falling back to client mode...")
                _statusText.value = "Cổng $WS_PORT bận - Thử chế độ Client..."
                startClientFallback()
            }
        }
    }

    private var keepAliveJob: Job? = null

    fun start() {
        if (server == null) {
            startServer()
        }
        startClientFallback()
        startKeepAliveMonitor()
    }

    fun stop() {
        stopKeepAliveMonitor()
        stopClientFallback()
        stopServer()
        _isConnected.value = false
        _statusText.value = "Đã dừng Server"
    }

    private fun startKeepAliveMonitor() {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(3000)
                try {
                    if (server == null) {
                        Log.i(TAG, "KeepAlive: Server is null, auto-restarting Waze Mod listener...")
                        startServer()
                    }
                    if (!_isConnected.value && clientJob?.isActive != true) {
                        startClientFallback()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "KeepAlive error: $e")
                }
            }
        }
    }

    private fun stopKeepAliveMonitor() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    private fun startServer() {
        try {
            stopServer()
            _statusText.value = "Đang kết nối Waze Mod (Cổng $WS_PORT)..."
            val s = WazeHlpServer(WS_PORT)
            s.start()
            server = s
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WazeHlpServer", e)
            if (e is java.net.BindException) {
                startClientFallback()
            } else {
                _statusText.value = "Lỗi Server: ${e.message}"
            }
        }
    }

    private fun stopServer() {
        try {
            server?.stop(500)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping server", e)
        }
        server = null
    }

    fun restartConnection() {
        Log.i(TAG, "Restarting Waze Mod connection...")
        _isConnected.value = false
        _statusText.value = "Đang khởi động lại..."
        stopClientFallback()
        stopServer()
        scope.launch {
            delay(500)
            startServer()
        }
    }

    private fun startClientFallback() {
        if (clientJob?.isActive == true) return
        clientJob = scope.launch {
            while (isActive && !_isConnected.value) {
                connectClient()
                delay(6000)
            }
        }
    }

    private fun stopClientFallback() {
        clientJob?.cancel()
        clientJob = null
        try {
            webSocketClient?.cancel()
        } catch (e: Exception) {}
        webSocketClient = null
    }

    private fun connectClient() {
        try {
            val request = Request.Builder()
                .url(WS_URL)
                .addHeader("Sec-WebSocket-Protocol", WS_PROTOCOL)
                .build()

            webSocketClient = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Client connected to Waze Mod on 127.0.0.1:$WS_PORT!")
                    _isConnected.value = true
                    _statusText.value = "Đã kết nối Waze Mod (Client 127.0.0.1:$WS_PORT)"
                    VietmapStateRepository.updateConnection(true)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "WAZE_RAW(client)=$text")
                    handleJsonMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    _isConnected.value = false
                    _statusText.value = "Đang ngắt kết nối"
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _isConnected.value = false
                    _statusText.value = "Đã ngắt kết nối"
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _isConnected.value = false
                    _statusText.value = "Chờ Waze Mod kết nối (Cổng $WS_PORT)..."
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Client connection exception", e)
        }
    }

    fun getDeviceIpList(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        list.add("Cục bộ (cùng máy)" to "127.0.0.1")
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        val name = intf.name.lowercase()
                        val desc = when {
                            name.contains("wlan") || name.contains("wifi") -> "Wi-Fi LAN"
                            name.contains("ap") || name.contains("hotspot") || name.contains("tether") -> "Hotspot máy"
                            name.contains("eth") || name.contains("rndis") -> "Ethernet"
                            else -> "Mạng (${intf.displayName ?: intf.name})"
                        }
                        list.add(desc to ip)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting IP addresses", e)
        }
        return list
    }


    private val IGNORED_STATUS_PHRASES = listOf(
        "đã tắt",
        "tắt âm",
        "âm thanh đã tắt",
        "đang chạy trong nền",
        "đang chạy ngầm",
        "chạy trong nền",
        "chạy ngầm",
        "running in background",
        "running in the background",
        "chạm để mở",
        "nhấn để mở",
        "tap to open",
        "click to open",
        "đang sử dụng gps",
        "using gps",
        "chế độ lái xe",
        "driving mode",
        "notification settings",
        "cài đặt thông báo"
    )

    fun isIgnoredText(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val lower = text.lowercase().trim()
        return IGNORED_STATUS_PHRASES.any { lower.contains(it) }
    }

    private fun mergeNestedPayload(root: JSONObject): JSONObject {
        val merged = JSONObject(root.toString())
        // HLP/1 builds can wrap state in one of these objects. Flatten them so the
        // parser works with both flat and wrapped frames.
        for (containerKey in listOf("data", "payload", "state", "hud", "navigation", "nav", "d")) {
            val nested = root.optJSONObject(containerKey) ?: continue
            val it = nested.keys()
            while (it.hasNext()) {
                val key = it.next()
                merged.put(key, nested.opt(key))
            }
        }
        return merged
    }

    private fun hasAny(json: JSONObject, vararg keys: String): Boolean = keys.any { json.has(it) }

    private fun firstInt(json: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val raw = json.opt(key)
            val value = when (raw) {
                is Number -> raw.toDouble().toInt()
                is String -> raw.trim().replace(',', '.').toDoubleOrNull()?.toInt()
                else -> null
            }
            if (value != null) return value
        }
        return null
    }

    private fun firstFloat(json: JSONObject, vararg keys: String): Float? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val raw = json.opt(key)
            val value = when (raw) {
                is Number -> raw.toFloat()
                is String -> raw.trim().replace(',', '.').toFloatOrNull()
                else -> null
            }
            if (value != null) return value
        }
        return null
    }

    private fun firstDistanceMeters(json: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val raw = json.opt(key)
            val meters = when (raw) {
                is Number -> raw.toDouble().toInt()
                is String -> {
                    val cleaned = raw.trim().lowercase().replace(',', '.')
                    val number = Regex("""(\d+(?:\.\d+)?)""")
                        .find(cleaned)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toDoubleOrNull()
                    when {
                        number == null -> null
                        cleaned.contains("km") -> (number * 1000.0).toInt()
                        else -> number.toInt()
                    }
                }
                else -> null
            }
            if (meters != null) return meters
        }
        return null
    }

    private fun firstString(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val value = json.optString(key, "").trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun firstArray(json: JSONObject, vararg keys: String): JSONArray? {
        for (key in keys) {
            val arr = json.optJSONArray(key)
            if (arr != null) return arr
        }
        return null
    }

    private fun firstObject(json: JSONObject, vararg keys: String): JSONObject? {
        for (key in keys) {
            val obj = json.optJSONObject(key)
            if (obj != null) return obj
        }
        return null
    }

    private fun parseLaneGuidance(json: JSONObject): String? {
        firstString(json, "laneGuidance", "lane_guidance", "lane", "lanes", "ln")?.let { return it }
        val arr = firstArray(json, "lanes", "laneGuidance", "lane_guidance") ?: return null
        val parts = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) ?: continue
            parts.add(item.toString())
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" | ")
    }

    private fun handleJsonMessage(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)
            val json = mergeNestedPayload(root)

            val keys = mutableListOf<String>()
            val keyIt = json.keys()
            while (keyIt.hasNext()) keys.add(keyIt.next())
            Log.d(TAG, "WAZE_KEYS=${keys.sorted().joinToString()}")

            // Core speed state
            val speed = firstInt(json, "speed", "current_speed", "cur_speed", "spd", "currentSpeed", "gpsSpeed", "gps_speed")
                ?.takeIf { it >= 0 }
            val limit = firstInt(json, "speed_limit", "limit", "max_speed", "maxSpeed", "lim", "sl", "speedLimit")
                ?.takeIf { it > 0 }
            val secondaryLimit = firstInt(json, "secondarySpeedLimit", "secondary_speed_limit", "alrS", "nextSpeedLimit", "next_speed_limit")
                ?.takeIf { it > 0 }

            // Navigation / maneuver
            val turnCodePresent = hasAny(json, "trn", "turnCode", "turn_code")
            val turnCode = firstInt(json, "trn", "turnCode", "turn_code") ?: 0
            val rawTurnAction = firstString(
                json,
                "nextTurnAction", "turnAction", "turn_action", "maneuver", "nextManeuver",
                "next_maneuver", "navAction", "nav_action", "turnType", "turn_type", "action"
            )
            val normalizedWazeAction = rawTurnAction
                ?.lowercase()
                ?.trim()
                ?.replace('_', '-')
                ?.replace(' ', '-')
                ?.replace(Regex("-+"), "-")

            // Prefer Waze's own maneuver text. Numeric turn-code mapping is only
            // a fallback for HLP frames that do not contain an action string.
            val nextTurnAction = when {
                !normalizedWazeAction.isNullOrBlank() -> when {
                    normalizedWazeAction.contains("sharp-left") -> "sharp-left"
                    normalizedWazeAction.contains("sharp-right") -> "sharp-right"
                    normalizedWazeAction.contains("slight-left") || normalizedWazeAction.contains("keep-left") -> "slight-left"
                    normalizedWazeAction.contains("slight-right") || normalizedWazeAction.contains("keep-right") -> "slight-right"
                    normalizedWazeAction.contains("exit-left") -> "exit-left"
                    normalizedWazeAction.contains("exit-right") -> "exit-right"
                    normalizedWazeAction.contains("u-turn") || normalizedWazeAction.contains("uturn") -> "u-turn"
                    normalizedWazeAction.contains("roundabout") || normalizedWazeAction.contains("traffic-circle") -> "roundabout"
                    normalizedWazeAction.contains("destination") || normalizedWazeAction.contains("arrive") -> "destination"
                    normalizedWazeAction.contains("left") -> "turn-left"
                    normalizedWazeAction.contains("right") -> "turn-right"
                    normalizedWazeAction.contains("straight") || normalizedWazeAction.contains("continue") -> "straight"
                    else -> normalizedWazeAction
                }
                else -> when (turnCode) {
                    2, 9 -> "turn-left"
                    3, 10 -> "turn-right"
                    4, 12 -> "slight-left"
                    5, 13 -> "slight-right"
                    6, 7 -> "roundabout"
                    8 -> "u-turn"
                    11 -> "destination"
                    else -> "straight"
                }
            }

            val defaultTurnDesc = when (turnCode) {
                1 -> "Đi thẳng"
                2 -> "Rẽ trái"
                3 -> "Rẽ phải"
                4 -> "Chếch sang trái"
                5 -> "Chếch sang phải"
                6 -> "Vào vòng xuyến bên phải"
                7 -> "Vào vòng xuyến bên trái"
                8 -> "Quay đầu"
                9 -> "Rẽ gắt sang trái"
                10 -> "Rẽ gắt sang phải"
                11 -> "Đến nơi"
                12 -> "Lối ra bên trái"
                13 -> "Lối ra bên phải"
                else -> if (turnCode > 0) "Chỉ đường" else null
            }
            val turnDescription = firstString(json, "turnDescription", "turn_description", "instruction", "instructionText", "instr")
                ?: defaultTurnDesc

            val turnDistancePresent = hasAny(json, "distanceToTurnMeters", "distanceToTurn", "turnDistance", "turn_distance", "dst", "distance_meters", "dist_m", "dist")
            val distanceToTurnMeters = firstDistanceMeters(json, "distanceToTurnMeters", "distanceToTurn", "turnDistance", "turn_distance", "dst", "distance_meters", "dist_m", "dist")
                ?.takeIf { it >= 0 }

            val etaTime = firstString(json, "eta", "etaTime", "eta_time", "arrivalTime", "arrival_time")
            val remainingMinutes = firstInt(json, "rmin", "remainingMinutes", "remaining_minutes", "remainingMin")
                ?.takeIf { it >= 0 }

            val remainingDistanceKm = when {
                hasAny(json, "rm") -> firstFloat(json, "rm")?.let { it / 1000f }?.takeIf { it >= 0f }
                else -> firstFloat(json, "remainingDistanceKm", "remaining_km", "remainingKm")?.takeIf { it >= 0f }
            }

            // Current/next road. WazeMod V11 specifically documents st2 as the next road.
            val rawRoad = firstString(json, "st", "road", "street", "road_name", "street_name", "currentRoad", "current_road")
            val roadName = rawRoad?.takeIf { !isIgnoredText(it) }
            val rawNextRoad = firstString(json, "st2", "nextRoad", "next_road", "nextStreet", "next_street")
            val nextRoadName = rawNextRoad?.takeIf { !isIgnoredText(it) }

            val laneGuidance = parseLaneGuidance(json)
            val trafficLevel = firstInt(json, "trafficLevel", "traffic_level", "jamLevel", "jam_level", "jam")
                ?.takeIf { it >= 0 }
            val trafficDelayMinutes = firstInt(json, "trafficDelayMinutes", "traffic_delay_minutes", "delayMinutes", "delay_minutes", "delayMin", "jamDelay")
                ?.takeIf { it >= 0 }

            // Alert / warning payload. The important distinction is whether an alert field
            // is ABSENT (preserve previous alert) or explicitly says NONE/0 (clear alert).
            val alertCodePresent = hasAny(json, "alr", "alrV", "alertCode", "alert_code")
            var alertCode = firstInt(json, "alr", "alrV", "alertCode", "alert_code") ?: 0

            var rawAlertStr = firstString(json, "alert", "alert_type", "warning", "hazard", "warningType", "type")
            var rawCandidate = firstString(json, "alertDescription", "alert_description", "alert_title", "alertTitle", "warningText", "warning_text")
            var alertDistanceMeters = firstDistanceMeters(json, "alertDistanceMeters", "alert_distance_meters", "alertDistance", "alert_distance", "alrD", "alrDst", "alertDist", "reportDistance", "report_distance", "aheadDistance", "ahead_distance")
                ?.takeIf { it >= 0 }
            var alertRoad: String? = null

            // Real Waze/Waze-Mod builds may send the warning as a nested report
            // object instead of flat alr/alrD fields. Prefer the actual Waze
            // payload and preserve its subtype/title/distance.
            val alertObject = firstObject(
                json,
                "alertData", "alert_data", "warningData", "warning_data",
                "report", "hazardData", "hazard_data", "event"
            ) ?: (json.opt("alert") as? JSONObject)

            if (alertObject != null) {
                val objectCode = firstInt(alertObject, "code", "alr", "alertCode", "alert_code", "typeCode", "type_code") ?: 0
                val objectType = firstString(
                    alertObject,
                    "type", "kind", "category", "reportType", "report_type",
                    "subtype", "subType", "reportSubtype", "report_subtype", "hazardType", "hazard_type"
                )
                val objectTitle = firstString(
                    alertObject,
                    "title", "label", "description", "text", "message",
                    "warning", "alertTitle", "alert_title"
                )
                val objectDistance = firstDistanceMeters(
                    alertObject,
                    "distance", "distanceMeters", "distance_meters", "dist", "dst",
                    "alertDistance", "alert_distance", "reportDistance", "report_distance",
                    "aheadDistance", "ahead_distance"
                )?.takeIf { it >= 0 }
                val objectRoad = firstString(alertObject, "road", "street", "roadName", "road_name")

                if (alertCode <= 0 && objectCode > 0) alertCode = objectCode
                if (rawAlertStr.isNullOrBlank() || rawAlertStr?.trim()?.startsWith("{") == true) rawAlertStr = objectType
                if (rawCandidate.isNullOrBlank()) rawCandidate = objectTitle
                if (alertDistanceMeters == null) alertDistanceMeters = objectDistance
                if (alertRoad.isNullOrBlank()) alertRoad = objectRoad
            }

            // Some builds send a list of upcoming alerts instead of one flat alert.
            // Do not blindly pick the first item: choose the closest/most important
            // warning so a nearby accident/camera is not hidden by a distant low
            // priority event.
            val alertArray = firstArray(json, "alerts", "warnings", "alertList", "alert_list", "alrs", "reports", "hazards", "events", "upcomingAlerts", "upcoming_alerts")
            if (alertArray != null) {
                var bestScore = Int.MIN_VALUE
                var bestCode = 0
                var bestTitle: String? = null
                var bestTypeText: String? = null
                var bestDist: Int? = null
                var bestRoad: String? = null

                for (i in 0 until alertArray.length()) {
                    val item = alertArray.optJSONObject(i) ?: continue
                    val itemCode = firstInt(item, "code", "alr", "typeCode", "type_code") ?: 0
                    val itemTitle = firstString(item, "title", "label", "description", "text", "message", "alert", "warning")
                    val itemType = firstString(item, "type", "kind", "category", "reportType", "report_type")
                    val itemSubtype = firstString(item, "subtype", "subType", "reportSubtype", "report_subtype", "hazardType", "hazard_type")
                    val itemIcon = firstString(item, "icon", "iconName", "icon_name")
                    val itemDist = firstDistanceMeters(item, "distance", "distanceMeters", "distance_meters", "dist", "dst", "reportDistance", "report_distance", "aheadDistance", "ahead_distance")?.takeIf { it >= 0 }
                    val itemRoad = firstString(item, "road", "street", "roadName", "road_name")
                    val mapped = when (itemCode) {
                        1 -> VietmapWarningType.POLICE
                        2 -> VietmapWarningType.SPEED_CAMERA
                        3 -> VietmapWarningType.TRAFFIC_JAM
                        4 -> VietmapWarningType.RED_LIGHT_CAMERA
                        5 -> VietmapWarningType.ACCIDENT
                        6 -> VietmapWarningType.CONSTRUCTION
                        7 -> VietmapWarningType.HAZARD
                        8 -> VietmapWarningType.TOLL_BOOTH
                        else -> mapWarningType(itemTitle, itemType, itemSubtype, itemIcon)
                    }
                    if (itemCode <= 0 && mapped == VietmapWarningType.NONE) continue

                    val score = WazeAlertPolicy.score(mapped, itemDist)
                    if (score > bestScore) {
                        bestScore = score
                        bestCode = itemCode
                        bestTitle = itemTitle
                        bestTypeText = itemType
                        bestDist = itemDist
                        bestRoad = itemRoad
                    }
                }

                if (bestScore != Int.MIN_VALUE) {
                    if (alertCode <= 0) alertCode = bestCode
                    if (rawAlertStr.isNullOrBlank()) rawAlertStr = bestTypeText
                    if (rawCandidate.isNullOrBlank()) rawCandidate = bestTitle
                    if (alertDistanceMeters == null) alertDistanceMeters = bestDist
                    if (alertRoad.isNullOrBlank()) alertRoad = bestRoad
                }
            }

            val alertRaw = rawAlertStr?.takeIf { !isIgnoredText(it) }?.trim()
            val alertTitleCandidate = rawCandidate?.takeIf { !isIgnoredText(it) }?.trim()

            val alertDescDefault = when (alertCode) {
                1 -> "Chốt CSGT"
                2 -> "Camera tốc độ"
                3 -> "Sự cố / Kẹt xe"
                4 -> "Camera vượt đèn đỏ"
                5 -> "Tai nạn phía trước"
                6 -> "Công trường đang thi công"
                7 -> "Chú ý nguy hiểm trên đường"
                8 -> "Trạm thu phí BOT"
                else -> null
            }

            var finalAlertDesc = alertTitleCandidate ?: alertDescDefault
            if (secondaryLimit != null && !finalAlertDesc.isNullOrBlank() && !finalAlertDesc.contains("km/h")) {
                finalAlertDesc += " ($secondaryLimit km/h)"
            }

            val explicitClearText = alertRaw?.let {
                it.equals("none", ignoreCase = true) ||
                it.equals("clear", ignoreCase = true) ||
                it.equals("off", ignoreCase = true) ||
                it.equals("0", ignoreCase = true)
            } == true

            val alertTextFieldPresent = hasAny(json, "alert", "alert_type", "warning", "hazard", "warningType", "alertDescription", "alert_description", "alert_title", "alertTitle")
            val clearFlag = json.has("clear") && json.optBoolean("clear", false)
            val emptyAlertListClear = alertArray != null && alertArray.length() == 0 &&
                hasAny(json, "alerts", "warnings", "alertList", "alert_list", "alrs", "reports", "hazards", "events", "upcomingAlerts", "upcoming_alerts")
            val isClearExplicit = clearFlag || explicitClearText || emptyAlertListClear ||
                (alertCodePresent && alertCode == 0 && !alertTextFieldPresent && alertArray == null)

            val alertType = if (!isClearExplicit) {
                when {
                    alertCode == 1 -> VietmapWarningType.POLICE
                    alertCode == 2 -> VietmapWarningType.SPEED_CAMERA
                    alertCode == 3 -> VietmapWarningType.TRAFFIC_JAM
                    alertCode == 4 -> VietmapWarningType.RED_LIGHT_CAMERA
                    alertCode == 5 -> VietmapWarningType.ACCIDENT
                    alertCode == 6 -> VietmapWarningType.CONSTRUCTION
                    alertCode == 7 -> VietmapWarningType.HAZARD
                    alertCode == 8 -> VietmapWarningType.TOLL_BOOTH
                    alertCode > 0 -> mapWarningType(alertRaw, finalAlertDesc)
                    !alertRaw.isNullOrBlank() -> mapWarningType(alertRaw, finalAlertDesc)
                    !finalAlertDesc.isNullOrBlank() -> mapWarningType(finalAlertDesc)
                    else -> VietmapWarningType.NONE
                }
            } else VietmapWarningType.NONE

            val hasActiveAlert = alertType != VietmapWarningType.NONE && !isClearExplicit
            val alertTitle = if (hasActiveAlert) (finalAlertDesc ?: alertType.label) else null
            val finalAlertDistance = alertDistanceMeters
            val finalRoad = alertRoad?.takeIf { !isIgnoredText(it) } ?: roadName

            val timeFormatted = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())

            Log.i(
                TAG,
                "WAZE_PARSE spd=$speed limit=$limit sec=$secondaryLimit turn=$turnCode action=$nextTurnAction " +
                    "turnDist=$distanceToTurnMeters eta=$etaTime road=$roadName nextRoad=$nextRoadName lane=$laneGuidance " +
                    "traffic=$trafficLevel delay=$trafficDelayMinutes alert=$alertType title=$alertTitle alertDist=$finalAlertDistance clear=$isClearExplicit"
            )

            VietmapStateRepository.mergeUpdate(
                speed = speed,
                limit = limit,
                secondaryLimit = secondaryLimit,
                turnCode = if (turnCodePresent) turnCode else null,
                turnAction = if (turnCodePresent || !rawTurnAction.isNullOrBlank()) nextTurnAction else null,
                turnDescription = if (turnCodePresent || !turnDescription.isNullOrBlank()) turnDescription else null,
                distanceToTurnMeters = if (turnDistancePresent) distanceToTurnMeters else null,
                etaTime = etaTime,
                remainingMinutes = remainingMinutes,
                remainingDistanceKm = remainingDistanceKm,
                alertDescription = if (hasActiveAlert) alertTitle else null,
                lastUpdatedFormatted = timeFormatted,
                distanceText = if (hasActiveAlert && finalAlertDistance != null && finalAlertDistance > 0) "${finalAlertDistance}m" else null,
                distanceMeters = if (hasActiveAlert) finalAlertDistance else null,
                warningType = if (hasActiveAlert) alertType else null,
                alertTitle = if (hasActiveAlert) alertTitle else null,
                roadName = finalRoad,
                nextRoadName = nextRoadName,
                laneGuidance = laneGuidance,
                trafficLevel = trafficLevel,
                trafficDelayMinutes = trafficDelayMinutes,
                clearAlert = isClearExplicit,
                source = "WAZE_HLP"
            )

            val state = VietmapStateRepository.alertState.value
            Log.d(
                TAG,
                "WAZE_STATE speed=${state.currentSpeed} limit=${state.speedLimit} warning=${state.warningType} " +
                    "title=${state.alertTitle} dist=${state.distanceText} road=${state.roadName} turn=${state.turnCode} " +
                    "eta=${state.etaTime} nextRoad=${state.nextRoadName} traffic=${state.trafficLevel}/${state.trafficDelayMinutes}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Waze Mod JSON message: $jsonStr", e)
        }
    }

    fun mapWarningType(vararg inputs: String?): VietmapWarningType {
        val combined = inputs.filterNotNull().joinToString(" ").lowercase()
        return when {
            // Safety warnings first. Some alert strings also contain maneuver words,
            // so hazard/camera classification must win before turn classification.
            combined.contains("đèn đỏ") || combined.contains("red_light") || combined.contains("red light") || combined.contains("camera_traffic") ->
                VietmapWarningType.RED_LIGHT_CAMERA
            combined.contains("speed_cam") || combined.contains("phạt nguội") || combined.contains("speed_camera") || combined.contains("camera_speed") || combined.contains("bắn tốc độ") || combined.contains("camera tốc độ") || combined.contains("speed camera") ->
                VietmapWarningType.SPEED_CAMERA
            combined.contains("police") || combined.contains("cảnh sát") || combined.contains("csgt") || combined.contains("chốt") || combined.contains("công an") ->
                VietmapWarningType.POLICE
            combined.contains("accident") || combined.contains("tai nạn") || combined.contains("va chạm") || combined.contains("crash") ->
                VietmapWarningType.ACCIDENT
            combined.contains("construction") || combined.contains("công trường") || combined.contains("thi công") || combined.contains("sửa đường") || combined.contains("roadworks") || combined.contains("road closed") || combined.contains("road closure") || combined.contains("closure") || combined.contains("đường đóng") || combined.contains("đóng đường") ->
                VietmapWarningType.CONSTRUCTION
            combined.contains("jam") || combined.contains("traffic jam") || combined.contains("kẹt xe") || combined.contains("ùn tắc") || combined.contains("tắc đường") ->
                VietmapWarningType.TRAFFIC_JAM
            combined.contains("school zone") || combined.contains("trường học") || combined.contains("khu vực trường") ||
            combined.contains("hazard") || combined.contains("nguy hiểm") || combined.contains("chướng ngại vật") || combined.contains("vật cản") ||
            combined.contains("ổ gà") || combined.contains("pothole") || combined.contains("xe dừng") || combined.contains("stopped vehicle") ||
            combined.contains("car on shoulder") || combined.contains("xe trên lề") || combined.contains("xe hỏng") ||
            combined.contains("blocked lane") || combined.contains("lane blocked") || combined.contains("làn bị chặn") ||
            combined.contains("bad weather") || combined.contains("thời tiết xấu") || combined.contains("mưa lớn") || combined.contains("sương mù") || combined.contains("flood") || combined.contains("ngập") ||
            combined.contains("đường sắt") || combined.contains("railroad") || combined.contains("railway") ||
            combined.contains("speed bump") || combined.contains("gờ giảm tốc") || combined.contains("sharp curve") || combined.contains("cua gấp") ||
            combined.contains("lane ending") || combined.contains("hết làn") || combined.contains("narrow bridge") || combined.contains("cầu hẹp") ->
                VietmapWarningType.HAZARD
            combined.contains("speed limit") || combined.contains("giới hạn tốc độ") || combined.contains("tốc độ tối đa") ->
                VietmapWarningType.SPEED_LIMIT_ZONE
            combined.contains("residential_end") || (combined.contains("khu dân cư") && (combined.contains("hết") || combined.contains("kết thúc"))) ->
                VietmapWarningType.RESIDENTIAL_END
            combined.contains("residential") || combined.contains("khu dân cư") || combined.contains("đô thị") ->
                VietmapWarningType.RESIDENTIAL_START
            combined.contains("end_no_overtaking") || (combined.contains("cấm vượt") && (combined.contains("hết") || combined.contains("kết thúc"))) ->
                VietmapWarningType.END_NO_OVERTAKING
            combined.contains("no_overtaking") || combined.contains("cấm vượt") ->
                VietmapWarningType.NO_OVERTAKING
            combined.contains("toll") || combined.contains("thu phí") || combined.contains("trạm bot") || combined.contains("etc") ->
                VietmapWarningType.TOLL_BOOTH
            combined.contains("tunnel") || combined.contains("hầm") || combined.contains("cầu vượt") || combined.contains("bridge") ->
                VietmapWarningType.TUNNEL
            combined.contains("xăng") || combined.contains("gas station") || combined.contains("fuel") ->
                VietmapWarningType.GAS_STATION

            // Navigation maneuvers after safety warnings.
            combined.contains("quay đầu") || combined.contains("u-turn") || combined.contains("uturn") || combined.contains("vòng lại") ->
                VietmapWarningType.UTURN
            combined.contains("rẽ trái") || combined.contains("quẹo trái") || combined.contains("turn_left") || combined.contains("turn left") ->
                VietmapWarningType.TURN_LEFT
            combined.contains("rẽ phải") || combined.contains("quẹo phải") || combined.contains("turn_right") || combined.contains("turn right") ->
                VietmapWarningType.TURN_RIGHT
            combined.contains("vòng xuyến") || combined.contains("bùng binh") || combined.contains("roundabout") ->
                VietmapWarningType.ROUNDABOUT
            combined.contains("chếch trái") || combined.contains("keep left") || combined.contains("keep_left") ->
                VietmapWarningType.KEEP_LEFT
            combined.contains("chếch phải") || combined.contains("keep right") || combined.contains("keep_right") ->
                VietmapWarningType.KEEP_RIGHT
            combined.contains("đi thẳng") || combined.contains("straight") ->
                VietmapWarningType.STRAIGHT
            else -> VietmapWarningType.NONE
        }
    }

    fun openWazeMod(context: Context) {
        val wazePackages = listOf(
            "com.waze.mod",
            "com.waze",
            "com.waze.vietnam",
            "com.waze.vn",
            "com.waze.c4a"
        )
        for (pkg in wazePackages) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
        }
        // Fallback: Open Play Store or generic Waze intent
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse("https://waze.com/ul")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot launch Waze", e)
        }
    }
}

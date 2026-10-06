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
    @Volatile private var serverStarted = false
    @Volatile private var serverStarting = false
    private var restartJob: Job? = null
    @Volatile private var currentSession: Long? = null
    @Volatile private var lastStateTs: Long = -1L
    @Volatile private var lastStateReceivedAtMs: Long = 0L

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
            serverStarting = false
            serverStarted = true
            restartJob?.cancel()
            restartJob = null
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
            currentSession = null
            lastStateTs = -1L
            lastStateReceivedAtMs = 0L
            VietmapStateRepository.beginHlpSession()
            VietmapStateRepository.updateConnection(true)

            // HLP/1: alrs and lan are opt-in fields. Without a dev declaration,
            // WazeMod falls back after ~500 ms and deliberately omits alrs.
            conn?.let { sendDeviceDeclaration(it) }
        }

        override fun onClose(conn: JvmWebSocket?, code: Int, reason: String?, remote: Boolean) {
            Log.i(TAG, "Waze Mod client closed: code=$code, reason=$reason, remote=$remote")
            if (connections.isEmpty()) {
                _isConnected.value = false
                _statusText.value = "Chờ Waze Mod kết nối (Cổng $WS_PORT)..."
                currentSession = null
                lastStateTs = -1L
                lastStateReceivedAtMs = 0L
                VietmapStateRepository.clearHlpAlerts()
                VietmapStateRepository.updateConnection(false)
            }
        }

        override fun onMessage(conn: JvmWebSocket?, message: String?) {
            if (message.isNullOrBlank()) return
            Log.d(TAG, "WAZE_RAW(server)=$message")
            handleIncomingMessage(message) { reply -> conn?.send(reply) }
        }

        override fun onError(conn: JvmWebSocket?, ex: Exception?) {
            Log.w(TAG, "Waze Mod server error: ${ex?.message}")
            if (conn == null) {
                serverStarting = false
                serverStarted = false
                _isConnected.value = false
                VietmapStateRepository.updateConnection(false)
                val bindError = ex is java.net.BindException
                if (bindError) {
                    Log.e(TAG, "Port $WS_PORT already bound; retrying listener after backoff")
                    _statusText.value = "Cổng $WS_PORT đang bận - đang thử lại..."
                } else {
                    _statusText.value = "Listener Waze lỗi - đang tự khôi phục..."
                }
                scheduleServerRestart(if (bindError) 5_000L else 1_500L)
            }
        }
    }

    private var keepAliveJob: Job? = null

    fun start() {
        if (server == null || (!serverStarted && !serverStarting)) {
            startServer()
        }
        // WazeMod is the WebSocket client. Do not connect this process back to
        // its own localhost server; that creates a false-positive connection.
        stopClientFallback()
        startKeepAliveMonitor()
    }

    fun stop() {
        stopKeepAliveMonitor()
        restartJob?.cancel()
        restartJob = null
        stopClientFallback()
        stopServer()
        currentSession = null
        lastStateTs = -1L
        lastStateReceivedAtMs = 0L
        VietmapStateRepository.clearHlpAlerts()
        VietmapStateRepository.updateConnection(false)
        _isConnected.value = false
        _statusText.value = "Đã dừng Server"
    }

    private fun startKeepAliveMonitor() {
        if (keepAliveJob?.isActive == true) return
        keepAliveJob = scope.launch {
            while (isActive) {
                delay(3000)
                try {
                    if (server == null || (!serverStarted && !serverStarting)) {
                        Log.i(TAG, "KeepAlive: listener unavailable, auto-restarting Waze Mod server...")
                        startServer()
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

    @Synchronized
    private fun startServer() {
        if (serverStarted || serverStarting) return
        try {
            stopServer()
            serverStarting = true
            _statusText.value = "Đang kết nối Waze Mod (Cổng $WS_PORT)..."
            val s = WazeHlpServer(WS_PORT)
            server = s
            s.start()
        } catch (e: Exception) {
            serverStarting = false
            serverStarted = false
            server = null
            Log.e(TAG, "Failed to start WazeHlpServer", e)
            val bindError = e is java.net.BindException
            _statusText.value = if (bindError) {
                "Cổng $WS_PORT đang bận - đang thử lại..."
            } else {
                "Lỗi Server: ${e.message}"
            }
            scheduleServerRestart(if (bindError) 5_000L else 1_500L)
        }
    }

    @Synchronized
    private fun stopServer() {
        serverStarting = false
        serverStarted = false
        try {
            server?.stop(500)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping server", e)
        }
        server = null
    }

    private fun scheduleServerRestart(delayMs: Long) {
        if (restartJob?.isActive == true) return
        restartJob = scope.launch {
            delay(delayMs)
            restartJob = null
            if (!serverStarted && !serverStarting) {
                startServer()
            }
        }
    }

    fun restartConnection() {
        Log.i(TAG, "Restarting Waze Mod connection...")
        _isConnected.value = false
        _statusText.value = "Đang khởi động lại..."
        stopClientFallback()
        restartJob?.cancel()
        restartJob = null
        stopServer()
        currentSession = null
        lastStateTs = -1L
        lastStateReceivedAtMs = 0L
        VietmapStateRepository.clearHlpAlerts()
        VietmapStateRepository.updateConnection(false)
        scheduleServerRestart(500L)
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
                    handleIncomingMessage(text) { reply -> webSocket.send(reply) }
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


    private fun buildDeviceDeclarationPayload(): String = JSONObject().apply {
        put("v", 1)
        put("t", "dev")
        put("name", "THTV-PRO")
        put("fw", "1.0")
        put("proto", JSONArray().put(1))
        put("can", JSONArray().apply {
            listOf("speed", "limit", "turn", "lanes", "street", "eta", "avgzone", "alerts")
                .forEach { put(it) }
        })
        put("want", JSONObject().apply {
            put("rate", 8)
            put("fields", JSONArray().apply {
                listOf(
                    "nav", "spd", "lim", "min", "over",
                    "trn", "trn2", "dst", "exit", "lan",
                    "st", "st2", "eta", "rmin", "rm", "rkm",
                    "avg", "avgL", "avgR", "avgP",
                    "alr", "alrD", "alrV", "alrS", "alrM", "alrs"
                ).forEach { put(it) }
            })
        })
    }.toString()

    private fun sendDeviceDeclaration(conn: JvmWebSocket) {
        try {
            val payload = buildDeviceDeclarationPayload()
            Log.i(TAG, "HLP_TX dev=$payload")
            conn.send(payload)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send HLP dev declaration", t)
        }
    }

    /** Handles HLP envelope before the state parser. */
    private fun handleIncomingMessage(jsonStr: String, reply: (String) -> Unit = {}) {
        try {
            val root = JSONObject(jsonStr)
            if (root.optInt("v", 1) != 1) return
            when (root.optString("t", "s")) {
                "ping" -> {
                    reply("{\"v\":1,\"t\":\"pong\"}")
                    return
                }
                "pong" -> return
                "hi" -> {
                    val sess = if (root.has("sess")) root.optLong("sess") else -1L
                    val previousSession = currentSession
                    if (sess >= 0L && sess != previousSession) {
                        currentSession = sess
                        lastStateTs = -1L
                        lastStateReceivedAtMs = 0L
                        VietmapStateRepository.beginHlpSession()

                        // If WazeMod restarts while the WebSocket stays alive, HLP requires
                        // the receiver to negotiate again for the new session. Avoid a
                        // duplicate declaration on the very first hi because onOpen already
                        // sent it.
                        if (previousSession != null) {
                            reply(buildDeviceDeclarationPayload())
                        }
                    }
                    _isConnected.value = true
                    VietmapStateRepository.updateConnection(true)
                    val fields = root.opt("fields")?.toString().orEmpty()
                    Log.i(TAG, "HLP_HI sess=$sess fields=$fields")
                    return
                }
                "bye" -> {
                    currentSession = null
                    lastStateTs = -1L
                    lastStateReceivedAtMs = 0L
                    _isConnected.value = false
                    _statusText.value = "Waze Mod đã kết thúc phiên dẫn đường"
                    VietmapStateRepository.clearHlpAlerts()
                    VietmapStateRepository.updateConnection(false)
                    return
                }
                "s" -> {
                    val ts = if (root.has("ts")) root.optLong("ts", -1L) else -1L
                    val now = System.currentTimeMillis()
                    if (ts >= 0L && lastStateTs >= 0L && ts < lastStateTs) {
                        val rollback = lastStateTs - ts
                        val receiveGap = if (lastStateReceivedAtMs > 0L) now - lastStateReceivedAtMs else Long.MAX_VALUE
                        val likelySessionReset = rollback > 30_000L || receiveGap > 2_000L
                        if (likelySessionReset) {
                            Log.i(TAG, "HLP timestamp reset detected: ts=$ts last=$lastStateTs gap=$receiveGap; accepting new stream")
                            lastStateTs = -1L
                            VietmapStateRepository.beginHlpSession()
                        } else {
                            Log.d(TAG, "Ignoring stale HLP state ts=$ts < $lastStateTs sess=$currentSession")
                            return
                        }
                    }
                    if (ts >= 0L) lastStateTs = ts
                    lastStateReceivedAtMs = now
                    _isConnected.value = true
                    VietmapStateRepository.updateConnection(true)
                    handleJsonMessage(jsonStr)
                }
                else -> return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Invalid HLP envelope: $jsonStr", t)
        }
    }

    /** HLP/1 v1 alert code -> broad HUD policy category. */
    fun alertCodeToWarningType(code: Int): VietmapWarningType = when (code) {
        1 -> VietmapWarningType.POLICE
        2 -> VietmapWarningType.SPEED_CAMERA
        3 -> VietmapWarningType.RED_LIGHT_CAMERA
        4 -> VietmapWarningType.HAZARD
        5 -> VietmapWarningType.ACCIDENT
        6 -> VietmapWarningType.TRAFFIC_JAM
        7 -> VietmapWarningType.HAZARD
        8, 22 -> VietmapWarningType.SPEED_LIMIT_ZONE
        9 -> VietmapWarningType.NO_OVERTAKING
        10 -> VietmapWarningType.END_NO_OVERTAKING
        12 -> VietmapWarningType.TOLL_BOOTH
        14 -> VietmapWarningType.CONSTRUCTION
        23 -> VietmapWarningType.RESIDENTIAL_START
        24 -> VietmapWarningType.RESIDENTIAL_END
        in 40..46 -> VietmapWarningType.SPEED_CAMERA
        else -> if (code > 0) VietmapWarningType.HAZARD else VietmapWarningType.NONE
    }

    fun alertCodeLabel(code: Int): String = when (code) {
        1 -> "Cảnh sát giao thông"
        2 -> "Camera tốc độ"
        3 -> "Camera đèn đỏ"
        4 -> "Nguy hiểm"
        5 -> "Tai nạn"
        6 -> "Kẹt xe"
        7 -> "Đường đóng"
        8 -> "Giảm giới hạn tốc độ"
        9 -> "Cấm vượt"
        10 -> "Hết cấm vượt"
        11 -> "Giao cắt đường sắt"
        12 -> "Trạm thu phí"
        13 -> "Xe dừng trên đường"
        14 -> "Công trường"
        15 -> "Ổ gà / sụt lún"
        16 -> "Thời tiết xấu"
        17 -> "Làn đường bị chặn"
        18 -> "Đường nguy hiểm"
        19 -> "Lối ra cao tốc"
        20 -> "Trạm dừng cao tốc"
        21 -> "Trạm dừng nghỉ"
        22 -> "Hết hạn chế tốc độ"
        23 -> "Bắt đầu khu dân cư"
        24 -> "Hết khu dân cư"
        25 -> "Hết mọi lệnh cấm"
        26 -> "Cấm ô tô"
        27 -> "Cấm xe máy"
        28 -> "Cấm rẽ trái"
        29 -> "Cấm rẽ phải"
        30 -> "Cấm quay đầu"
        31 -> "Cấm đi thẳng"
        32 -> "Bắt buộc đi thẳng"
        33 -> "Bắt buộc rẽ phải"
        34 -> "Bắt buộc rẽ trái"
        35 -> "Làn ô tô"
        36 -> "Làn xe máy"
        37 -> "Đường một chiều"
        38 -> "Đường cấm"
        39 -> "Biển cấm kết hợp"
        40 -> "Camera phạt nguội"
        41 -> "Camera mô hình"
        42 -> "Camera dây an toàn"
        43 -> "Camera khoảng cách"
        44 -> "Camera làn xe buýt"
        45 -> "Camera tiếng ồn"
        46 -> "Camera biển STOP"
        47 -> "Động vật trên đường"
        48 -> "Vật cản trên đường"
        49 -> "Xác động vật"
        50 -> "Ngập nước"
        51 -> "Sương mù"
        52 -> "Mưa đá"
        53 -> "Tuyết"
        54 -> "Băng tuyết"
        55 -> "Đường trơn"
        56 -> "Gờ giảm tốc"
        57 -> "Khu vực trường học"
        58 -> "Nhập làn"
        59 -> "Khúc cua nguy hiểm"
        60 -> "Đường phân nhánh"
        61 -> "Đèn tín hiệu hỏng"
        62 -> "Người đi xe đạp"
        63 -> "Xe ưu tiên"
        64 -> "Cảnh báo an toàn"
        65 -> "Cấm thẳng và rẽ phải"
        66 -> "Cấm trái và quay đầu"
        67 -> "Cấm thẳng và rẽ trái"
        68 -> "Cấm trái và rẽ phải"
        69 -> "Ô tô cấm trái và quay đầu"
        70 -> "Ô tô cấm phải và quay đầu"
        71 -> "Cấm phải và quay đầu"
        72 -> "Ô tô cấm rẽ trái"
        73 -> "Ô tô cấm rẽ phải"
        74 -> "Ô tô cấm quay đầu"
        75 -> "Đèn giao thông"
        else -> "Cảnh báo"
    }

    fun alertCodeEmoji(code: Int): String = when (code) {
        1 -> "👮"
        2, in 40..46 -> "📷"
        3 -> "📷🚦"
        61, 75 -> "🚦"
        5 -> "💥"
        6 -> "🚗"
        7, in 26..39, in 65..74 -> "⛔"
        8, 22 -> "⭕"
        9 -> "🚫"
        10, 25 -> "✅"
        11 -> "🚂"
        12 -> "💰"
        13 -> "🚙"
        14 -> "🚧"
        15 -> "🕳️"
        16, 51, 52, 53, 54, 55 -> "🌧️"
        19 -> "↗️"
        20, 21 -> "🅿️"
        23 -> "🏘️"
        24 -> "🛣️"
        47 -> "🐄"
        48 -> "⚠️"
        49 -> "🐾"
        50 -> "🌊"
        56 -> "〰️"
        57 -> "🏫"
        58, 60 -> "🔀"
        59 -> "↪️"
        62 -> "🚴"
        63 -> "🚑"
        64 -> "🛡️"
        else -> "⚠️"
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
        firstString(json, "laneGuidance", "lane_guidance", "lane", "lanes", "lan", "ln")?.let { return it }
        val arr = firstArray(json, "lan", "lanes", "laneGuidance", "lane_guidance") ?: return null
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
            var secondaryLimit = firstInt(json, "secondarySpeedLimit", "secondary_speed_limit", "nextSpeedLimit", "next_speed_limit")
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
                    1 -> "straight"
                    2 -> "turn-left"
                    3 -> "turn-right"
                    4 -> "slight-left"
                    5 -> "slight-right"
                    6 -> "sharp-left"
                    7 -> "sharp-right"
                    8, 9 -> "u-turn"
                    10, 11, 12, 19, 20 -> "roundabout"
                    13 -> "keep-left"
                    14 -> "keep-right"
                    15 -> "exit-left"
                    16 -> "exit-right"
                    17 -> "destination"
                    else -> "straight"
                }
            }

            val defaultTurnDesc = when (turnCode) {
                1 -> "Đi thẳng"
                2 -> "Rẽ trái"
                3 -> "Rẽ phải"
                4 -> "Chếch sang trái"
                5 -> "Chếch sang phải"
                6 -> "Rẽ gắt sang trái"
                7 -> "Rẽ gắt sang phải"
                8, 9 -> "Quay đầu"
                10 -> "Vào vòng xuyến"
                11 -> "Vòng xuyến rẽ trái"
                12 -> "Vòng xuyến rẽ phải"
                13 -> "Giữ trái"
                14 -> "Giữ phải"
                15 -> "Lối ra bên trái"
                16 -> "Lối ra bên phải"
                17 -> "Đến nơi"
                18 -> "Đi phà"
                19 -> "Đi thẳng qua vòng xuyến"
                20 -> "Vòng xuyến quay đầu"
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
            val alertCodePresent = hasAny(json, "alr", "alertCode", "alert_code")
            var alertCode = firstInt(json, "alr", "alertCode", "alert_code") ?: 0
            var alertValue = firstInt(json, "alrV", "alertValue", "alert_value")
            var alertJamSeverity = firstInt(json, "alrS", "alertSeverity", "alert_severity")
            var alertJamDelay = firstInt(json, "alrM", "alertDelayMinutes", "alert_delay_minutes")

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
                val objectCode = firstInt(alertObject, "k", "code", "alr", "alertCode", "alert_code", "typeCode", "type_code") ?: 0
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
                    "d", "distance", "distanceMeters", "distance_meters", "dist", "dst",
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
            var upcomingAlerts: List<WazeAlertItem>? = null
            val alertArray = firstArray(json, "alrs", "alerts", "warnings", "alertList", "alert_list", "reports", "hazards", "events", "upcomingAlerts", "upcoming_alerts")
            if (alertArray != null) {
                val parsed = mutableListOf<WazeAlertItem>()
                for (i in 0 until alertArray.length()) {
                    val item = alertArray.optJSONObject(i) ?: continue
                    val itemCode = firstInt(item, "k", "code", "alr", "typeCode", "type_code") ?: 0
                    val itemTypeText = firstString(item, "type", "kind", "category", "reportType", "report_type")
                    val itemSubtype = firstString(item, "subtype", "subType", "reportSubtype", "report_subtype", "hazardType", "hazard_type")
                    val itemIcon = firstString(item, "icon", "iconName", "icon_name")
                    val itemTitleRaw = firstString(item, "title", "label", "description", "text", "message", "alert", "warning")
                    val itemDist = firstDistanceMeters(item, "d", "distance", "distanceMeters", "distance_meters", "dist", "dst", "reportDistance", "report_distance", "aheadDistance", "ahead_distance")?.takeIf { it >= 0 }
                    val itemValue = firstInt(item, "v", "value", "alertValue", "alert_value")
                    val itemSeverity = firstInt(item, "s", "severity", "jamSeverity", "jam_severity")
                    val itemDelay = firstInt(item, "m", "delay", "delayMinutes", "delay_minutes", "jamDelay", "jam_delay")
                    val itemRoad = firstString(item, "road", "street", "roadName", "road_name")
                    val mapped = if (itemCode > 0) alertCodeToWarningType(itemCode)
                    else mapWarningType(itemTitleRaw, itemTypeText, itemSubtype, itemIcon)
                    if (itemCode <= 0 && mapped == VietmapWarningType.NONE) continue
                    val itemTitle = itemTitleRaw ?: if (itemCode > 0) alertCodeLabel(itemCode) else mapped.label
                    parsed += WazeAlertItem(
                        code = itemCode,
                        warningType = mapped,
                        title = itemTitle,
                        distanceMeters = itemDist,
                        value = itemValue,
                        jamSeverity = itemSeverity,
                        jamDelayMinutes = itemDelay,
                        roadName = itemRoad
                    )
                }
                val deduped = WazeAlertPolicy.dedupeAlerts(parsed)
                upcomingAlerts = deduped.take(4)
                if (secondaryLimit == null) {
                    secondaryLimit = deduped.firstOrNull { it.code in setOf(8, 22) && (it.value ?: 0) > 0 }?.value
                }

                // Official alrs is already sorted near -> far. Keep that ordering
                // after removing duplicate copies of the same report.
                deduped.firstOrNull()?.let { first ->
                    alertCode = first.code
                    alertValue = first.value
                    alertJamSeverity = first.jamSeverity
                    alertJamDelay = first.jamDelayMinutes
                    rawCandidate = first.title
                    alertDistanceMeters = first.distanceMeters
                    alertRoad = first.roadName
                }
            }

            val alertRaw = rawAlertStr?.takeIf { !isIgnoredText(it) }?.trim()
            val alertTitleCandidate = rawCandidate?.takeIf { !isIgnoredText(it) }?.trim()

            val alertDescDefault = alertCode.takeIf { it > 0 }?.let(::alertCodeLabel)

            if (secondaryLimit == null && alertCode in setOf(8, 22)) {
                secondaryLimit = alertValue?.takeIf { it > 0 }
            }
            var finalAlertDesc = alertTitleCandidate ?: alertDescDefault
            alertValue?.takeIf { alertCode in setOf(8, 22) && it > 0 }?.let { value ->
                if (!finalAlertDesc.isNullOrBlank() && !finalAlertDesc.contains("km/h")) {
                    finalAlertDesc += " ($value km/h)"
                }
            }
            alertJamDelay?.takeIf { alertCode == 6 && it > 0 }?.let { delay ->
                if (!finalAlertDesc.isNullOrBlank()) {
                    finalAlertDesc += " (+$delay phút)"
                }
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
                    alertCode > 0 -> alertCodeToWarningType(alertCode)
                    !alertRaw.isNullOrBlank() -> mapWarningType(alertRaw, finalAlertDesc)
                    !finalAlertDesc.isNullOrBlank() -> mapWarningType(finalAlertDesc)
                    else -> VietmapWarningType.NONE
                }
            } else VietmapWarningType.NONE

            val hasActiveAlert = alertType != VietmapWarningType.NONE && !isClearExplicit
            val alertTitle = if (hasActiveAlert) (finalAlertDesc ?: alertType.label) else null
            val finalAlertDistance = alertDistanceMeters
            val finalRoad = alertRoad?.takeIf { !isIgnoredText(it) } ?: roadName
            if (upcomingAlerts == null && hasActiveAlert) {
                upcomingAlerts = listOf(
                    WazeAlertItem(
                        code = alertCode,
                        warningType = alertType,
                        title = alertTitle,
                        distanceMeters = finalAlertDistance,
                        value = alertValue,
                        jamSeverity = alertJamSeverity,
                        jamDelayMinutes = alertJamDelay,
                        roadName = finalRoad
                    )
                )
            }

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
                upcomingAlerts = upcomingAlerts,
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

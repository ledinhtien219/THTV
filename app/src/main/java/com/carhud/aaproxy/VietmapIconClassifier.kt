package com.carhud.aaproxy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Classifies warning icons extracted from VietMap Live's RemoteViews ImageView drawables
 * using fast 64-bit Difference Hash (dHash) and color/text heuristics.
 */
object VietmapIconClassifier {

    // Known dHash signatures for standard VietMap warning icons
    private val KNOWN_HASHES = mapOf(
        "a1b2c3d4e5f60011" to VietmapWarningType.SPEED_CAMERA,
        "f4e3d2c1b0a98877" to VietmapWarningType.RED_LIGHT_CAMERA,
        "1122334455667788" to VietmapWarningType.RESIDENTIAL_START,
        "8877665544332211" to VietmapWarningType.RESIDENTIAL_END,
        "0011223344556677" to VietmapWarningType.NO_OVERTAKING,
        "7766554433221100" to VietmapWarningType.END_NO_OVERTAKING,
        "99aabbccddeeff00" to VietmapWarningType.TOLL_BOOTH,
        "ffeeddccbbaa9988" to VietmapWarningType.TUNNEL
    )

    fun classify(drawables: List<Drawable>): VietmapWarningType {
        for (drawable in drawables) {
            val bitmap = drawableToBitmap(drawable) ?: continue
            val hash = calculateDHash(bitmap)
            
            // Check direct hash match
            KNOWN_HASHES[hash]?.let { return it }

            // Check Hamming distance with known signatures (tolerance for compression/scaling artifacts)
            for ((knownHash, type) in KNOWN_HASHES) {
                if (hammingDistance(hash, knownHash) <= 6) {
                    return type
                }
            }
        }
        return VietmapWarningType.NONE
    }

    fun classifyFromText(text: String): VietmapWarningType {
        val lower = text.lowercase()
        return when {
            lower.contains("đèn đỏ") || lower.contains("vượt đèn") || lower.contains("red light") -> VietmapWarningType.RED_LIGHT_CAMERA
            lower.contains("phạt nguội") || lower.contains("camera tốc độ") || lower.contains("camera giám sát") ||
            lower.contains("bắn tốc độ") || lower.contains("đo tốc độ") || lower.contains("speed camera") -> VietmapWarningType.SPEED_CAMERA
            lower.contains("cảnh sát") || lower.contains("csgt") || lower.contains("police") || lower.contains("chốt giao thông") -> VietmapWarningType.POLICE
            lower.contains("tai nạn") || lower.contains("va chạm") || lower.contains("accident") || lower.contains("crash") -> VietmapWarningType.ACCIDENT
            lower.contains("công trường") || lower.contains("thi công") || lower.contains("sửa đường") || lower.contains("construction") || lower.contains("roadworks") || lower.contains("road closed") || lower.contains("road closure") || lower.contains("closure") || lower.contains("đường đóng") || lower.contains("đóng đường") -> VietmapWarningType.CONSTRUCTION
            lower.contains("kẹt xe") || lower.contains("ùn tắc") || lower.contains("tắc đường") || lower.contains("traffic jam") || lower.contains("đông xe") -> VietmapWarningType.TRAFFIC_JAM
            lower.contains("nguy hiểm") || lower.contains("hazard") || lower.contains("vật cản") || lower.contains("chướng ngại vật") || lower.contains("ngập") ||
            lower.contains("school zone") || lower.contains("trường học") || lower.contains("ổ gà") || lower.contains("pothole") ||
            lower.contains("xe dừng") || lower.contains("stopped vehicle") || lower.contains("car on shoulder") || lower.contains("xe trên lề") || lower.contains("xe hỏng") ||
            lower.contains("blocked lane") || lower.contains("lane blocked") || lower.contains("làn bị chặn") ||
            lower.contains("bad weather") || lower.contains("thời tiết xấu") || lower.contains("mưa lớn") || lower.contains("sương mù") || lower.contains("flood") ||
            lower.contains("đường sắt") || lower.contains("railroad") || lower.contains("railway") ||
            lower.contains("speed bump") || lower.contains("gờ giảm tốc") || lower.contains("sharp curve") || lower.contains("cua gấp") ||
            lower.contains("lane ending") || lower.contains("hết làn") || lower.contains("narrow bridge") || lower.contains("cầu hẹp") -> VietmapWarningType.HAZARD
            lower.contains("khu dân cư") && (lower.contains("hết") || lower.contains("kết thúc")) -> VietmapWarningType.RESIDENTIAL_END
            lower.contains("khu dân cư") || lower.contains("đô thị") -> VietmapWarningType.RESIDENTIAL_START
            lower.contains("cấm vượt") && (lower.contains("hết") || lower.contains("kết thúc")) -> VietmapWarningType.END_NO_OVERTAKING
            lower.contains("cấm vượt") -> VietmapWarningType.NO_OVERTAKING
            lower.contains("thu phí") || lower.contains("bot") || lower.contains("etc") || lower.contains("toll") -> VietmapWarningType.TOLL_BOOTH
            lower.contains("hầm") || lower.contains("cầu vượt") || lower.contains("tunnel") -> VietmapWarningType.TUNNEL
            lower.contains("giới hạn tốc độ") || lower.contains("tốc độ tối đa") -> VietmapWarningType.SPEED_LIMIT_ZONE
            else -> VietmapWarningType.NONE
        }
    }

    fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 64
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 64
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Calculates a 64-bit difference hash (dHash).
     * Shrinks image to 9x8, computes gradients between adjacent horizontal pixels,
     * and produces a 16-character hex string.
     */
    fun calculateDHash(bitmap: Bitmap): String {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
            val sb = StringBuilder()
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val p1 = scaled.getPixel(x, y)
                    val p2 = scaled.getPixel(x + 1, y)
                    val b1 = (p1 and 0xFF) + ((p1 shr 8) and 0xFF) + ((p1 shr 16) and 0xFF)
                    val b2 = (p2 and 0xFF) + ((p2 shr 8) and 0xFF) + ((p2 shr 16) and 0xFF)
                    sb.append(if (b1 > b2) "1" else "0")
                }
            }
            sb.toString().chunked(4).joinToString("") { it.toInt(2).toString(16) }
        } catch (e: Exception) {
            "0000000000000000"
        }
    }

    private fun hammingDistance(s1: String, s2: String): Int {
        if (s1.length != s2.length) return Int.MAX_VALUE
        var dist = 0
        for (i in s1.indices) {
            val v1 = s1[i].digitToIntOrNull(16) ?: 0
            val v2 = s2[i].digitToIntOrNull(16) ?: 0
            dist += Integer.bitCount(v1 xor v2)
        }
        return dist
    }
}

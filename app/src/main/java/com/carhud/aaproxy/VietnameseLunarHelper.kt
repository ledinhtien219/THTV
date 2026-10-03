package com.carhud.aaproxy

import java.util.*
import kotlin.math.*

object VietnameseLunarHelper {

    private fun jdFromDate(d: Int, m: Int, y: Int): Int {
        val a = (14 - m) / 12
        val y1 = y + 4800 - a
        val m1 = m + 12 * a - 3
        return d + (153 * m1 + 2) / 5 + 365 * y1 + y1 / 4 - y1 / 100 + y1 / 400 - 32045
    }

    private fun newMoon(k: Int): Double {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val jd = 2415020.75933 + 29.53058868 * k + 0.0001178 * t2 - 0.000000155 * t3
        val m = 359.2242 + 29.10535608 * k - 0.0000333 * t2 - 0.00000347 * t3
        val mPrime = 306.0253 + 385.81691806 * k + 0.0107306 * t2 + 0.00001236 * t3
        val f = 21.2964 + 390.67050646 * k - 0.0016528 * t2 - 0.00000239 * t3

        val rad = Math.PI / 180.0
        val c1 = (0.1734 - 0.000393 * t) * sin(m * rad) + 0.0021 * sin(2 * m * rad)
        val c2 = -0.4068 * sin(mPrime * rad) + 0.0161 * sin(2 * mPrime * rad)
        val c3 = -0.0004 * sin(3 * mPrime * rad)
        val c4 = 0.0104 * sin(2 * f * rad) - 0.0051 * sin((m + mPrime) * rad)
        val c5 = -0.0074 * sin((m - mPrime) * rad) + 0.0004 * sin((2 * f + m) * rad)
        val c6 = -0.0004 * sin((2 * f - m) * rad) - 0.0006 * sin((2 * f + mPrime) * rad)
        val c7 = 0.0010 * sin((2 * f - mPrime) * rad) + 0.0005 * sin((m + 2 * mPrime) * rad)
        val deltaJd = c1 + c2 + c3 + c4 + c5 + c6 + c7
        return jd + deltaJd
    }

    private fun sunLongitude(jdn: Double): Double {
        val t = (jdn - 2451545.0) / 36525.0
        val t2 = t * t
        val rad = Math.PI / 180.0
        val l0 = 280.46645 + 36000.76983 * t + 0.0003032 * t2
        val m = 357.52910 + 35999.05030 * t - 0.0001559 * t2 - 0.00000048 * t * t2
        val c = (1.914600 - 0.004817 * t - 0.000014 * t2) * sin(m * rad) +
                (0.019993 - 0.000101 * t) * sin(2 * m * rad) +
                0.000290 * sin(3 * m * rad)
        var theta = l0 + c
        theta %= 360.0
        if (theta < 0) theta += 360.0
        return theta
    }

    private fun getSunLongitude(dayNumber: Int, timeZone: Double): Int {
        return (sunLongitude(dayNumber - 0.5 - timeZone / 24.0) / 30.0).toInt()
    }

    private fun getLunarMonth11(yy: Int, timeZone: Double): Int {
        val off = jdFromDate(31, 12, yy) - 2415021
        val k = (off / 29.530588853).toInt()
        var nm = (newMoon(k) + 0.5 + timeZone / 24.0).toInt()
        val sunLong = getSunLongitude(nm, timeZone)
        if (sunLong >= 9) {
            nm = (newMoon(k - 1) + 0.5 + timeZone / 24.0).toInt()
        }
        return nm
    }

    fun convertSolar2Lunar(dd: Int, mm: Int, yy: Int, timeZone: Double = 7.0): Pair<Int, Int> {
        val dayNumber = jdFromDate(dd, mm, yy)
        var k = ((dayNumber - 2415021.076998695) / 29.530588853).toInt()
        var monthStart = (newMoon(k) + 0.5 + timeZone / 24.0).toInt()
        if (monthStart > dayNumber) {
            k--
            monthStart = (newMoon(k) + 0.5 + timeZone / 24.0).toInt()
        }
        var a11 = getLunarMonth11(yy, timeZone)
        if (a11 >= monthStart) {
            a11 = getLunarMonth11(yy - 1, timeZone)
        }
        val lunarDay = dayNumber - monthStart + 1
        val diff = ((monthStart - a11) / 29.0).toInt()
        val lunarMonth = (diff + 11 - 1) % 12 + 1
        return Pair(lunarDay, lunarMonth)
    }

    fun getFormattedDateWithLunar(calendar: Calendar = Calendar.getInstance()): String {
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val year = calendar.get(Calendar.YEAR)

        val (lunarDay, lunarMonth) = try {
            convertSolar2Lunar(day, month, year)
        } catch (e: Exception) {
            Pair(day, month)
        }

        val lDayStr = if (lunarDay < 10) "0$lunarDay" else "$lunarDay"
        val lMonthStr = if (lunarMonth < 10) "0$lunarMonth" else "$lunarMonth"
        val dStr = if (day < 10) "0$day" else "$day"

        return "$dStr Tháng $month • $lDayStr/$lMonthStr ÂL"
    }

    private val CAN = arrayOf("Canh", "Tân", "Nhâm", "Quý", "Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ")
    private val CHI = arrayOf("Thân", "Dậu", "Tuất", "Hợi", "Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi")

    fun getCanChiYear(year: Int): String {
        val can = CAN[year % 10]
        val chi = CHI[year % 12]
        return "Năm $can $chi"
    }

    fun getFormattedSolarDate(calendar: Calendar = Calendar.getInstance()): String {
        val dow = when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "Chủ Nhật"
            Calendar.MONDAY -> "Thứ Hai"
            Calendar.TUESDAY -> "Thứ Ba"
            Calendar.WEDNESDAY -> "Thứ Tư"
            Calendar.THURSDAY -> "Thứ Năm"
            Calendar.FRIDAY -> "Thứ Sáu"
            Calendar.SATURDAY -> "Thứ Bảy"
            else -> "Thứ Hai"
        }
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val year = calendar.get(Calendar.YEAR)
        val dStr = if (day < 10) "0$day" else "$day"
        val mStr = if (month < 10) "0$month" else "$month"
        return "$dow, $dStr/$mStr/$year"
    }

    fun getFormattedLunarWithCanChi(calendar: Calendar = Calendar.getInstance()): String {
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val year = calendar.get(Calendar.YEAR)

        val (lunarDay, lunarMonth) = try {
            convertSolar2Lunar(day, month, year)
        } catch (e: Exception) {
            Pair(day, month)
        }

        val lDayStr = if (lunarDay < 10) "0$lunarDay" else "$lunarDay"
        val lMonthStr = if (lunarMonth < 10) "0$lunarMonth" else "$lunarMonth"
        return "ÂL: $lDayStr/$lMonthStr - ${getCanChiYear(year)}"
    }

    fun getGreeting(calendar: Calendar = Calendar.getInstance(), userName: String = "Phạm Nam"): String {
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val prefix = when (hour) {
            in 5..10 -> "Chào buổi sáng"
            in 11..13 -> "Chào buổi trưa"
            in 14..17 -> "Chào buổi chiều"
            else -> "Chào buổi tối"
        }
        return if (userName.isNotBlank()) "$prefix, $userName" else prefix
    }
}

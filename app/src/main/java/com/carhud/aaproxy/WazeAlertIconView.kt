package com.carhud.aaproxy

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Compact traffic-alert icon set drawn locally for the THTV HUD.
 *
 * The visual language follows the approved Waze-like preview: bold dark outline,
 * bright circular badge, high contrast and no emoji dependency. Everything is
 * Canvas-based so the icons stay sharp on Android Auto at any HUD scale.
 */
class WazeAlertIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Kind {
        SPEED_CAMERA,
        RED_LIGHT_CAMERA,
        POLICE,
        TRAFFIC_LIGHT,
        CONSTRUCTION,
        RAIL_CROSSING,
        HAZARD,
        TRAFFIC_JAM,
        ACCIDENT,
        NO_OVERTAKING,
        END_NO_OVERTAKING,
        RESIDENTIAL,
        SPEED_LIMIT
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var kind: Kind = Kind.HAZARD
    private var speedLimitValue: Int? = null

    private val dark = Color.rgb(9, 18, 31)
    private val white = Color.rgb(248, 250, 252)
    private val blue = Color.rgb(56, 189, 248)
    private val blueDeep = Color.rgb(37, 99, 235)
    private val red = Color.rgb(244, 63, 94)
    private val redStrong = Color.rgb(239, 68, 68)
    private val yellow = Color.rgb(251, 191, 36)
    private val orange = Color.rgb(251, 146, 60)
    private val green = Color.rgb(34, 197, 94)
    private val beige = Color.rgb(254, 215, 170)
    private val gray = Color.rgb(148, 163, 184)

    fun setAlert(code: Int, warningType: VietmapWarningType, value: Int? = null) {
        kind = resolveKind(code, warningType)
        speedLimitValue = if (kind == Kind.SPEED_LIMIT) value?.takeIf { it > 0 } else null
        contentDescription = WazeHlpWebSocketManager.alertCodeLabel(code.takeIf { it > 0 } ?: when (warningType) {
            VietmapWarningType.POLICE -> 1
            VietmapWarningType.SPEED_CAMERA -> 2
            VietmapWarningType.RED_LIGHT_CAMERA -> 3
            VietmapWarningType.ACCIDENT -> 5
            VietmapWarningType.TRAFFIC_JAM -> 6
            VietmapWarningType.NO_OVERTAKING -> 9
            VietmapWarningType.END_NO_OVERTAKING -> 10
            VietmapWarningType.CONSTRUCTION -> 14
            VietmapWarningType.RESIDENTIAL_START -> 23
            VietmapWarningType.RESIDENTIAL_END -> 24
            else -> 4
        })
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (44f * resources.displayMetrics.density).toInt()
        val w = resolveSize(desired, widthMeasureSpec)
        val h = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val scale = side / 100f
        canvas.save()
        canvas.translate((width - side) / 2f, (height - side) / 2f)
        canvas.scale(scale, scale)

        when (kind) {
            Kind.SPEED_CAMERA -> drawSpeedCamera(canvas, false)
            Kind.RED_LIGHT_CAMERA -> drawSpeedCamera(canvas, true)
            Kind.POLICE -> drawPolice(canvas)
            Kind.TRAFFIC_LIGHT -> drawTrafficLight(canvas)
            Kind.CONSTRUCTION -> drawConstruction(canvas)
            Kind.RAIL_CROSSING -> drawRailCrossing(canvas)
            Kind.HAZARD -> drawHazard(canvas)
            Kind.TRAFFIC_JAM -> drawTrafficJam(canvas)
            Kind.ACCIDENT -> drawAccident(canvas)
            Kind.NO_OVERTAKING -> drawNoOvertaking(canvas, false)
            Kind.END_NO_OVERTAKING -> drawNoOvertaking(canvas, true)
            Kind.RESIDENTIAL -> drawResidential(canvas)
            Kind.SPEED_LIMIT -> drawSpeedLimit(canvas)
        }
        canvas.restore()
    }

    private fun resolveKind(code: Int, warningType: VietmapWarningType): Kind = when {
        code == 1 -> Kind.POLICE
        code == 3 -> Kind.RED_LIGHT_CAMERA
        code == 2 || code in 40..46 -> Kind.SPEED_CAMERA
        code == 5 -> Kind.ACCIDENT
        code == 6 -> Kind.TRAFFIC_JAM
        code in setOf(8, 22) -> Kind.SPEED_LIMIT
        code == 9 -> Kind.NO_OVERTAKING
        code in setOf(10, 25) -> Kind.END_NO_OVERTAKING
        code == 11 -> Kind.RAIL_CROSSING
        code == 14 -> Kind.CONSTRUCTION
        code in setOf(23, 24) -> Kind.RESIDENTIAL
        code in setOf(61, 75) -> Kind.TRAFFIC_LIGHT
        warningType == VietmapWarningType.POLICE -> Kind.POLICE
        warningType == VietmapWarningType.SPEED_CAMERA -> Kind.SPEED_CAMERA
        warningType == VietmapWarningType.RED_LIGHT_CAMERA -> Kind.RED_LIGHT_CAMERA
        warningType == VietmapWarningType.ACCIDENT -> Kind.ACCIDENT
        warningType == VietmapWarningType.TRAFFIC_JAM -> Kind.TRAFFIC_JAM
        warningType == VietmapWarningType.CONSTRUCTION -> Kind.CONSTRUCTION
        warningType == VietmapWarningType.NO_OVERTAKING -> Kind.NO_OVERTAKING
        warningType == VietmapWarningType.END_NO_OVERTAKING -> Kind.END_NO_OVERTAKING
        warningType == VietmapWarningType.RESIDENTIAL_START || warningType == VietmapWarningType.RESIDENTIAL_END -> Kind.RESIDENTIAL
        warningType == VietmapWarningType.SPEED_LIMIT_ZONE -> Kind.SPEED_LIMIT
        else -> Kind.HAZARD
    }

    private fun fill(color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
    }

    private fun stroke(color: Int, width: Float) {
        outline.color = color
        outline.strokeWidth = width
    }

    private fun circleBadge(canvas: Canvas, color: Int) {
        fill(color)
        canvas.drawCircle(50f, 50f, 46f, paint)
        stroke(white, 3.5f)
        canvas.drawCircle(50f, 50f, 46f, outline)
    }

    private fun rr(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int, border: Int = dark, borderW: Float = 4f) {
        fill(color)
        canvas.drawRoundRect(RectF(l, t, r, b), rad, rad, paint)
        if (borderW > 0f) {
            stroke(border, borderW)
            canvas.drawRoundRect(RectF(l, t, r, b), rad, rad, outline)
        }
    }

    private fun drawSpeedCamera(canvas: Canvas, redLight: Boolean) {
        circleBadge(canvas, blue)
        rr(canvas, 14f, 28f, if (redLight) 68f else 74f, 66f, 7f, white, dark, 5f)
        fill(Color.rgb(186, 230, 253))
        canvas.drawRect(18f, 32f, if (redLight) 64f else 70f, 40f, paint)
        stroke(dark, 3.5f)
        canvas.drawCircle(43f, 49f, 14f, outline)
        fill(blueDeep)
        canvas.drawCircle(43f, 49f, 10f, paint)
        fill(Color.CYAN)
        canvas.drawCircle(39f, 45f, 3.5f, paint)
        fill(dark)
        canvas.drawCircle(24f, 36f, 3f, paint)
        rr(canvas, 40f, 66f, 48f, 82f, 2f, dark, dark, 0f)
        if (!redLight) {
            rr(canvas, 74f, 38f, 84f, 57f, 4f, white, dark, 4f)
        } else {
            drawMiniTrafficLight(canvas, 68f, 28f, 88f, 70f)
        }
    }

    private fun drawMiniTrafficLight(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        rr(canvas, l, t, r, b, 5f, dark, white, 3f)
        val cx = (l + r) / 2f
        val step = (b - t) / 4f
        val radius = (r - l) * 0.24f
        fill(redStrong); canvas.drawCircle(cx, t + step, radius, paint)
        fill(Color.rgb(250, 204, 21)); canvas.drawCircle(cx, t + step * 2f, radius, paint)
        fill(green); canvas.drawCircle(cx, t + step * 3f, radius, paint)
    }

    private fun drawPolice(canvas: Canvas) {
        circleBadge(canvas, yellow)
        fill(beige)
        canvas.drawCircle(50f, 57f, 22f, paint)
        stroke(dark, 5f)
        canvas.drawCircle(50f, 57f, 22f, outline)

        val cap = Path().apply {
            moveTo(24f, 43f)
            quadTo(28f, 22f, 50f, 20f)
            quadTo(72f, 22f, 76f, 43f)
            quadTo(50f, 50f, 24f, 43f)
            close()
        }
        fill(Color.rgb(254, 243, 199)); canvas.drawPath(cap, paint)
        stroke(dark, 5f); canvas.drawPath(cap, outline)
        fill(redStrong); canvas.drawRoundRect(RectF(27f, 39f, 73f, 45f), 3f, 3f, paint)
        fill(yellow); canvas.drawCircle(50f, 31f, 6f, paint)
        stroke(dark, 2.5f); canvas.drawCircle(50f, 31f, 6f, outline)
        fill(dark)
        canvas.drawCircle(42f, 58f, 2.7f, paint)
        canvas.drawCircle(58f, 58f, 2.7f, paint)
    }

    private fun drawTrafficLight(canvas: Canvas) {
        circleBadge(canvas, blue)
        rr(canvas, 34f, 12f, 66f, 87f, 10f, dark, white, 4f)
        fill(redStrong); canvas.drawCircle(50f, 31f, 9f, paint)
        fill(Color.rgb(250, 204, 21)); canvas.drawCircle(50f, 50f, 9f, paint)
        fill(green); canvas.drawCircle(50f, 69f, 9f, paint)
        rr(canvas, 46f, 87f, 54f, 96f, 2f, dark, dark, 0f)
    }

    private fun drawConstruction(canvas: Canvas) {
        circleBadge(canvas, orange)
        val dome = Path().apply {
            moveTo(23f, 62f)
            cubicTo(27f, 31f, 39f, 24f, 50f, 24f)
            cubicTo(61f, 24f, 73f, 31f, 77f, 62f)
            close()
        }
        fill(yellow); canvas.drawPath(dome, paint)
        stroke(dark, 5f); canvas.drawPath(dome, outline)
        rr(canvas, 18f, 59f, 82f, 72f, 7f, yellow, dark, 5f)
        stroke(Color.rgb(180, 83, 9), 3f)
        canvas.drawLine(40f, 30f, 40f, 58f, outline)
        canvas.drawLine(60f, 30f, 60f, 58f, outline)
    }

    private fun drawRailCrossing(canvas: Canvas) {
        circleBadge(canvas, red)
        rr(canvas, 47f, 45f, 53f, 89f, 2f, gray, dark, 2f)
        stroke(dark, 9f)
        canvas.drawLine(25f, 24f, 75f, 57f, outline)
        canvas.drawLine(75f, 24f, 25f, 57f, outline)
        stroke(white, 5f)
        canvas.drawLine(25f, 24f, 75f, 57f, outline)
        canvas.drawLine(75f, 24f, 25f, 57f, outline)
        fill(redStrong)
        canvas.drawCircle(39f, 68f, 9f, paint)
        canvas.drawCircle(61f, 68f, 9f, paint)
        stroke(dark, 4f)
        canvas.drawCircle(39f, 68f, 9f, outline)
        canvas.drawCircle(61f, 68f, 9f, outline)
    }

    private fun drawHazard(canvas: Canvas) {
        circleBadge(canvas, yellow)
        val tri = Path().apply {
            moveTo(50f, 18f)
            lineTo(82f, 76f)
            quadTo(84f, 81f, 77f, 82f)
            lineTo(23f, 82f)
            quadTo(16f, 81f, 19f, 75f)
            close()
        }
        fill(Color.rgb(253, 224, 71)); canvas.drawPath(tri, paint)
        stroke(dark, 5f); canvas.drawPath(tri, outline)
        fill(dark)
        canvas.drawRoundRect(RectF(46f, 38f, 54f, 62f), 4f, 4f, paint)
        canvas.drawCircle(50f, 70f, 4.5f, paint)
    }

    private fun drawTrafficJam(canvas: Canvas) {
        circleBadge(canvas, red)
        drawCar(canvas, 53f, 26f, 82f, 49f, blueDeep)
        drawCar(canvas, 38f, 39f, 72f, 64f, yellow)
        drawCar(canvas, 18f, 52f, 58f, 80f, redStrong)
    }

    private fun drawCar(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        rr(canvas, l, t + 7f, r, b, 5f, color, dark, 4f)
        val roof = Path().apply {
            moveTo(l + 7f, t + 9f)
            lineTo(l + 14f, t)
            lineTo(r - 8f, t)
            lineTo(r - 2f, t + 9f)
            close()
        }
        fill(color); canvas.drawPath(roof, paint)
        stroke(dark, 4f); canvas.drawPath(roof, outline)
        fill(white)
        canvas.drawRoundRect(RectF(l + 6f, b - 10f, l + 13f, b - 6f), 2f, 2f, paint)
        canvas.drawRoundRect(RectF(r - 13f, b - 10f, r - 6f, b - 6f), 2f, 2f, paint)
    }

    private fun drawAccident(canvas: Canvas) {
        circleBadge(canvas, red)
        drawCar(canvas, 10f, 50f, 49f, 76f, Color.rgb(14, 165, 233))
        drawCar(canvas, 51f, 50f, 90f, 76f, redStrong)
        val burst = Path()
        val pts = arrayOf(
            50f to 30f, 55f to 42f, 68f to 35f, 62f to 48f,
            76f to 50f, 62f to 56f, 69f to 70f, 55f to 62f,
            50f to 76f, 45f to 62f, 31f to 70f, 38f to 56f,
            24f to 50f, 38f to 45f, 31f to 35f, 45f to 42f
        )
        pts.forEachIndexed { i, p -> if (i == 0) burst.moveTo(p.first, p.second) else burst.lineTo(p.first, p.second) }
        burst.close()
        fill(yellow); canvas.drawPath(burst, paint)
        stroke(dark, 3f); canvas.drawPath(burst, outline)
    }

    private fun drawNoOvertaking(canvas: Canvas, ended: Boolean) {
        fill(white); canvas.drawCircle(50f, 50f, 45f, paint)
        stroke(if (ended) dark else redStrong, 5f); canvas.drawCircle(50f, 50f, 45f, outline)
        if (ended) {
            stroke(dark, 2.5f)
            for (x in 22..70 step 12) {
                canvas.drawLine(x.toFloat(), 84f, (x + 36).toFloat(), 16f, outline)
            }
        }
        drawSimpleCar(canvas, 25f, 47f, if (ended) gray else redStrong)
        drawSimpleCar(canvas, 55f, 47f, dark)
        if (!ended) {
            stroke(redStrong, 7f)
            canvas.drawLine(20f, 20f, 80f, 80f, outline)
        }
    }

    private fun drawSimpleCar(canvas: Canvas, x: Float, y: Float, color: Int) {
        rr(canvas, x, y, x + 22f, y + 17f, 4f, color, color, 0f)
        fill(color)
        val roof = Path().apply {
            moveTo(x + 4f, y + 2f)
            lineTo(x + 8f, y - 7f)
            lineTo(x + 17f, y - 7f)
            lineTo(x + 21f, y + 2f)
            close()
        }
        canvas.drawPath(roof, paint)
        fill(dark)
        canvas.drawCircle(x + 5f, y + 18f, 3f, paint)
        canvas.drawCircle(x + 18f, y + 18f, 3f, paint)
    }

    private fun drawResidential(canvas: Canvas) {
        circleBadge(canvas, blueDeep)
        fill(white)
        val roof = Path().apply {
            moveTo(20f, 53f)
            lineTo(49f, 27f)
            lineTo(76f, 53f)
            close()
        }
        canvas.drawPath(roof, paint)
        canvas.drawRect(27f, 50f, 70f, 78f, paint)
        fill(blueDeep)
        canvas.drawRect(45f, 59f, 56f, 78f, paint)
        fill(white)
        canvas.drawRect(33f, 58f, 41f, 66f, paint)
        canvas.drawCircle(80f, 47f, 9f, paint)
        canvas.drawRect(78f, 54f, 82f, 78f, paint)
        canvas.drawRect(19f, 78f, 86f, 83f, paint)
    }

    private fun drawSpeedLimit(canvas: Canvas) {
        fill(white); canvas.drawCircle(50f, 50f, 44f, paint)
        stroke(redStrong, 8f); canvas.drawCircle(50f, 50f, 39f, outline)
        val value = speedLimitValue?.toString() ?: "!"
        paint.color = dark
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = if (value.length >= 3) 29f else 34f
        canvas.drawText(value, 50f, 62f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textAlign = Paint.Align.LEFT
    }
}

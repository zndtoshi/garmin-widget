package com.zndtoshi.garminwidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.zndtoshi.garminwidget.data.ActivityHeartRatePoint
import com.zndtoshi.garminwidget.data.HrvTrendPoint
import com.zndtoshi.garminwidget.data.LastActivity
import com.zndtoshi.garminwidget.data.LocalStatus
import com.zndtoshi.garminwidget.data.SleepStages
import com.zndtoshi.garminwidget.data.TimelinePoint
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class RingSegment(
    val startDegrees: Float,
    val sweepDegrees: Float,
    val color: Int,
)

internal data class ChartPoint(
    val x: Float,
    val y: Float,
    val value: Int,
)

internal data class ChartGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val batteryPoints: List<ChartPoint>,
    val stressPoints: List<ChartPoint>,
)

/** Draw order for the combined Body Battery / stress chart (grid under bars under curve). */
internal enum class CombinedChartLayer {
    GRID,
    STRESS_BARS,
    BATTERY_CURVE,
    BATTERY_MARKER,
}

internal fun combinedChartDrawOrder(): List<CombinedChartLayer> = listOf(
    CombinedChartLayer.GRID,
    CombinedChartLayer.STRESS_BARS,
    CombinedChartLayer.BATTERY_CURVE,
    CombinedChartLayer.BATTERY_MARKER,
)

/** Garmin-inspired stress bar bands: rest/low vs active stress. */
internal enum class StressBarKind {
    REST,
    STRESS,
}

internal fun classifyStressBar(value: Int): StressBarKind? = when (value) {
    in 0..25 -> StressBarKind.REST
    in 26..100 -> StressBarKind.STRESS
    else -> null
}

internal fun stressBarColorArgb(value: Int): Int = when (classifyStressBar(value)) {
    StressBarKind.REST -> WidgetPalette.stressRest
    StressBarKind.STRESS -> WidgetPalette.stress
    null -> WidgetPalette.stress
}

internal fun countStressBarColors(values: Collection<Int>): Pair<Int, Int> {
    var rest = 0
    var stress = 0
    for (value in values) {
        when (classifyStressBar(value)) {
            StressBarKind.REST -> rest++
            StressBarKind.STRESS -> stress++
            null -> Unit
        }
    }
    return rest to stress
}

/**
 * Half-width for a stress bar so clustered samples stay legible instead of merging
 * into one solid block.
 */
internal fun stressBarHalfWidthPx(points: List<ChartPoint>, index: Int): Float {
    if (points.isEmpty()) return 1f
    if (points.size == 1) return 3f
    val x = points[index].x
    val leftGap = if (index > 0) x - points[index - 1].x else Float.POSITIVE_INFINITY
    val rightGap = if (index < points.lastIndex) points[index + 1].x - x else Float.POSITIVE_INFINITY
    val halfGap = min(leftGap, rightGap) * 0.4f
    return halfGap.coerceIn(0.55f, 1.35f)
}

internal object WidgetPalette {
    const val deep = 0xFF4A3FAE.toInt()
    const val light = 0xFF6FA8FF.toInt()
    const val rem = 0xFFB158D8.toInt()
    const val awake = 0xFFFFB74D.toInt()
    const val neutral = 0xFF607D8B.toInt()
    /** Garmin Connect–style Body Battery stroke (white/off-white). */
    const val battery = 0xFFF5F7FA.toInt()
    /** Very subtle fill under the Body Battery curve (not a strong cyan area). */
    const val batteryFill = 0x14FFFFFF
    /** Rest / low stress (0–25). */
    const val stressRest = 0xFF42A5F5.toInt()
    /** Elevated stress (26–100). */
    const val stress = 0xFFFFA726.toInt()
    const val hrvGreen = 0xFF4CAF50.toInt()
    const val hrvOrange = 0xFFFF9800.toInt()
    const val hrvRed = 0xFFF44336.toInt()
    const val hrvGray = 0xFF90A4AE.toInt()
    /** @see ACTIVITY_HR_LINE_NORMAL */
    const val activityHr = 0xFFC8D0D6.toInt()
    const val activityHrFill = 0x4452616B
    /** @see ACTIVITY_HR_LINE_PEAK */
    const val activityHrMax = 0xFFF4514F.toInt()
}

/**
 * Shape-preserving monotone cubic Hermite (Fritsch–Carlson) slopes for strictly
 * increasing abscissae. Equal neighboring values yield zero slopes (flat plateaus).
 */
internal fun fritschCarlsonSlopes(xs: FloatArray, ys: FloatArray): FloatArray {
    val n = xs.size
    require(n == ys.size)
    val m = FloatArray(n)
    if (n == 0) return m
    if (n == 1) return m

    val h = FloatArray(n - 1)
    val delta = FloatArray(n - 1)
    for (i in 0 until n - 1) {
        h[i] = xs[i + 1] - xs[i]
        delta[i] = if (h[i] > 0f) (ys[i + 1] - ys[i]) / h[i] else 0f
    }

    m[0] = delta[0]
    m[n - 1] = delta[n - 2]
    for (i in 1 until n - 1) {
        if (delta[i - 1] == 0f || delta[i] == 0f || delta[i - 1] * delta[i] < 0f) {
            m[i] = 0f
        } else {
            val w1 = 2f * h[i] + h[i - 1]
            val w2 = h[i] + 2f * h[i - 1]
            m[i] = (w1 + w2) / (w1 / delta[i - 1] + w2 / delta[i])
        }
    }
    return m
}

internal fun hermiteCubicY(
    x: Float,
    x0: Float,
    x1: Float,
    y0: Float,
    y1: Float,
    m0: Float,
    m1: Float,
): Float {
    val h = x1 - x0
    if (h <= 0f || !h.isFinite()) return y0
    val t = ((x - x0) / h).coerceIn(0f, 1f)
    val t2 = t * t
    val t3 = t2 * t
    val h00 = 2f * t3 - 3f * t2 + 1f
    val h10 = t3 - 2f * t2 + t
    val h01 = -2f * t3 + 3f * t2
    val h11 = t3 - t2
    val y = h00 * y0 + h10 * h * m0 + h01 * y1 + h11 * h * m1
    return if (y.isFinite()) y else y0
}

/**
 * Collapse duplicate X positions (keep last) so cubic segments have positive width.
 */
internal fun dedupeChartPointsByX(points: List<ChartPoint>): List<ChartPoint> {
    if (points.size <= 1) return points
    val out = ArrayList<ChartPoint>(points.size)
    for (p in points.sortedBy { it.x }) {
        if (out.isNotEmpty() && abs(out.last().x - p.x) < 1e-3f) {
            out[out.lastIndex] = p
        } else {
            out.add(p)
        }
    }
    return out
}

/**
 * Render-only dense samples along a monotone cubic through the measured Body Battery
 * points. Does not invent API/persisted measurements — geometry only for drawing.
 */
internal fun buildSmoothBatteryRenderPoints(
    measured: List<ChartPoint>,
    samplesPerSegmentHint: Int = 12,
): List<ChartPoint> {
    val pts = dedupeChartPointsByX(measured)
    if (pts.isEmpty()) return emptyList()
    if (pts.size == 1) return pts

    val xs = FloatArray(pts.size) { pts[it].x }
    val ys = FloatArray(pts.size) { pts[it].y }
    val values = FloatArray(pts.size) { pts[it].value.toFloat() }
    val ySlopes = fritschCarlsonSlopes(xs, ys)
    val vSlopes = fritschCarlsonSlopes(xs, values)

    val out = ArrayList<ChartPoint>(pts.size * (samplesPerSegmentHint + 1))
    out.add(pts.first())
    for (i in 0 until pts.size - 1) {
        val h = xs[i + 1] - xs[i]
        if (h <= 0f) continue
        val base = samplesPerSegmentHint.coerceIn(4, 24)
        val byWidth = (h / 4f).roundToInt()
        val samples = max(base, byWidth).coerceIn(4, 32)
        val lo = min(values[i], values[i + 1])
        val hi = max(values[i], values[i + 1])
        for (s in 1..samples) {
            val t = s.toFloat() / samples.toFloat()
            val x = xs[i] + t * h
            val y = hermiteCubicY(x, xs[i], xs[i + 1], ys[i], ys[i + 1], ySlopes[i], ySlopes[i + 1])
            val value = if (s == samples) {
                pts[i + 1].value
            } else {
                hermiteCubicY(x, xs[i], xs[i + 1], values[i], values[i + 1], vSlopes[i], vSlopes[i + 1])
                    .coerceIn(lo, hi)
                    .roundToInt()
            }
            out.add(ChartPoint(x = x, y = y, value = value))
        }
    }
    return out
}

/**
 * Evaluate monotone cubic in value space for shape-preservation tests.
 * Returns denser (x, value) samples; endpoints match the inputs exactly.
 */
internal fun sampleMonotoneCubicValues(
    xs: FloatArray,
    values: FloatArray,
    samplesPerSegment: Int = 12,
): List<Pair<Float, Float>> {
    require(xs.size == values.size)
    if (xs.isEmpty()) return emptyList()
    if (xs.size == 1) return listOf(xs[0] to values[0])

    val slopes = fritschCarlsonSlopes(xs, values)
    val out = ArrayList<Pair<Float, Float>>()
    out.add(xs[0] to values[0])
    for (i in 0 until xs.size - 1) {
        val h = xs[i + 1] - xs[i]
        if (h <= 0f) continue
        val samples = samplesPerSegment.coerceAtLeast(1)
        for (s in 1..samples) {
            val t = s.toFloat() / samples.toFloat()
            val x = xs[i] + t * h
            val y = hermiteCubicY(x, xs[i], xs[i + 1], values[i], values[i + 1], slopes[i], slopes[i + 1])
            out.add(x to y)
        }
    }
    return out
}

internal fun buildSleepRingSegments(stages: SleepStages?): List<RingSegment> {
    val entries = listOf(
        stages?.deepSeconds?.takeIf { it > 0 } to WidgetPalette.deep,
        stages?.lightSeconds?.takeIf { it > 0 } to WidgetPalette.light,
        stages?.remSeconds?.takeIf { it > 0 } to WidgetPalette.rem,
        stages?.awakeSeconds?.takeIf { it > 0 } to WidgetPalette.awake,
    ).filter { it.first != null }

    val total = entries.sumOf { it.first ?: 0 }
    if (total <= 0) return listOf(RingSegment(-90f, 360f, WidgetPalette.neutral))

    val gap = 2.5f
    val available = 360f - gap * entries.size
    var cursor = -90f
    return entries.map { (seconds, color) ->
        val sweep = (seconds!!.toFloat() / total.toFloat()) * available
        val segment = RingSegment(cursor, sweep, color)
        cursor += sweep + gap
        segment
    }
}

internal fun buildCombinedChartGeometry(
    widthPx: Int,
    heightPx: Int,
    battery: List<TimelinePoint>,
    stress: List<TimelinePoint>,
    rangeStart: Instant? = null,
    rangeEnd: Instant? = null,
): ChartGeometry {
    val w = max(1, widthPx)
    val h = max(1, heightPx)
    val left = 4f
    val top = 4f
    val right = (w - 4).toFloat().coerceAtLeast(left + 1f)
    val bottom = (h - 15).toFloat().coerceAtLeast(top + 1f)

    val sortedBattery = battery.sortedBy { it.timestamp }
    val sortedStress = stress.sortedBy { it.timestamp }
    val allTs = (sortedBattery + sortedStress).map { it.timestamp.toEpochMilli() }
    if (allTs.isEmpty()) {
        return ChartGeometry(w, h, left, top, right, bottom, emptyList(), emptyList())
    }

    val minTs = rangeStart?.toEpochMilli() ?: allTs.minOrNull()!!
    val maxTs = rangeEnd?.toEpochMilli() ?: allTs.maxOrNull()!!
    val span = (maxTs - minTs).coerceAtLeast(1L).toDouble()

    fun xOf(epochMs: Long): Float {
        val relative = (epochMs - minTs).toDouble() / span
        return (left + relative * (right - left)).toFloat()
    }

    fun yOf(value: Int): Float {
        val clamped = value.coerceIn(0, 100)
        return bottom - (clamped / 100f) * (bottom - top)
    }

    val batteryPoints = sortedBattery.map {
        ChartPoint(x = xOf(it.timestamp.toEpochMilli()), y = yOf(it.value), value = it.value)
    }
    val stressPoints = sortedStress.map {
        ChartPoint(x = xOf(it.timestamp.toEpochMilli()), y = yOf(it.value), value = it.value)
    }
    return ChartGeometry(w, h, left, top, right, bottom, batteryPoints, stressPoints)
}

internal fun drawSleepRingBitmap(sizePx: Int, segments: List<RingSegment>): Bitmap {
    val safe = sizePx.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(safe, safe, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val stroke = max(8f, safe * 0.12f)
    val rect = RectF(stroke, stroke, safe - stroke, safe - stroke)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = stroke
    }
    for (segment in segments) {
        paint.color = segment.color
        canvas.drawArc(rect, segment.startDegrees, segment.sweepDegrees, false, paint)
    }
    return bmp
}

internal data class HrvGraphSlot(
    val index: Int,
    val x: Float,
    val y: Float?,
    val rawValue: Int?,
    val marker: HrvMarkerKind,
)

internal data class HrvGraphGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val plotLeft: Float,
    val plotTop: Float,
    val plotRight: Float,
    val plotBottom: Float,
    val yAtMin: Float,
    val yAtMax: Float,
    val yAtMid: Float,
    val showMidLabel: Boolean,
    val slots: List<HrvGraphSlot>,
)

internal fun yForHrvMs(valueMs: Int, plotTop: Float, plotBottom: Float): Float {
    val clamped = clampHrvForScale(valueMs)
    val span = (HRV_SCALE_MAX_MS - HRV_SCALE_MIN_MS).toFloat()
    val t = (clamped - HRV_SCALE_MIN_MS).toFloat() / span
    return plotBottom - t * (plotBottom - plotTop)
}

internal fun buildHrvGraphGeometry(
    widthPx: Int,
    heightPx: Int,
    points: List<HrvTrendPoint>,
    maxPoints: Int,
    showMidLabel: Boolean,
): HrvGraphGeometry {
    val w = max(1, widthPx)
    val h = max(1, heightPx)
    val labelGutter = if (w >= 90) 24f else 18f
    val labelSize = max(9f, h * 0.15f)
    // Keep top/bottom padding so 80 / mid / 22 stay fully inside the bitmap.
    val plotLeft = labelGutter
    val plotTop = (labelSize * 0.85f).coerceAtLeast(8f)
    val plotRight = (w - 4).toFloat().coerceAtLeast(plotLeft + 1f)
    val plotBottom = (h - labelSize * 0.35f - 2f).coerceAtLeast(plotTop + 1f)
    val selected = pickRecentHrvPoints(points, maxPoints)
    val slots = selected.mapIndexed { index, point ->
        val xFraction = if (selected.size <= 1) 0.5f else index.toFloat() / (selected.size - 1).toFloat()
        val x = plotLeft + xFraction * (plotRight - plotLeft)
        val raw = hrvPlotValue(point)
        val marker = mapHrvPointToMarker(point)
        val y = raw?.let { yForHrvMs(it, plotTop, plotBottom) }
        HrvGraphSlot(index = index, x = x, y = y, rawValue = raw, marker = marker)
    }
    return HrvGraphGeometry(
        widthPx = w,
        heightPx = h,
        plotLeft = plotLeft,
        plotTop = plotTop,
        plotRight = plotRight,
        plotBottom = plotBottom,
        yAtMin = yForHrvMs(HRV_SCALE_MIN_MS, plotTop, plotBottom),
        yAtMax = yForHrvMs(HRV_SCALE_MAX_MS, plotTop, plotBottom),
        yAtMid = yForHrvMs((HRV_SCALE_MIN_MS + HRV_SCALE_MAX_MS) / 2, plotTop, plotBottom),
        showMidLabel = showMidLabel && (plotBottom - plotTop) >= 28f,
        slots = slots,
    )
}

internal fun drawHrvGraphBitmap(
    widthPx: Int,
    heightPx: Int,
    points: List<HrvTrendPoint>,
    maxPoints: Int,
    showMidLabel: Boolean,
): Bitmap {
    val geometry = buildHrvGraphGeometry(widthPx, heightPx, points, maxPoints, showMidLabel)
    val bmp = Bitmap.createBitmap(geometry.widthPx, geometry.heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9FB5BB.toInt()
        textSize = max(9f, geometry.heightPx * 0.15f)
    }

    fun drawGrid(y: Float) {
        canvas.drawLine(geometry.plotLeft, y, geometry.plotRight, y, gridPaint)
    }
    drawGrid(geometry.yAtMax)
    if (geometry.showMidLabel) drawGrid(geometry.yAtMid)
    drawGrid(geometry.yAtMin)

    // Anchor labels so the glyph stays inside the bitmap (no top/bottom clipping).
    canvas.drawText("80", 2f, geometry.yAtMax + labelPaint.textSize * 0.35f, labelPaint)
    if (geometry.showMidLabel) {
        canvas.drawText("51", 2f, geometry.yAtMid + labelPaint.textSize * 0.35f, labelPaint)
    }
    canvas.drawText("22", 2f, min(geometry.heightPx - 1f, geometry.yAtMin + labelPaint.textSize * 0.15f), labelPaint)

    val markerBaseSize = (geometry.heightPx * 0.16f).coerceIn(9f, 16f)
    val slotSpacing = geometry.slots.zipWithNext { a, b -> b.x - a.x }.minOrNull()
    val markerSize = slotSpacing?.let { min(markerBaseSize, it * 1.3f) } ?: markerBaseSize
    for (slot in geometry.slots) {
        val y = slot.y
        if (y == null) continue
        drawHrvMarkerOnCanvas(canvas, slot.marker, slot.x, y, markerSize)
    }
    return bmp
}

private fun drawHrvMarkerOnCanvas(canvas: Canvas, kind: HrvMarkerKind, cx: Float, cy: Float, size: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    paint.color = when (kind) {
        HrvMarkerKind.CIRCLE -> WidgetPalette.hrvGreen
        HrvMarkerKind.SQUARE -> WidgetPalette.hrvOrange
        HrvMarkerKind.TRIANGLE -> WidgetPalette.hrvRed
        HrvMarkerKind.NEUTRAL -> WidgetPalette.hrvGray
    }
    val half = size / 2f
    when (kind) {
        HrvMarkerKind.CIRCLE -> canvas.drawCircle(cx, cy, half * 0.7f, paint)
        HrvMarkerKind.SQUARE -> canvas.drawRect(cx - half * 0.7f, cy - half * 0.7f, cx + half * 0.7f, cy + half * 0.7f, paint)
        HrvMarkerKind.TRIANGLE -> {
            val path = Path()
            path.moveTo(cx, cy - half * 0.75f)
            path.lineTo(cx + half * 0.7f, cy + half * 0.65f)
            path.lineTo(cx - half * 0.7f, cy + half * 0.65f)
            path.close()
            canvas.drawPath(path, paint)
        }
        HrvMarkerKind.NEUTRAL -> canvas.drawCircle(cx, cy, half * 0.4f, paint)
    }
}

internal fun batteryCurveStrokeWidthPx(plotHeightPx: Int): Float =
    (plotHeightPx * 0.035f).coerceIn(1.25f, 2f)

internal fun batteryEndpointMarkerRadiusPx(strokeWidthPx: Float): Float =
    (strokeWidthPx * 1.35f).coerceIn(1.75f, 2.75f)

internal fun drawCombinedChartBitmap(
    widthPx: Int,
    heightPx: Int,
    battery: List<TimelinePoint>,
    stress: List<TimelinePoint>,
    rangeStart: Instant? = null,
    rangeEnd: Instant? = null,
): Bitmap {
    val geometry = buildCombinedChartGeometry(widthPx, heightPx, battery, stress, rangeStart, rangeEnd)
    val bmp = Bitmap.createBitmap(geometry.widthPx, geometry.heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    if (geometry.batteryPoints.isEmpty() && geometry.stressPoints.isEmpty()) return bmp

    // Layer order: grid → stress bars → Body Battery curve → endpoint marker.
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#45606B75")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8B0BEC5")
        textSize = 10f
        textAlign = Paint.Align.CENTER
    }
    listOf("00", "06", "12", "18", "24").forEachIndexed { index, label ->
        val x = geometry.left + (geometry.right - geometry.left) * index / 4f
        canvas.drawLine(x, geometry.top, x, geometry.bottom, gridPaint)
        canvas.drawText(label, x, geometry.heightPx - 2f, labelPaint)
    }

    if (geometry.stressPoints.isNotEmpty()) {
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((index, point) in geometry.stressPoints.withIndex()) {
            barPaint.color = stressBarColorArgb(point.value)
            val barHalf = stressBarHalfWidthPx(geometry.stressPoints, index)
            canvas.drawRect(point.x - barHalf, point.y, point.x + barHalf, geometry.bottom, barPaint)
        }
    }

    if (geometry.batteryPoints.isNotEmpty()) {
        val measured = geometry.batteryPoints
        val renderPoints = buildSmoothBatteryRenderPoints(measured)
        val stroke = batteryCurveStrokeWidthPx((geometry.bottom - geometry.top).roundToInt())
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WidgetPalette.battery
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        if (renderPoints.size == 1) {
            val p = renderPoints.first()
            canvas.drawCircle(
                p.x,
                p.y,
                batteryEndpointMarkerRadiusPx(stroke),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = WidgetPalette.battery
                    style = Paint.Style.FILL
                },
            )
        } else {
            val path = Path()
            renderPoints.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            val fill = Path(path).apply {
                lineTo(renderPoints.last().x, geometry.bottom)
                lineTo(renderPoints.first().x, geometry.bottom)
                close()
            }
            canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WidgetPalette.batteryFill })
            canvas.drawPath(path, curvePaint)
            val end = measured.last()
            canvas.drawCircle(
                end.x,
                end.y,
                batteryEndpointMarkerRadiusPx(stroke),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = WidgetPalette.battery
                    style = Paint.Style.FILL
                },
            )
        }
    }
    return bmp
}

internal fun drawHrvMarkerBitmap(kind: HrvMarkerKind, sizePx: Int): Bitmap {
    val safe = sizePx.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(safe, safe, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    paint.color = when (kind) {
        HrvMarkerKind.CIRCLE -> WidgetPalette.hrvGreen
        HrvMarkerKind.SQUARE -> WidgetPalette.hrvOrange
        HrvMarkerKind.TRIANGLE -> WidgetPalette.hrvRed
        HrvMarkerKind.NEUTRAL -> WidgetPalette.hrvGray
    }
    when (kind) {
        HrvMarkerKind.CIRCLE -> canvas.drawCircle(safe / 2f, safe / 2f, safe * 0.35f, paint)
        HrvMarkerKind.SQUARE -> canvas.drawRect(safe * 0.2f, safe * 0.2f, safe * 0.8f, safe * 0.8f, paint)
        HrvMarkerKind.TRIANGLE -> {
            val path = Path()
            path.moveTo(safe / 2f, safe * 0.16f)
            path.lineTo(safe * 0.84f, safe * 0.82f)
            path.lineTo(safe * 0.16f, safe * 0.82f)
            path.close()
            canvas.drawPath(path, paint)
        }
        HrvMarkerKind.NEUTRAL -> canvas.drawCircle(safe / 2f, safe / 2f, safe * 0.22f, paint)
    }
    return bmp
}

internal fun drawStatusDotBitmap(status: LocalStatus, sizePx: Int): Bitmap {
    val safe = sizePx.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(safe, safe, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val color = when (status) {
        LocalStatus.READY -> 0xFF66BB6A.toInt()
        LocalStatus.REFRESHING -> 0xFF5BD8E6.toInt()
        LocalStatus.NETWORK_ERROR -> 0xFFFFB74D.toInt()
        LocalStatus.AUTH_ERROR -> 0xFFEF5350.toInt()
        LocalStatus.NOT_CONFIGURED -> 0xFFB0BEC5.toInt()
    }
    canvas.drawCircle(
        safe / 2f,
        safe / 2f,
        safe * 0.35f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        },
    )
    return bmp
}

internal fun drawLegendSwatchBitmap(color: Int, sizePx: Int): Bitmap {
    val safe = sizePx.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(safe, safe, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawCircle(
        safe / 2f,
        safe / 2f,
        safe * 0.35f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        },
    )
    return bmp
}

internal fun drawActivityIconBitmap(typeKey: String?, sizePx: Int): Bitmap {
    val safe = sizePx.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(safe, safe, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val kind = activityTypeIcon(typeKey)
    val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when (kind) {
            "running" -> 0xFFFF8A33.toInt()
            "walking" -> 0xFF42A5F5.toInt()
            "hiking" -> 0xFF66BB6A.toInt()
            "cycling" -> 0xFF26C6DA.toInt()
            "strength_training" -> 0xFFAB47BC.toInt()
            "swimming" -> 0xFF29B6F6.toInt()
            "cardio" -> 0xFFEF5350.toInt()
            "yoga" -> 0xFF7E57C2.toInt()
            "skiing" -> 0xFF5C6BC0.toInt()
            else -> 0xFF78909C.toInt()
        }
        style = Paint.Style.FILL
    }
    canvas.drawCircle(safe / 2f, safe / 2f, safe * 0.46f, badge)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = max(2.5f, safe * 0.11f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    when (kind) {
        "running" -> {
            canvas.drawCircle(safe * 0.62f, safe * 0.24f, safe * 0.08f, fill)
            canvas.drawLine(safe * 0.58f, safe * 0.34f, safe * 0.42f, safe * 0.52f, p)
            canvas.drawLine(safe * 0.42f, safe * 0.52f, safe * 0.28f, safe * 0.78f, p)
            canvas.drawLine(safe * 0.42f, safe * 0.52f, safe * 0.68f, safe * 0.72f, p)
            canvas.drawLine(safe * 0.5f, safe * 0.4f, safe * 0.72f, safe * 0.5f, p)
            canvas.drawLine(safe * 0.5f, safe * 0.4f, safe * 0.3f, safe * 0.48f, p)
        }
        "walking" -> {
            canvas.drawCircle(safe * 0.5f, safe * 0.24f, safe * 0.08f, fill)
            canvas.drawLine(safe * 0.5f, safe * 0.34f, safe * 0.5f, safe * 0.58f, p)
            canvas.drawLine(safe * 0.5f, safe * 0.42f, safe * 0.3f, safe * 0.54f, p)
            canvas.drawLine(safe * 0.5f, safe * 0.42f, safe * 0.7f, safe * 0.54f, p)
            canvas.drawLine(safe * 0.5f, safe * 0.58f, safe * 0.36f, safe * 0.8f, p)
            canvas.drawLine(safe * 0.5f, safe * 0.58f, safe * 0.64f, safe * 0.8f, p)
        }
        "hiking" -> {
            canvas.drawCircle(safe * 0.46f, safe * 0.24f, safe * 0.08f, fill)
            canvas.drawLine(safe * 0.46f, safe * 0.34f, safe * 0.46f, safe * 0.58f, p)
            canvas.drawLine(safe * 0.46f, safe * 0.42f, safe * 0.3f, safe * 0.56f, p)
            canvas.drawLine(safe * 0.46f, safe * 0.58f, safe * 0.34f, safe * 0.8f, p)
            canvas.drawLine(safe * 0.46f, safe * 0.58f, safe * 0.6f, safe * 0.8f, p)
            canvas.drawLine(safe * 0.62f, safe * 0.3f, safe * 0.62f, safe * 0.78f, p)
        }
        "cycling" -> {
            canvas.drawCircle(safe * 0.3f, safe * 0.68f, safe * 0.14f, p)
            canvas.drawCircle(safe * 0.72f, safe * 0.68f, safe * 0.14f, p)
            canvas.drawLine(safe * 0.3f, safe * 0.68f, safe * 0.5f, safe * 0.42f, p)
            canvas.drawLine(safe * 0.5f, safe * 0.42f, safe * 0.72f, safe * 0.68f, p)
            canvas.drawLine(safe * 0.42f, safe * 0.3f, safe * 0.58f, safe * 0.42f, p)
        }
        "strength_training" -> {
            canvas.drawLine(safe * 0.2f, safe * 0.5f, safe * 0.8f, safe * 0.5f, p)
            canvas.drawRect(safe * 0.12f, safe * 0.34f, safe * 0.22f, safe * 0.66f, fill)
            canvas.drawRect(safe * 0.78f, safe * 0.34f, safe * 0.88f, safe * 0.66f, fill)
        }
        "swimming" -> {
            canvas.drawCircle(safe * 0.34f, safe * 0.36f, safe * 0.08f, fill)
            canvas.drawLine(safe * 0.4f, safe * 0.42f, safe * 0.68f, safe * 0.5f, p)
            canvas.drawArc(RectF(safe * 0.2f, safe * 0.58f, safe * 0.8f, safe * 0.82f), 200f, 140f, false, p)
        }
        "cardio" -> {
            val heart = Path()
            heart.moveTo(safe * 0.5f, safe * 0.72f)
            heart.cubicTo(safe * 0.2f, safe * 0.52f, safe * 0.22f, safe * 0.28f, safe * 0.5f, safe * 0.4f)
            heart.cubicTo(safe * 0.78f, safe * 0.28f, safe * 0.8f, safe * 0.52f, safe * 0.5f, safe * 0.72f)
            canvas.drawPath(heart, fill)
        }
        "yoga" -> {
            canvas.drawCircle(safe * 0.5f, safe * 0.28f, safe * 0.08f, fill)
            canvas.drawLine(safe * 0.5f, safe * 0.38f, safe * 0.5f, safe * 0.58f, p)
            canvas.drawLine(safe * 0.3f, safe * 0.72f, safe * 0.5f, safe * 0.58f, p)
            canvas.drawLine(safe * 0.7f, safe * 0.72f, safe * 0.5f, safe * 0.58f, p)
            canvas.drawLine(safe * 0.28f, safe * 0.48f, safe * 0.72f, safe * 0.48f, p)
        }
        "skiing" -> {
            canvas.drawCircle(safe * 0.46f, safe * 0.24f, safe * 0.07f, fill)
            canvas.drawLine(safe * 0.46f, safe * 0.32f, safe * 0.52f, safe * 0.56f, p)
            canvas.drawLine(safe * 0.52f, safe * 0.56f, safe * 0.4f, safe * 0.74f, p)
            canvas.drawLine(safe * 0.28f, safe * 0.8f, safe * 0.78f, safe * 0.68f, p)
        }
        else -> {
            canvas.drawCircle(safe * 0.5f, safe * 0.5f, safe * 0.18f, p)
            canvas.drawLine(safe * 0.5f, safe * 0.28f, safe * 0.5f, safe * 0.72f, p)
            canvas.drawLine(safe * 0.28f, safe * 0.5f, safe * 0.72f, safe * 0.5f, p)
        }
    }
    return bmp
}

/** Stable draw-plan tags proving icon categories are geometrically distinct. */
internal fun activityIconDrawPlan(typeKey: String?): List<String> {
    val kind = activityTypeIcon(typeKey)
    return when (kind) {
        "running" -> listOf("badge:orange", "dynamic-run", "lean-torso", "rear-leg", "forward-leg", "swing-arms")
        "walking" -> listOf("badge:blue", "upright-walk", "vertical-torso", "split-legs", "side-arms")
        "hiking" -> listOf("badge:green", "upright-hike", "vertical-torso", "split-legs", "trekking-pole")
        "cycling" -> listOf("badge:cyan", "wheel-left", "wheel-right", "frame", "handlebar")
        "strength_training" -> listOf("badge:purple", "barbell-bar", "plate-left", "plate-right")
        "swimming" -> listOf("badge:lightblue", "head", "stroke-arm", "wave-arc")
        "cardio" -> listOf("badge:red", "heart-fill")
        "yoga" -> listOf("badge:violet", "head", "torso", "triangle-legs", "side-arms")
        "skiing" -> listOf("badge:indigo", "head", "lean-torso", "ski-board")
        else -> listOf("badge:gray", "cross-circle")
    }
}

/** Deterministic icon fingerprint: distinct draw-plan plus non-empty rendered PNG payload. */
internal fun activityIconPixelSignature(typeKey: String?, sizePx: Int = 48): Long {
    val plan = activityIconDrawPlan(typeKey)
    val bmp = drawActivityIconBitmap(typeKey, sizePx)
    val stream = java.io.ByteArrayOutputStream()
    check(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) {
        "icon for $typeKey failed to compress"
    }
    val bytes = stream.toByteArray()
    check(bytes.size > 64) { "icon for $typeKey was blank/empty" }
    var hash = plan.hashCode().toLong()
    hash = hash * 31 + bytes.size
    for (b in bytes.take(64)) {
        hash = hash * 31 + (b.toInt() and 0xff)
    }
    return hash
}

/** Stable draw-plan tags proving health panel icons are geometrically distinct. */
internal enum class HealthPanelIcon { SLEEP, HRV, BODY_BATTERY }

internal fun healthPanelIconDrawPlan(kind: HealthPanelIcon): List<String> = when (kind) {
    HealthPanelIcon.SLEEP -> listOf("badge:indigo", "crescent-moon", "star-dot")
    HealthPanelIcon.HRV -> listOf("badge:green", "heart-outline", "pulse-notch")
    HealthPanelIcon.BODY_BATTERY -> listOf("badge:cyan", "battery-body", "bolt")
}

internal fun healthPanelIconContentDescription(kind: HealthPanelIcon): String = when (kind) {
    HealthPanelIcon.SLEEP -> "Sleep panel icon"
    HealthPanelIcon.HRV -> "HRV panel icon"
    HealthPanelIcon.BODY_BATTERY -> "Body Battery panel icon"
}

internal fun drawHealthPanelIconBitmap(kind: HealthPanelIcon, sizePx: Int): Bitmap {
    val safe = sizePx.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(safe, safe, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val badge = when (kind) {
        HealthPanelIcon.SLEEP -> 0xFF5C6BC0.toInt()
        HealthPanelIcon.HRV -> 0xFF4CAF50.toInt()
        HealthPanelIcon.BODY_BATTERY -> 0xFF4DD0E1.toInt()
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = badge
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(RectF(0f, 0f, safe.toFloat(), safe.toFloat()), safe * 0.22f, safe * 0.22f, fill)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = max(1.5f, safe * 0.08f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    when (kind) {
        HealthPanelIcon.SLEEP -> {
            canvas.drawCircle(safe * 0.52f, safe * 0.5f, safe * 0.28f, ink)
            canvas.drawCircle(safe * 0.64f, safe * 0.42f, safe * 0.22f, fill)
            canvas.drawCircle(safe * 0.28f, safe * 0.28f, safe * 0.05f, solid)
        }
        HealthPanelIcon.HRV -> {
            val heart = Path()
            heart.moveTo(safe * 0.5f, safe * 0.72f)
            heart.cubicTo(safe * 0.2f, safe * 0.52f, safe * 0.22f, safe * 0.28f, safe * 0.5f, safe * 0.4f)
            heart.cubicTo(safe * 0.78f, safe * 0.28f, safe * 0.8f, safe * 0.52f, safe * 0.5f, safe * 0.72f)
            canvas.drawPath(heart, ink)
            canvas.drawLine(safe * 0.34f, safe * 0.5f, safe * 0.44f, safe * 0.5f, ink)
            canvas.drawLine(safe * 0.44f, safe * 0.5f, safe * 0.5f, safe * 0.38f, ink)
            canvas.drawLine(safe * 0.5f, safe * 0.38f, safe * 0.58f, safe * 0.58f, ink)
            canvas.drawLine(safe * 0.58f, safe * 0.58f, safe * 0.68f, safe * 0.5f, ink)
        }
        HealthPanelIcon.BODY_BATTERY -> {
            canvas.drawRoundRect(RectF(safe * 0.22f, safe * 0.32f, safe * 0.72f, safe * 0.68f), safe * 0.08f, safe * 0.08f, ink)
            canvas.drawRect(safe * 0.72f, safe * 0.42f, safe * 0.8f, safe * 0.58f, solid)
            val bolt = Path()
            bolt.moveTo(safe * 0.5f, safe * 0.34f)
            bolt.lineTo(safe * 0.4f, safe * 0.52f)
            bolt.lineTo(safe * 0.52f, safe * 0.52f)
            bolt.lineTo(safe * 0.46f, safe * 0.68f)
            bolt.lineTo(safe * 0.62f, safe * 0.46f)
            bolt.lineTo(safe * 0.5f, safe * 0.46f)
            bolt.close()
            canvas.drawPath(bolt, solid)
        }
    }
    return bmp
}

internal fun drawRefreshIconBitmap(sizePx: Int, refreshing: Boolean): Bitmap {
    val safe = sizePx.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(safe, safe, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val color = if (refreshing) Color.parseColor("#4DD0E1") else Color.WHITE
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = max(2f, safe * 0.1f)
        strokeCap = Paint.Cap.ROUND
    }
    val rect = RectF(safe * 0.2f, safe * 0.2f, safe * 0.8f, safe * 0.8f)
    canvas.drawArc(rect, 40f, 250f, false, p)
    val head = Path().apply {
        moveTo(safe * 0.78f, safe * 0.28f)
        lineTo(safe * 0.9f, safe * 0.32f)
        lineTo(safe * 0.82f, safe * 0.42f)
        close()
    }
    canvas.drawPath(
        head,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        },
    )
    return bmp
}

internal fun activityDetailPairs(activity: LastActivity, rich: Boolean): List<Pair<String, String>> {
    val pairs = mutableListOf<Pair<String, String>>()
    if (activity.maxHeartRate != null) pairs += "Max HR" to "${activity.maxHeartRate} bpm"
    if (activity.elevationGainMeters != null) {
        pairs += "Gain" to String.format(Locale.US, "%.0f m", activity.elevationGainMeters)
    }
    if (activity.aerobicTrainingEffect != null) {
        pairs += "Aer" to String.format(Locale.US, "%.1f", activity.aerobicTrainingEffect)
    }
    if (activity.anaerobicTrainingEffect != null) {
        pairs += "Ana" to String.format(Locale.US, "%.1f", activity.anaerobicTrainingEffect)
    }
    if (activity.trainingLoad != null) {
        pairs += "Load" to String.format(Locale.US, "%.0f", activity.trainingLoad)
    }
    return if (rich) pairs.take(4) else pairs.take(2)
}

internal data class ActivityHrChartGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val points: List<ChartPoint>,
    val maxPoint: ChartPoint?,
    val minHr: Int,
    val maxHr: Int,
    val minElapsedSeconds: Int,
    val maxElapsedSeconds: Int,
) {
    val hasRenderableSeries: Boolean get() = points.size >= 2
}

internal fun buildActivityHrChartGeometry(
    widthPx: Int,
    heightPx: Int,
    timeline: List<ActivityHeartRatePoint>,
): ActivityHrChartGeometry {
    val w = max(1, widthPx)
    val h = max(1, heightPx)
    val left = 28f
    val top = 4f
    val right = (w - 4).toFloat().coerceAtLeast(left + 1f)
    val bottom = (h - 15).toFloat().coerceAtLeast(top + 1f)
    val ordered = timeline
        .filter { it.elapsedSeconds >= 0 && it.heartRate in 20..250 }
        .sortedBy { it.elapsedSeconds }
        .distinctBy { it.elapsedSeconds }
        .take(48)
    if (ordered.isEmpty()) {
        return ActivityHrChartGeometry(w, h, left, top, right, bottom, emptyList(), null, 20, 250, 0, 0)
    }
    val minElapsed = ordered.first().elapsedSeconds
    val maxElapsed = ordered.last().elapsedSeconds
    val span = (maxElapsed - minElapsed).coerceAtLeast(1)
    val dataMin = ordered.minOf { it.heartRate }
    val dataMax = ordered.maxOf { it.heartRate }
    val pad = max(4, ((dataMax - dataMin) * 0.12f).toInt())
    val minHr = (dataMin - pad).coerceAtLeast(20)
    val maxHr = (dataMax + pad).coerceAtMost(250).coerceAtLeast(minHr + 1)
    val range = (maxHr - minHr).toFloat()

    fun xOf(elapsed: Int): Float {
        val relative = (elapsed - minElapsed).toFloat() / span.toFloat()
        return left + relative * (right - left)
    }

    fun yOf(hr: Int): Float {
        val clamped = hr.coerceIn(minHr, maxHr)
        return bottom - ((clamped - minHr) / range) * (bottom - top)
    }

    val points = ordered.map { ChartPoint(xOf(it.elapsedSeconds), yOf(it.heartRate), it.heartRate) }
    val maxPoint = points.maxByOrNull { it.value }
    return ActivityHrChartGeometry(w, h, left, top, right, bottom, points, maxPoint, minHr, maxHr, minElapsed, maxElapsed)
}

internal val ACTIVITY_HR_LINE_NORMAL = 0xFFC8D0D6.toInt() // #C8D0D6 light cool grey
internal val ACTIVITY_HR_DIFFUSION_NORMAL = 0xFF52616B.toInt() // #52616B dark slate-blue
internal val ACTIVITY_HR_LINE_PEAK = 0xFFF4514F.toInt() // #F4514F coral red
internal val ACTIVITY_HR_DIFFUSION_PEAK = ACTIVITY_HR_LINE_PEAK

internal const val ACTIVITY_HR_PEAK_RATIO = 0.95f

/** Valid activity max HR, else max of timeline samples, else a safe default. */
internal fun resolveActivityHrCeiling(
    maxHeartRate: Int?,
    timeline: List<ActivityHeartRatePoint>,
): Int {
    if (maxHeartRate != null && maxHeartRate in 20..250) return maxHeartRate
    val fromTimeline = timeline
        .map { it.heartRate }
        .filter { it in 20..250 }
        .maxOrNull()
    return fromTimeline ?: 180
}

internal fun lerpColorArgb(from: Int, to: Int, t: Float): Int {
    val u = t.coerceIn(0f, 1f)
    val a = ((from ushr 24) and 0xff) + ((((to ushr 24) and 0xff) - ((from ushr 24) and 0xff)) * u).roundToInt()
    val r = ((from ushr 16) and 0xff) + ((((to ushr 16) and 0xff) - ((from ushr 16) and 0xff)) * u).roundToInt()
    val g = ((from ushr 8) and 0xff) + ((((to ushr 8) and 0xff) - ((from ushr 8) and 0xff)) * u).roundToInt()
    val b = (from and 0xff) + (((to and 0xff) - (from and 0xff)) * u).roundToInt()
    return (a.coerceIn(0, 255) shl 24) or
        (r.coerceIn(0, 255) shl 16) or
        (g.coerceIn(0, 255) shl 8) or
        b.coerceIn(0, 255)
}

internal fun activityHrPeakRatio(heartRate: Float, ceiling: Int): Float =
    heartRate / ceiling.coerceAtLeast(1).toFloat()

internal fun isActivityHrPeak(heartRate: Int, maxHr: Int): Boolean =
    activityHrPeakRatio(heartRate.toFloat(), maxHr) >= ACTIVITY_HR_PEAK_RATIO

/** Peak diffusion applies only to interpolated subsegments at/above the 95% threshold. */
internal fun activityHrPeakDiffusionSelected(midpointHr: Float, ceiling: Int): Boolean =
    activityHrPeakRatio(midpointHr, ceiling) >= ACTIVITY_HR_PEAK_RATIO

/**
 * Thin activity HR stroke: cool grey below 95% of resolved max HR, coral red at/above.
 */
internal fun activityHrLineColorArgb(heartRate: Int, maxHr: Int): Int =
    if (isActivityHrPeak(heartRate, maxHr)) ACTIVITY_HR_LINE_PEAK else ACTIVITY_HR_LINE_NORMAL

@Deprecated("Use activityHrLineColorArgb", ReplaceWith("activityHrLineColorArgb(heartRate, maxHr)"))
internal fun heartRateZoneColorArgb(heartRate: Int, maxHr: Int): Int =
    activityHrLineColorArgb(heartRate, maxHr)

internal fun activityHrNormalDiffusionColorArgb(): Int = ACTIVITY_HR_DIFFUSION_NORMAL

internal fun activityHrPeakDiffusionColorArgb(): Int = ACTIVITY_HR_DIFFUSION_PEAK

/** Target ~1.25dp stroke at render scale 2 → ~2.5px. */
internal fun activityHrStrokeWidthPx(heightPx: Int, renderScale: Float = LayoutMetrics.RENDER_SCALE): Float {
    val fromDp = 1.25f * renderScale
    return fromDp.coerceIn(2f, 3f).coerceAtMost(max(2f, heightPx * 0.02f))
}

internal fun activityHrMarkerRadiusPx(heightPx: Int, renderScale: Float = LayoutMetrics.RENDER_SCALE): Float {
    val fromDp = 1.1f * renderScale
    return fromDp.coerceIn(2f, 3.2f).coerceAtMost(max(2f, heightPx * 0.04f))
}

/**
 * Slate diffusion under the full HR curve (~10–18dp), scaled for chart height.
 * Bounded — reaches the baseline only when the line is already that close.
 */
internal fun activityHrNormalDiffusionDepthPx(
    plotHeightPx: Float,
    renderScale: Float = LayoutMetrics.RENDER_SCALE,
): Float {
    val target = 14f * renderScale
    return target
        .coerceIn(10f * renderScale, 18f * renderScale)
        .coerceAtMost((plotHeightPx * 0.42f).coerceAtLeast(8f))
}

/** @see activityHrNormalDiffusionDepthPx */
internal fun activityHrDiffusionDepthPx(
    plotHeightPx: Float,
    renderScale: Float = LayoutMetrics.RENDER_SCALE,
): Float = activityHrNormalDiffusionDepthPx(plotHeightPx, renderScale)

internal fun activityHrNormalDiffusionStartAlpha(): Int = 0x4A

internal fun activityHrDiffusionStartAlpha(): Int = activityHrNormalDiffusionStartAlpha()

/** Shallower coral glow only under ≥95% peak sections. */
internal fun activityHrPeakDiffusionDepthPx(
    plotHeightPx: Float,
    renderScale: Float = LayoutMetrics.RENDER_SCALE,
): Float {
    val normal = activityHrNormalDiffusionDepthPx(plotHeightPx, renderScale)
    val target = 7.5f * renderScale
    return target
        .coerceIn(5f * renderScale, 11f * renderScale)
        .coerceAtMost((normal * 0.55f).coerceAtLeast(6f))
}

internal fun activityHrPeakDiffusionStartAlpha(): Int = 0x4E

internal fun argbWithAlpha(colorRgb: Int, alpha: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (colorRgb and 0x00FFFFFF)

internal fun activityHrDiffusionBottomY(lineY: Float, depthPx: Float, plotBottom: Float): Float =
    min(lineY + depthPx, plotBottom)

internal fun activityHrDiffusionExtendsToBaseline(lineY: Float, depthPx: Float, plotBottom: Float): Boolean =
    lineY + depthPx >= plotBottom - 0.01f

internal fun drawActivityHrDiffusionStrip(
    canvas: Canvas,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    color0: Int,
    color1: Int,
    depthPx: Float,
    plotBottom: Float,
    startAlpha: Int = activityHrNormalDiffusionStartAlpha(),
) {
    if (depthPx <= 0.5f) return
    val y0b = activityHrDiffusionBottomY(y0, depthPx, plotBottom)
    val y1b = activityHrDiffusionBottomY(y1, depthPx, plotBottom)
    val path = Path().apply {
        moveTo(x0, y0)
        lineTo(x1, y1)
        lineTo(x1, y1b)
        lineTo(x0, y0b)
        close()
    }
    val midX = (x0 + x1) * 0.5f
    val midY = (y0 + y1) * 0.5f
    val midBottom = (y0b + y1b) * 0.5f
    val topColor = argbWithAlpha(lerpColorArgb(color0, color1, 0.5f), startAlpha)
    val bottomColor = argbWithAlpha(lerpColorArgb(color0, color1, 0.5f), 0)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(
            midX,
            midY,
            midX,
            midBottom,
            topColor,
            bottomColor,
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawPath(path, paint)
}

internal fun drawActivityHrChartBitmap(
    widthPx: Int,
    heightPx: Int,
    timeline: List<ActivityHeartRatePoint>,
    maxHeartRate: Int? = null,
): Bitmap? {
    val geo = buildActivityHrChartGeometry(widthPx, heightPx, timeline)
    if (!geo.hasRenderableSeries) return null
    val ceiling = resolveActivityHrCeiling(maxHeartRate, timeline)
    val bmp = Bitmap.createBitmap(geo.widthPx, geo.heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55606B88")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C5D0D8")
        textSize = 10f
    }
    val midHr = (geo.minHr + geo.maxHr) / 2
    listOf(geo.maxHr, midHr, geo.minHr).forEach { level ->
        val y = geo.bottom - ((level - geo.minHr).toFloat() / (geo.maxHr - geo.minHr)) * (geo.bottom - geo.top)
        canvas.drawLine(geo.left, y, geo.right, y, gridPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(level.toString(), geo.left - 4f, y + 4f, labelPaint)
    }
    listOf(0f, 0.5f, 1f).forEach { fraction ->
        val x = geo.left + (geo.right - geo.left) * fraction
        canvas.drawLine(x, geo.top, x, geo.bottom, gridPaint)
        val elapsed = geo.minElapsedSeconds + ((geo.maxElapsedSeconds - geo.minElapsedSeconds) * fraction).toInt()
        labelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("${elapsed / 60}m", x, geo.heightPx - 2f, labelPaint)
    }

    val plotHeight = geo.bottom - geo.top
    val normalDepth = activityHrNormalDiffusionDepthPx(plotHeight)
    val peakDepth = activityHrPeakDiffusionDepthPx(plotHeight)
    val slate = activityHrNormalDiffusionColorArgb()
    val peakFill = activityHrPeakDiffusionColorArgb()
    canvas.save()
    canvas.clipRect(geo.left, geo.top, geo.right, geo.bottom)
    for (i in 0 until geo.points.lastIndex) {
        val a = geo.points[i]
        val b = geo.points[i + 1]
        val span = abs(b.x - a.x)
        val steps = max(1, (span / 6f).roundToInt().coerceIn(1, 10))
        for (s in 0 until steps) {
            val t0 = s.toFloat() / steps.toFloat()
            val t1 = (s + 1).toFloat() / steps.toFloat()
            val x0 = a.x + (b.x - a.x) * t0
            val x1 = a.x + (b.x - a.x) * t1
            val y0 = a.y + (b.y - a.y) * t0
            val y1 = a.y + (b.y - a.y) * t1
            drawActivityHrDiffusionStrip(
                canvas,
                x0,
                y0,
                x1,
                y1,
                slate,
                slate,
                normalDepth,
                geo.bottom,
                activityHrNormalDiffusionStartAlpha(),
            )
            val midpointHr = a.value + (b.value - a.value) * ((t0 + t1) * 0.5f)
            if (activityHrPeakDiffusionSelected(midpointHr, ceiling)) {
                drawActivityHrDiffusionStrip(
                    canvas,
                    x0,
                    y0,
                    x1,
                    y1,
                    peakFill,
                    peakFill,
                    peakDepth,
                    geo.bottom,
                    activityHrPeakDiffusionStartAlpha(),
                )
            }
        }
    }

    val stroke = activityHrStrokeWidthPx(geo.heightPx)
    for (i in 0 until geo.points.lastIndex) {
        val a = geo.points[i]
        val b = geo.points[i + 1]
        val c0 = activityHrLineColorArgb(a.value, ceiling)
        val c1 = activityHrLineColorArgb(b.value, ceiling)
        val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = LinearGradient(a.x, a.y, b.x, b.y, c0, c1, Shader.TileMode.CLAMP)
        }
        canvas.drawLine(a.x, a.y, b.x, b.y, segPaint)
    }
    canvas.restore()

    geo.maxPoint?.let { peak ->
        val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activityHrLineColorArgb(peak.value, ceiling)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(peak.x, peak.y, activityHrMarkerRadiusPx(geo.heightPx), marker)
    }
    return bmp
}

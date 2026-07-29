package com.zndtoshi.garminwidget.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vertical budget for the single wide two-row widget (dp), including padding/spacers.
 */
internal data class LayoutBudget(
    val widgetHeightDp: Int,
    val outerPaddingDp: Int,
    val headerDp: Int,
    val afterHeaderSpacerDp: Int,
    val healthRowDp: Int,
    val afterHealthSpacerDp: Int,
    val activityDp: Int,
) {
    val estimatedUsedHeightDp: Int
        get() = (outerPaddingDp * 2) +
            headerDp +
            afterHeaderSpacerDp +
            healthRowDp +
            afterHealthSpacerDp +
            activityDp

    val fits: Boolean get() = estimatedUsedHeightDp <= widgetHeightDp
}

/**
 * Layout derived from the actual allocated widget size (`LocalSize` under `SizeMode.Exact`).
 * Always two rows: equal health panels on top, activity (+ HR chart) on the bottom.
 */
internal data class AdaptiveLayoutSpec(
    val widthDp: Float,
    val heightDp: Float,
    val contentWidthDp: Float,
    val panelWidthDp: Float,
    val healthRowDp: Int,
    val afterHealthSpacerDp: Int,
    val activityDp: Int,
    val sleepRingDp: Float,
    val hrvGraphWidthDp: Float,
    val hrvGraphHeightDp: Float,
    val panelChartWidthDp: Float,
    val panelChartHeightDp: Float,
    val activityHrChartHeightDp: Int,
    val showPanelTitles: Boolean,
    val showActivitySecondaryDetails: Boolean,
    val showActivityHrChart: Boolean,
) {
    val equalTopPanels: TopPanelGeometry
        get() = equalTopPanelGeometry(contentWidthDp)

    /** Always false: full-width Body Battery/Stress chart and metrics row were removed. */
    val hasStandaloneHealthChart: Boolean get() = false

    val hasMetricsRow: Boolean get() = false

    val hasSleepLegend: Boolean get() = false

    val estimatedUsedHeightDp: Int
        get() = LayoutMetrics.OUTER_PADDING_DP * 2 +
            LayoutMetrics.HEADER_DP +
            LayoutMetrics.AFTER_HEADER_SPACER_DP +
            healthRowDp +
            afterHealthSpacerDp +
            activityDp

    val fits: Boolean
        get() = estimatedUsedHeightDp <= heightDp.roundToInt()
}

internal object LayoutMetrics {
    const val OUTER_PADDING_DP = 8
    const val REFRESH_TOUCH_TARGET_DP = 36
    const val HEADER_DP = REFRESH_TOUCH_TARGET_DP
    const val AFTER_HEADER_SPACER_DP = 2
    const val SECTION_SPACER_DP = 4

    const val MIN_HEALTH_ROW_DP = 56
    const val MIN_ACTIVITY_ROW_DP = 64
    const val MIN_ACTIVITY_HR_CHART_DP = 24
    const val MIN_HRV_GRAPH_HEIGHT_DP = 28
    const val MIN_PANEL_CHART_HEIGHT_DP = 22
    const val MIN_SLEEP_RING_DP = 36

    /** Default bitmap render scale for Glance ImageProvider bitmaps. */
    const val RENDER_SCALE = 2f
    const val MAX_BITMAP_WIDTH_PX = 560
    const val MAX_BITMAP_HEIGHT_PX = 200
    const val MAX_LARGE_WIDGET_BITMAP_BYTES = 600 * 1024

    /** Representative Samsung/Lawnchair allocation at the phone's active 479 dpi. */
    fun primaryBudget(): LayoutBudget = fromSize(DpSize(438.dp, 236.dp)).toBudget()

    fun defaultWideBudget(): LayoutBudget = fromSize(DpSize(300.dp, 200.dp)).toBudget()

    fun fromSize(size: DpSize): AdaptiveLayoutSpec {
        val width = size.width.value.coerceAtLeast(1f)
        val height = size.height.value.coerceAtLeast(1f)
        val contentWidth = (width - OUTER_PADDING_DP * 2).coerceAtLeast(1f)
        val panelWidth = contentWidth / 3f
        val fixedChrome = OUTER_PADDING_DP * 2 + HEADER_DP + AFTER_HEADER_SPACER_DP
        val remaining = (height.roundToInt() - fixedChrome).coerceAtLeast(0)
        val spacer = if (remaining > 0) SECTION_SPACER_DP else 0
        val available = (remaining - spacer).coerceAtLeast(0)

        // Prefer equal visual weight, with activity getting at least half for the HR chart.
        var health = (available * 0.48f).roundToInt()
        var activity = available - health
        if (available >= MIN_HEALTH_ROW_DP + MIN_ACTIVITY_ROW_DP) {
            health = health.coerceIn(MIN_HEALTH_ROW_DP, available - MIN_ACTIVITY_ROW_DP)
            activity = available - health
            if (activity < health) {
                val half = available / 2
                health = half
                activity = available - health
            }
        } else {
            // Below comfortable height: still keep both rows; shrink secondary later.
            health = maxOf(1, (available * 0.45f).roundToInt())
            activity = (available - health).coerceAtLeast(1)
        }

        // Absorb leftover so estimated height matches allocated height.
        val usedRows = health + spacer + activity
        if (remaining > usedRows) {
            activity += remaining - usedRows
        }

        val showTitles = health >= 68 && panelWidth >= 90f
        val showSecondary = activity >= 88
        // Size the visual elements from the actual row budgets instead of using
        // conservative fixed caps. This lets a resized widget consume the space
        // the launcher has really allocated while retaining compact fallbacks.
        val hrChart = (activity - 30)
            .coerceIn(16, 110)
            .coerceAtLeast(MIN_ACTIVITY_HR_CHART_DP.coerceAtMost(activity / 3))

        val sleepRing = min(panelWidth - 18f, (health - 27).toFloat())
            .coerceIn(MIN_SLEEP_RING_DP.toFloat(), 110f)
        val graphWidth = (panelWidth - 12f).coerceIn(56f, 120f)
        val hrvHeight = (health - 42).toFloat()
            .coerceIn(MIN_HRV_GRAPH_HEIGHT_DP.toFloat(), 90f)
        val panelChartHeight = (health - 41).toFloat()
            .coerceIn(MIN_PANEL_CHART_HEIGHT_DP.toFloat(), 90f)

        return AdaptiveLayoutSpec(
            widthDp = width,
            heightDp = height,
            contentWidthDp = contentWidth,
            panelWidthDp = panelWidth,
            healthRowDp = health.coerceAtLeast(0),
            afterHealthSpacerDp = spacer,
            activityDp = activity.coerceAtLeast(0),
            sleepRingDp = sleepRing,
            hrvGraphWidthDp = graphWidth,
            hrvGraphHeightDp = hrvHeight,
            panelChartWidthDp = graphWidth,
            panelChartHeightDp = panelChartHeight,
            activityHrChartHeightDp = hrChart,
            showPanelTitles = showTitles,
            showActivitySecondaryDetails = showSecondary,
            showActivityHrChart = hrChart >= 16,
        )
    }

    fun dpToRenderPx(dp: Float, scale: Float = RENDER_SCALE): Int {
        val raw = (dp * scale).roundToInt().coerceAtLeast(1)
        return min(raw, MAX_BITMAP_WIDTH_PX.coerceAtLeast(MAX_BITMAP_HEIGHT_PX))
    }

    fun chartRenderSize(widthDp: Float, heightDp: Float, scale: Float = RENDER_SCALE): Pair<Int, Int> {
        val w = (widthDp * scale).roundToInt().coerceIn(1, MAX_BITMAP_WIDTH_PX)
        val h = (heightDp * scale).roundToInt().coerceIn(1, MAX_BITMAP_HEIGHT_PX)
        return w to h
    }

    fun estimatedArgbBytes(widthPx: Int, heightPx: Int): Long =
        widthPx.toLong() * heightPx.toLong() * 4L

    /** Approximate combined uncompressed bitmap payload for one two-row composition. */
    fun estimateWidgetBitmapBytes(): Long {
        val ring = chartRenderSize(48f, 48f)
        val hrv = chartRenderSize(100f, 40f)
        val panelChart = chartRenderSize(100f, 28f)
        val activityIcon = chartRenderSize(18f, 18f)
        val activityHr = chartRenderSize(320f, 36f)
        val panelIcon = chartRenderSize(14f, 14f)
        val refresh = chartRenderSize(18f, 18f)
        val status = chartRenderSize(10f, 10f)
        return estimatedArgbBytes(ring.first, ring.second) +
            estimatedArgbBytes(hrv.first, hrv.second) +
            estimatedArgbBytes(panelChart.first, panelChart.second) +
            estimatedArgbBytes(activityIcon.first, activityIcon.second) +
            estimatedArgbBytes(activityHr.first, activityHr.second) +
            estimatedArgbBytes(panelIcon.first, panelIcon.second) * 3 +
            estimatedArgbBytes(refresh.first, refresh.second) +
            estimatedArgbBytes(status.first, status.second)
    }

    private fun AdaptiveLayoutSpec.toBudget(): LayoutBudget = LayoutBudget(
        widgetHeightDp = heightDp.roundToInt(),
        outerPaddingDp = OUTER_PADDING_DP,
        headerDp = HEADER_DP,
        afterHeaderSpacerDp = AFTER_HEADER_SPACER_DP,
        healthRowDp = healthRowDp,
        afterHealthSpacerDp = afterHealthSpacerDp,
        activityDp = activityDp,
    )
}

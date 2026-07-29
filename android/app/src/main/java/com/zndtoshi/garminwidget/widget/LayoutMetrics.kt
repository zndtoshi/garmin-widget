package com.zndtoshi.garminwidget.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Explicit vertical budgets for responsive widget sizes (dp), including padding/spacers.
 * Used by layout composition and JVM budget assertions.
 */
internal data class LayoutBudget(
    val widgetHeightDp: Int,
    val outerPaddingDp: Int,
    val headerDp: Int,
    val afterHeaderSpacerDp: Int,
    val healthRowDp: Int,
    val afterHealthSpacerDp: Int,
    val chartDp: Int,
    val afterChartSpacerDp: Int,
    val metricsRowDp: Int,
    val afterMetricsSpacerDp: Int,
    val activityDp: Int,
) {
    val estimatedUsedHeightDp: Int
        get() = (outerPaddingDp * 2) +
            headerDp +
            afterHeaderSpacerDp +
            healthRowDp +
            afterHealthSpacerDp +
            chartDp +
            afterChartSpacerDp +
            metricsRowDp +
            afterMetricsSpacerDp +
            activityDp

    val fits: Boolean get() = estimatedUsedHeightDp <= widgetHeightDp
}

/**
 * Layout derived from the actual allocated widget size (`LocalSize` under `SizeMode.Exact`).
 */
internal data class AdaptiveLayoutSpec(
    val sizeClass: WidgetSizeClass,
    val widthDp: Float,
    val heightDp: Float,
    val contentWidthDp: Float,
    val panelWidthDp: Float,
    val healthRowDp: Int,
    val afterHealthSpacerDp: Int,
    val chartDp: Int,
    val afterChartSpacerDp: Int,
    val metricsRowDp: Int,
    val afterMetricsSpacerDp: Int,
    val activityDp: Int,
    val sleepRingDp: Float,
    val panelChartWidthDp: Float,
    val panelChartHeightDp: Float,
    val hrvGraphWidthDp: Float,
    val hrvGraphHeightDp: Float,
    val showSleepLegend: Boolean,
    val showFullChart: Boolean,
    val showMetricsRow: Boolean,
    val showActivityHrChart: Boolean,
    val showWideHrSparkline: Boolean,
    val activityHrChartHeightDp: Int,
) {
    val equalTopPanels: TopPanelGeometry
        get() = equalTopPanelGeometry(contentWidthDp)

    /** Total vertical dp claimed by padding, header, rows, and enabled spacers. */
    val estimatedUsedHeightDp: Int
        get() = LayoutMetrics.OUTER_PADDING_DP * 2 +
            LayoutMetrics.HEADER_DP +
            LayoutMetrics.AFTER_HEADER_SPACER_DP +
            healthRowDp +
            afterHealthSpacerDp +
            chartDp +
            afterChartSpacerDp +
            metricsRowDp +
            afterMetricsSpacerDp +
            activityDp

    val fits: Boolean
        get() = estimatedUsedHeightDp <= heightDp.roundToInt()
}

internal object LayoutMetrics {
    const val OUTER_PADDING_DP = 8
    const val REFRESH_TOUCH_TARGET_DP = 36
    const val HEADER_DP = REFRESH_TOUCH_TARGET_DP
    const val AFTER_HEADER_SPACER_DP = 2

    const val LARGE_SLEEP_RING_DP = 40
    const val SLEEP_DURATION_LABEL_DP = 10
    const val SLEEP_LEGEND_GRID_DP = 16
    const val SECTION_SPACER_DP = 4

    /** Default bitmap render scale for Glance ImageProvider bitmaps. */
    const val RENDER_SCALE = 2f
    const val MAX_BITMAP_WIDTH_PX = 560
    const val MAX_BITMAP_HEIGHT_PX = 200
    const val MAX_LARGE_WIDGET_BITMAP_BYTES = 600 * 1024

    fun compactBudget(): LayoutBudget = fromSize(DpSize(180.dp, 110.dp)).toBudget()

    fun wideBudget(): LayoutBudget = fromSize(DpSize(300.dp, 180.dp)).toBudget()

    fun largeBudget(): LayoutBudget = fromSize(DpSize(300.dp, 280.dp)).toBudget()

    /** Estimated height of the large sleep column: ring + duration + compact legend. */
    fun largeSleepPanelContentHeightDp(): Int =
        LARGE_SLEEP_RING_DP + SLEEP_DURATION_LABEL_DP + SLEEP_LEGEND_GRID_DP

    fun fromSize(size: DpSize): AdaptiveLayoutSpec {
        val width = size.width.value.coerceAtLeast(1f)
        val height = size.height.value.coerceAtLeast(1f)
        val sizeClass = classifySize(size)
        val contentWidth = (width - OUTER_PADDING_DP * 2).coerceAtLeast(1f)
        val panelWidth = contentWidth / 3f
        val fixedChrome = OUTER_PADDING_DP * 2 + HEADER_DP + AFTER_HEADER_SPACER_DP
        val remaining = (height.roundToInt() - fixedChrome).coerceAtLeast(0)

        return when (sizeClass) {
            WidgetSizeClass.COMPACT -> {
                val health = remaining.coerceIn(0, 72)
                AdaptiveLayoutSpec(
                    sizeClass = sizeClass,
                    widthDp = width,
                    heightDp = height,
                    contentWidthDp = contentWidth,
                    panelWidthDp = panelWidth,
                    healthRowDp = health,
                    afterHealthSpacerDp = 0,
                    chartDp = 0,
                    afterChartSpacerDp = 0,
                    metricsRowDp = 0,
                    afterMetricsSpacerDp = 0,
                    activityDp = 0,
                    sleepRingDp = min(46f, panelWidth - 4f).coerceAtLeast(36f),
                    panelChartWidthDp = (panelWidth - 8f).coerceIn(40f, 70f),
                    panelChartHeightDp = 18f,
                    hrvGraphWidthDp = (panelWidth - 8f).coerceIn(40f, 70f),
                    hrvGraphHeightDp = 24f,
                    showSleepLegend = false,
                    showFullChart = false,
                    showMetricsRow = false,
                    showActivityHrChart = false,
                    showWideHrSparkline = false,
                    activityHrChartHeightDp = 0,
                )
            }

            WidgetSizeClass.WIDE -> packWide(
                width = width,
                height = height,
                contentWidth = contentWidth,
                panelWidth = panelWidth,
                remaining = remaining,
            )

            WidgetSizeClass.LARGE -> packLarge(
                width = width,
                height = height,
                contentWidth = contentWidth,
                panelWidth = panelWidth,
                remaining = remaining,
            )
        }
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

    /** Approximate combined uncompressed bitmap payload for one large-layout composition. */
    fun estimateLargeLayoutBitmapBytes(): Long {
        val ring = chartRenderSize(LARGE_SLEEP_RING_DP.toFloat(), LARGE_SLEEP_RING_DP.toFloat())
        val chart = chartRenderSize(268f, 50f)
        val hrv = chartRenderSize(90f, 42f)
        val panelChart = chartRenderSize(84f, 28f)
        val activityIcon = chartRenderSize(18f, 18f)
        val activityHr = chartRenderSize(268f, 36f)
        val refresh = chartRenderSize(18f, 18f)
        val status = chartRenderSize(10f, 10f)
        return estimatedArgbBytes(ring.first, ring.second) +
            estimatedArgbBytes(chart.first, chart.second) +
            estimatedArgbBytes(hrv.first, hrv.second) +
            estimatedArgbBytes(panelChart.first, panelChart.second) +
            estimatedArgbBytes(activityIcon.first, activityIcon.second) +
            estimatedArgbBytes(activityHr.first, activityHr.second) +
            estimatedArgbBytes(refresh.first, refresh.second) +
            estimatedArgbBytes(status.first, status.second)
    }

    private fun packWide(
        width: Float,
        height: Float,
        contentWidth: Float,
        panelWidth: Float,
        remaining: Int,
    ): AdaptiveLayoutSpec {
        var activity = when {
            remaining >= 140 -> min(48, remaining / 4)
            remaining >= 110 -> 36
            else -> 0
        }
        var afterHealth = if (activity > 0) SECTION_SPACER_DP else 0
        var health = (remaining - activity - afterHealth).coerceAtLeast(0)
        // Prefer a readable health band, but never force mins that overflow remaining.
        if (health > 96) {
            val extra = health - 96
            health = 96
            if (activity > 0) {
                activity += extra
            }
        }
        // If activity + spacer would leave almost no health, drop activity first.
        if (health < 48 && activity > 0) {
            activity = 0
            afterHealth = 0
            health = remaining
        }
        val sparkline = activity >= 44 && height >= 170f
        return AdaptiveLayoutSpec(
            sizeClass = WidgetSizeClass.WIDE,
            widthDp = width,
            heightDp = height,
            contentWidthDp = contentWidth,
            panelWidthDp = panelWidth,
            healthRowDp = health,
            afterHealthSpacerDp = afterHealth,
            chartDp = 0,
            afterChartSpacerDp = 0,
            metricsRowDp = 0,
            afterMetricsSpacerDp = 0,
            activityDp = activity,
            sleepRingDp = min(44f, panelWidth - 8f).coerceAtLeast(34f),
            panelChartWidthDp = (panelWidth - 10f).coerceIn(60f, 96f),
            panelChartHeightDp = 40f,
            hrvGraphWidthDp = (panelWidth - 10f).coerceIn(60f, 96f),
            hrvGraphHeightDp = 44f,
            showSleepLegend = health >= 72,
            showFullChart = false,
            showMetricsRow = false,
            showActivityHrChart = false,
            showWideHrSparkline = sparkline,
            activityHrChartHeightDp = if (sparkline) 16 else 0,
        )
    }

    private fun packLarge(
        width: Float,
        height: Float,
        contentWidth: Float,
        panelWidth: Float,
        remaining: Int,
    ): AdaptiveLayoutSpec {
        // Baseline 300x280 => remaining 226: keep activity roughly 100–110dp.
        var activity = when {
            remaining >= 220 -> (remaining * 0.42f).roundToInt().coerceIn(100, 140)
            remaining >= 180 -> 90
            remaining >= 140 -> 72
            remaining >= 100 -> 56
            else -> (remaining / 2).coerceAtLeast(0)
        }
        var chart = when {
            remaining >= 200 -> 36
            remaining >= 160 -> 28
            else -> 0
        }
        var metrics = if (remaining >= 190) 24 else 0
        val minHealth = 48

        fun spacerSum(activityDp: Int, chartDp: Int, metricsDp: Int): Int =
            (if (chartDp > 0) SECTION_SPACER_DP else 0) +
                (if (metricsDp > 0) SECTION_SPACER_DP else 0) +
                (if (activityDp > 0) SECTION_SPACER_DP else 0)

        fun healthFor(activityDp: Int, chartDp: Int, metricsDp: Int): Int =
            remaining - activityDp - chartDp - metricsDp - spacerSum(activityDp, chartDp, metricsDp)

        // Drop/omit lower-priority rows before forcing any minimum that would overflow.
        while (healthFor(activity, chart, metrics) < minHealth) {
            when {
                metrics > 0 -> metrics = 0
                chart > 0 -> chart = 0
                activity > 0 -> {
                    val deficit = minHealth - healthFor(activity, chart, metrics)
                    activity = (activity - deficit).coerceAtLeast(0)
                }
                else -> break
            }
        }

        var health = healthFor(activity, chart, metrics).coerceAtLeast(0)

        // Final hard fit: shrink activity, then chart/metrics, never coerce health upward.
        while (health + activity + chart + metrics + spacerSum(activity, chart, metrics) > remaining) {
            when {
                activity > 0 -> activity -= 1
                metrics > 0 -> metrics = 0
                chart > 0 -> chart = 0
                health > 0 -> health -= 1
                else -> break
            }
            health = healthFor(activity, chart, metrics).coerceAtLeast(0)
        }

        // Absorb leftover into activity so tall widgets do not leave a blank band.
        val used = health + activity + chart + metrics + spacerSum(activity, chart, metrics)
        if (remaining > used && activity > 0) {
            activity += remaining - used
        } else if (remaining > used) {
            health += remaining - used
        }

        val afterHealth = if (chart > 0) SECTION_SPACER_DP else 0
        val afterChart = if (metrics > 0) SECTION_SPACER_DP else 0
        val afterMetrics = if (activity > 0) SECTION_SPACER_DP else 0
        val hrChart = if (activity >= 100) min(36, activity / 3) else 0

        return AdaptiveLayoutSpec(
            sizeClass = WidgetSizeClass.LARGE,
            widthDp = width,
            heightDp = height,
            contentWidthDp = contentWidth,
            panelWidthDp = panelWidth,
            healthRowDp = health,
            afterHealthSpacerDp = afterHealth,
            chartDp = chart,
            afterChartSpacerDp = afterChart,
            metricsRowDp = metrics,
            afterMetricsSpacerDp = afterMetrics,
            activityDp = activity,
            sleepRingDp = min(LARGE_SLEEP_RING_DP.toFloat(), panelWidth - 8f).coerceAtLeast(32f),
            panelChartWidthDp = (panelWidth - 10f).coerceIn(60f, 100f),
            panelChartHeightDp = 26f,
            hrvGraphWidthDp = (panelWidth - 10f).coerceIn(60f, 100f),
            hrvGraphHeightDp = 40f,
            showSleepLegend = health >= 64,
            showFullChart = chart > 0,
            showMetricsRow = metrics > 0,
            showActivityHrChart = hrChart > 0,
            showWideHrSparkline = false,
            activityHrChartHeightDp = hrChart,
        )
    }

    private fun AdaptiveLayoutSpec.toBudget(): LayoutBudget = LayoutBudget(
        widgetHeightDp = heightDp.roundToInt(),
        outerPaddingDp = OUTER_PADDING_DP,
        headerDp = HEADER_DP,
        afterHeaderSpacerDp = AFTER_HEADER_SPACER_DP,
        healthRowDp = healthRowDp,
        afterHealthSpacerDp = afterHealthSpacerDp,
        chartDp = chartDp,
        afterChartSpacerDp = afterChartSpacerDp,
        metricsRowDp = metricsRowDp,
        afterMetricsSpacerDp = afterMetricsSpacerDp,
        activityDp = activityDp,
    )
}

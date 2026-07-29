package com.zndtoshi.garminwidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.zndtoshi.garminwidget.data.ActivityHeartRatePoint
import com.zndtoshi.garminwidget.data.HrvTrendPoint
import com.zndtoshi.garminwidget.data.LastActivity
import com.zndtoshi.garminwidget.data.LocalStatus
import com.zndtoshi.garminwidget.data.SettingsStore
import com.zndtoshi.garminwidget.data.SleepStages
import com.zndtoshi.garminwidget.data.TimelinePoint
import com.zndtoshi.garminwidget.data.WidgetResponse
import com.zndtoshi.garminwidget.data.WidgetStore
import java.time.ZoneId
import java.util.Locale

class GarminWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    /** Exact size so LocalSize reflects launcher-allocated dp after resize. */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val glanceState = currentState<Preferences>()
            val refreshRevision = glanceState[RefreshRevisionKey] ?: 0L
            val settings = SettingsStore(context)
            val state = WidgetStore(context).read()
            WidgetContent(
                data = state.data,
                status = resolveVisibleStatus(settings.isConfigured(), state.status, refreshRevision),
                opacityPercent = settings.widgetOpacityPercent(),
            )
        }
    }
}

/** Wide preset — preserve existing receiver identity for already-placed widgets. */
class GarminWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GarminWidget()
}

class GarminCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GarminWidget()
}

class GarminLargeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GarminWidget()
}

@Composable
private fun WidgetContent(data: WidgetResponse?, status: LocalStatus, opacityPercent: Int) {
    val size = LocalSize.current
    val spec = LayoutMetrics.fromSize(size)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(22.dp)
            .background(ColorProvider(Color(0xFF101B2A).copy(alpha = opacityPercentToAlpha(opacityPercent))))
            .padding(LayoutMetrics.OUTER_PADDING_DP.dp),
    ) {
        HeaderRow(status)
        Spacer(GlanceModifier.height(LayoutMetrics.AFTER_HEADER_SPACER_DP.dp))
        if (data == null) {
            EmptyState(status)
        } else {
            val zone = ZoneId.systemDefault()
            val locale = Locale.getDefault()
            val bb = filterTimelineForResponseDate(data.bodyBatteryTimeline, data.date, zone)
            val st = filterTimelineForResponseDate(data.stressTimeline, data.date, zone)
            Column(modifier = GlanceModifier.fillMaxWidth().clickable(actionRunCallback<OpenGarminAction>())) {
                when (spec.sizeClass) {
                    WidgetSizeClass.COMPACT -> CompactLayout(data, bb, st, spec)
                    WidgetSizeClass.WIDE -> WideLayout(data, bb, st, zone, locale, spec)
                    WidgetSizeClass.LARGE -> LargeLayout(data, bb, st, zone, locale, spec)
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(status: LocalStatus) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(LayoutMetrics.HEADER_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "GARMIN",
            style = TextStyle(color = ColorProvider(Color(0xFF5BD8E6)), fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.defaultWeight(),
        )
        Image(
            provider = ImageProvider(drawStatusDotBitmap(status, 20)),
            contentDescription = statusContentDescription(status),
            modifier = GlanceModifier.width(10.dp).height(10.dp).padding(end = 4.dp),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier
                .width(LayoutMetrics.REFRESH_TOUCH_TARGET_DP.dp)
                .height(LayoutMetrics.REFRESH_TOUCH_TARGET_DP.dp)
                .clickable(actionRunCallback<RefreshAction>()),
        ) {
            Image(
                provider = ImageProvider(
                    drawRefreshIconBitmap(
                        LayoutMetrics.dpToRenderPx(18f),
                        status == LocalStatus.REFRESHING,
                    ),
                ),
                contentDescription = refreshContentDescription(status),
                modifier = GlanceModifier.width(18.dp).height(18.dp),
            )
        }
    }
}

@Composable
private fun CompactLayout(
    data: WidgetResponse,
    bodyBattery: List<TimelinePoint>,
    stress: List<TimelinePoint>,
    spec: AdaptiveLayoutSpec,
) {
    val labels = batteryStressPresentation(data.bodyBattery, data.stress)
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(spec.healthRowDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EqualPanel(modifier = GlanceModifier.defaultWeight()) {
            CompactSleepRing(data, ringDp = spec.sleepRingDp.dp)
        }
        EqualPanel(modifier = GlanceModifier.defaultWeight()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("HRV", style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 8.sp))
                Text(
                    text = data.overnightHrv?.toString() ?: "—",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = formatHrvStatusLabel(data.hrvStatus),
                    style = TextStyle(color = ColorProvider(Color(0xFFB0BEC5)), fontSize = 7.sp),
                    maxLines = 1,
                )
                HrvGraphImage(
                    data.hrvTrend,
                    maxPoints = 3,
                    widthDp = spec.hrvGraphWidthDp.dp,
                    heightDp = spec.hrvGraphHeightDp.dp,
                    showMidLabel = false,
                )
            }
        }
        EqualPanel(modifier = GlanceModifier.defaultWeight()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(labels.bodyBatteryLabel, style = TextStyle(color = ColorProvider(Color(0xFF4DD0E1)), fontSize = 7.sp), maxLines = 1)
                Text(labels.bodyBatteryValue, style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp, fontWeight = FontWeight.Bold))
                Text(labels.stressLabel, style = TextStyle(color = ColorProvider(Color(0xFFFFA726)), fontSize = 7.sp), maxLines = 1)
                Text(labels.stressValue, style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp, fontWeight = FontWeight.Bold))
                CombinedChartImage(
                    bodyBattery,
                    stress,
                    data.bodyBattery,
                    data.stress,
                    widthDp = spec.panelChartWidthDp.dp,
                    heightDp = spec.panelChartHeightDp.dp,
                )
            }
        }
    }
}

@Composable
private fun WideLayout(
    data: WidgetResponse,
    bodyBattery: List<TimelinePoint>,
    stress: List<TimelinePoint>,
    zoneId: ZoneId,
    locale: Locale,
    spec: AdaptiveLayoutSpec,
) {
    val labels = batteryStressPresentation(data.bodyBattery, data.stress)
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(spec.healthRowDp.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EqualPanel(modifier = GlanceModifier.defaultWeight()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CompactSleepRing(data, ringDp = spec.sleepRingDp.dp)
                Text(formatSleepDuration(data.sleepDurationSeconds), style = TextStyle(color = ColorProvider(Color(0xFFB0BEC5)), fontSize = 8.sp))
                if (spec.showSleepLegend) {
                    SleepLegendGrid(data.sleepStages)
                }
            }
        }
        EqualPanel(modifier = GlanceModifier.defaultWeight()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("HRV ${data.overnightHrv ?: "—"}", style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                Text(formatHrvStatusLabel(data.hrvStatus), style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 8.sp))
                HrvGraphImage(
                    data.hrvTrend,
                    maxPoints = 7,
                    widthDp = spec.hrvGraphWidthDp.dp,
                    heightDp = spec.hrvGraphHeightDp.dp,
                    showMidLabel = true,
                )
            }
        }
        EqualPanel(modifier = GlanceModifier.defaultWeight()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${labels.bodyBatteryLabel} ${labels.bodyBatteryValue}", style = TextStyle(color = ColorProvider(Color(0xFF4DD0E1)), fontSize = 8.sp), maxLines = 1)
                Text("${labels.stressLabel} ${labels.stressValue}", style = TextStyle(color = ColorProvider(Color(0xFFFFA726)), fontSize = 8.sp), maxLines = 1)
                CombinedChartImage(
                    bodyBattery,
                    stress,
                    data.bodyBattery,
                    data.stress,
                    widthDp = spec.panelChartWidthDp.dp,
                    heightDp = spec.panelChartHeightDp.dp,
                )
            }
        }
    }
    if (spec.activityDp > 0) {
        Spacer(GlanceModifier.height(spec.afterHealthSpacerDp.dp))
        ActivityStrip(
            activity = data.lastActivity,
            zoneId = zoneId,
            locale = locale,
            rich = false,
            heightDp = spec.activityDp.dp,
            showHrChart = spec.showWideHrSparkline,
            hrChartHeightDp = spec.activityHrChartHeightDp.dp,
            chartWidthDp = (spec.contentWidthDp - 12f).dp,
        )
    }
}

@Composable
private fun LargeLayout(
    data: WidgetResponse,
    bodyBattery: List<TimelinePoint>,
    stress: List<TimelinePoint>,
    zoneId: ZoneId,
    locale: Locale,
    spec: AdaptiveLayoutSpec,
) {
    val labels = batteryStressPresentation(data.bodyBattery, data.stress)
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(spec.healthRowDp.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EqualPanel(modifier = GlanceModifier.defaultWeight()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CompactSleepRing(data, ringDp = spec.sleepRingDp.dp)
                Text(formatSleepDuration(data.sleepDurationSeconds), style = TextStyle(color = ColorProvider(Color(0xFFB0BEC5)), fontSize = 8.sp))
                if (spec.showSleepLegend) {
                    SleepLegendGrid(data.sleepStages)
                }
            }
        }
        EqualPanel(modifier = GlanceModifier.defaultWeight()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("HRV ${data.overnightHrv ?: "—"}", style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Text(formatHrvStatusLabel(data.hrvStatus), style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 8.sp))
                HrvGraphImage(
                    data.hrvTrend,
                    maxPoints = 7,
                    widthDp = spec.hrvGraphWidthDp.dp,
                    heightDp = spec.hrvGraphHeightDp.dp,
                    showMidLabel = true,
                )
            }
        }
        EqualPanel(modifier = GlanceModifier.defaultWeight()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(labels.bodyBatteryLabel, style = TextStyle(color = ColorProvider(Color(0xFF4DD0E1)), fontSize = 8.sp), maxLines = 1)
                Text(labels.bodyBatteryValue, style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                Text(labels.stressLabel, style = TextStyle(color = ColorProvider(Color(0xFFFFA726)), fontSize = 8.sp), maxLines = 1)
                Text(labels.stressValue, style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold))
                CombinedChartImage(
                    bodyBattery,
                    stress,
                    data.bodyBattery,
                    data.stress,
                    widthDp = spec.panelChartWidthDp.dp,
                    heightDp = spec.panelChartHeightDp.dp,
                )
            }
        }
    }
    if (spec.showFullChart && spec.chartDp > 0) {
        if (spec.afterHealthSpacerDp > 0) {
            Spacer(GlanceModifier.height(spec.afterHealthSpacerDp.dp))
        }
        CombinedChartImage(
            bodyBattery,
            stress,
            data.bodyBattery,
            data.stress,
            widthDp = (spec.contentWidthDp - 8f).dp,
            heightDp = spec.chartDp.dp,
        )
    }
    if (spec.showMetricsRow && spec.metricsRowDp > 0) {
        if (spec.afterChartSpacerDp > 0) {
            Spacer(GlanceModifier.height(spec.afterChartSpacerDp.dp))
        }
        Row(modifier = GlanceModifier.fillMaxWidth().height(spec.metricsRowDp.dp)) {
            Metric("Readiness", data.trainingReadiness?.toString() ?: "—", GlanceModifier.defaultWeight())
            Metric("Resting HR", data.restingHeartRate?.toString() ?: "—", GlanceModifier.defaultWeight())
        }
    }
    if (spec.activityDp > 0) {
        if (spec.afterMetricsSpacerDp > 0) {
            Spacer(GlanceModifier.height(spec.afterMetricsSpacerDp.dp))
        }
        ActivityStrip(
            activity = data.lastActivity,
            zoneId = zoneId,
            locale = locale,
            rich = true,
            heightDp = spec.activityDp.dp,
            showHrChart = spec.showActivityHrChart,
            hrChartHeightDp = spec.activityHrChartHeightDp.dp,
            chartWidthDp = (spec.contentWidthDp - 12f).dp,
        )
    }
}

@Composable
private fun EqualPanel(
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
private fun CompactSleepRing(data: WidgetResponse, modifier: GlanceModifier = GlanceModifier, ringDp: Dp = 52.dp) {
    val px = LayoutMetrics.dpToRenderPx(ringDp.value)
    Box(contentAlignment = Alignment.Center, modifier = modifier.width(ringDp).height(ringDp)) {
        Image(
            provider = ImageProvider(drawSleepRingBitmap(px, buildSleepRingSegments(data.sleepStages))),
            contentDescription = sleepRingContentDescription(data.sleepScore, data.sleepStages != null),
            modifier = GlanceModifier.fillMaxSize(),
        )
        Text(
            text = data.sleepScore?.toString() ?: "—",
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun SleepLegendGrid(@Suppress("UNUSED_PARAMETER") stages: SleepStages?) {
    val labels = sleepLegendPresentation()
    Column {
        Row {
            LegendChip(labels[0], WidgetPalette.deep)
            Spacer(GlanceModifier.width(4.dp))
            LegendChip(labels[1], WidgetPalette.light)
        }
        Row {
            LegendChip(labels[2], WidgetPalette.rem)
            Spacer(GlanceModifier.width(4.dp))
            LegendChip(labels[3], WidgetPalette.awake)
        }
    }
}

@Composable
private fun LegendChip(label: String, color: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(drawLegendSwatchBitmap(color, 14)),
            contentDescription = null,
            modifier = GlanceModifier.width(7.dp).height(7.dp),
        )
        Spacer(GlanceModifier.width(2.dp))
        Text(
            text = label,
            style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 7.sp),
            maxLines = 1,
        )
    }
}

@Composable
private fun CombinedChartImage(
    bodyBattery: List<TimelinePoint>,
    stress: List<TimelinePoint>,
    bodyBatteryValue: Int?,
    stressValue: Int?,
    widthDp: Dp,
    heightDp: Dp,
) {
    val (wPx, hPx) = LayoutMetrics.chartRenderSize(widthDp.value, heightDp.value)
    Image(
        provider = ImageProvider(drawCombinedChartBitmap(wPx, hPx, bodyBattery, stress)),
        contentDescription = chartContentDescription(bodyBattery.size, stress.size, bodyBatteryValue, stressValue),
        modifier = GlanceModifier.width(widthDp).height(heightDp),
    )
}

@Composable
private fun HrvGraphImage(
    points: List<HrvTrendPoint>,
    maxPoints: Int,
    widthDp: Dp,
    heightDp: Dp,
    showMidLabel: Boolean,
) {
    val (wPx, hPx) = LayoutMetrics.chartRenderSize(widthDp.value, heightDp.value)
    Image(
        provider = ImageProvider(drawHrvGraphBitmap(wPx, hPx, points, maxPoints, showMidLabel)),
        contentDescription = "HRV trend graph from ${HRV_SCALE_MIN_MS} to ${HRV_SCALE_MAX_MS} milliseconds",
        modifier = GlanceModifier.width(widthDp).height(heightDp),
    )
}

@Composable
private fun ActivityHrChartImage(
    timeline: List<ActivityHeartRatePoint>,
    widthDp: Dp,
    heightDp: Dp,
) {
    if (heightDp.value < 8f) return
    val (wPx, hPx) = LayoutMetrics.chartRenderSize(widthDp.value, heightDp.value)
    val bitmap = drawActivityHrChartBitmap(wPx, hPx, timeline) ?: return
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "Activity heart rate chart",
        modifier = GlanceModifier.width(widthDp).height(heightDp),
    )
}

@Composable
private fun ActivityStrip(
    activity: LastActivity?,
    zoneId: ZoneId,
    locale: Locale,
    rich: Boolean,
    heightDp: Dp,
    showHrChart: Boolean,
    hrChartHeightDp: Dp,
    chartWidthDp: Dp,
) {
    if (activity == null) {
        Text(
            text = "No recent activity",
            style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 9.sp),
            modifier = GlanceModifier.height(heightDp),
        )
        return
    }
    val typeKey = activityTypeIcon(activity.typeKey)
    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(heightDp)
            .background(ColorProvider(Color(0x1FFFFFFF)))
            .cornerRadius(10.dp)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(drawActivityIconBitmap(typeKey, LayoutMetrics.dpToRenderPx(18f))),
                contentDescription = activityIconContentDescription(activity.typeKey),
                modifier = GlanceModifier.width(16.dp).height(16.dp),
            )
            Spacer(GlanceModifier.width(4.dp))
            Text(
                text = activity.name ?: activity.typeKey ?: "Activity",
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
        }
        Text(
            text = buildString {
                append(formatLocalTime(activity.startedAt, zoneId, locale))
                append("  ")
                append(formatDuration(activity.durationSeconds))
                append("  ")
                append(formatDistanceKm(activity.distanceMeters))
                append("  ")
                append(activityPrimaryMetric(activity))
                append("  ")
                append(formatCalories(activity.calories))
                append("  Avg ")
                append(formatHr(activity.averageHeartRate))
                if (activity.maxHeartRate != null) {
                    append("  Max HR ")
                    append(formatHr(activity.maxHeartRate))
                }
            },
            style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 8.sp),
            maxLines = if (rich) 2 else 1,
        )
        if (rich) {
            val details = activityDetailPairs(activity, rich = true)
                .filterNot { it.first == "Max HR" }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString("  ") { "${it.first}:${it.second}" },
                    style = TextStyle(color = ColorProvider(Color(0xFFB0BEC5)), fontSize = 8.sp),
                    maxLines = 1,
                )
            }
        }
        if (showHrChart && activity.heartRateTimeline.size >= 2) {
            Spacer(GlanceModifier.height(2.dp))
            ActivityHrChartImage(activity.heartRateTimeline, chartWidthDp, hrChartHeightDp)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(text = value, style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Text(text = label, style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 8.sp))
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun resolveVisibleStatus(configured: Boolean, storedStatus: LocalStatus, refreshRevision: Long): LocalStatus {
    return if (configured) storedStatus else LocalStatus.NOT_CONFIGURED
}

private fun statusMessage(status: LocalStatus): String = when (status) {
    LocalStatus.NOT_CONFIGURED -> "Set token in app, then refresh."
    LocalStatus.AUTH_ERROR -> "Token rejected. Update and refresh."
    LocalStatus.NETWORK_ERROR -> "Offline. Cached data shown when available."
    LocalStatus.REFRESHING -> "Refreshing…"
    else -> "No cached data yet. Tap refresh."
}

@Composable
private fun EmptyState(status: LocalStatus) {
    Text(
        text = statusMessage(status),
        style = TextStyle(color = ColorProvider(Color(0xFFD6E2E5)), fontSize = 12.sp),
        maxLines = 3,
    )
}

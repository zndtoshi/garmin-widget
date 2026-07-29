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
import com.zndtoshi.garminwidget.data.TimelinePoint
import com.zndtoshi.garminwidget.data.WidgetResponse
import com.zndtoshi.garminwidget.data.WidgetStore
import java.time.Instant
import java.time.ZoneId

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

/** Sole widget provider identity — keep class name so already-placed widgets survive updates. */
class GarminWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GarminWidget()
}

@Composable
private fun WidgetContent(data: WidgetResponse?, status: LocalStatus, opacityPercent: Int) {
    val spec = LayoutMetrics.fromSize(LocalSize.current)
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
            val bb = filterTimelineForResponseDate(data.bodyBatteryTimeline, data.date, zone)
            val st = filterTimelineForResponseDate(data.stressTimeline, data.date, zone)
            Column(modifier = GlanceModifier.fillMaxWidth().clickable(actionRunCallback<OpenGarminAction>())) {
                TwoRowLayout(data, bb, st, zone, spec)
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
private fun TwoRowLayout(
    data: WidgetResponse,
    bodyBattery: List<TimelinePoint>,
    stress: List<TimelinePoint>,
    zoneId: ZoneId,
    spec: AdaptiveLayoutSpec,
) {
    val dayRange = timelineDayRange(data.date, zoneId)
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(spec.healthRowDp.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EqualPanel(widthDp = spec.panelWidthDp.dp, heightDp = spec.healthRowDp.dp) {
            SleepPanel(data, spec)
        }
        EqualPanel(widthDp = spec.panelWidthDp.dp, heightDp = spec.healthRowDp.dp) {
            HrvPanel(data, spec)
        }
        EqualPanel(widthDp = spec.panelWidthDp.dp, heightDp = spec.healthRowDp.dp) {
            BodyBatteryPanel(data, bodyBattery, stress, spec, dayRange?.first, dayRange?.second)
        }
    }
    if (spec.afterHealthSpacerDp > 0) {
        Spacer(GlanceModifier.height(spec.afterHealthSpacerDp.dp))
    }
    ActivityStrip(
        activity = data.lastActivity,
        heightDp = spec.activityDp.dp,
        showHrChart = spec.showActivityHrChart,
        hrChartHeightDp = spec.activityHrChartHeightDp.dp,
        chartWidthDp = (spec.contentWidthDp - 12f).dp,
    )
}

@Composable
private fun SleepPanel(data: WidgetResponse, spec: AdaptiveLayoutSpec) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.Top,
    ) {
        SleepRing(
            data = data,
            ringDp = spec.sleepRingDp.dp,
            scoreFontSp = sleepScoreFontSp(spec.widthDp),
            durationFontSp = sleepDurationFontSp(spec.widthDp),
        )
    }
}

@Composable
private fun HrvPanel(data: WidgetResponse, spec: AdaptiveLayoutSpec) {
    val header = hrvHeaderPresentation(data.overnightHrv, data.hrvStatus, spec.showPanelTitles)
    Column(modifier = GlanceModifier.fillMaxSize()) {
        HealthPanelHeader(HealthPanelIcon.HRV, header)
        if (header.supportingLeft != null) {
            Text(
                text = header.supportingLeft,
                style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 8.sp),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.defaultWeight())
        if (hasRenderableHrvTrend(data.hrvTrend)) {
            HrvGraphImage(
                points = data.hrvTrend,
                maxPoints = 7,
                widthDp = spec.hrvGraphWidthDp.dp,
                heightDp = spec.hrvGraphHeightDp.dp,
                showMidLabel = true,
            )
        }
    }
}

@Composable
private fun BodyBatteryPanel(
    data: WidgetResponse,
    bodyBattery: List<TimelinePoint>,
    stress: List<TimelinePoint>,
    spec: AdaptiveLayoutSpec,
    rangeStart: Instant?,
    rangeEnd: Instant?,
) {
    val header = bodyBatteryHeaderPresentation(data.bodyBattery, spec.showPanelTitles)
    Column(modifier = GlanceModifier.fillMaxSize()) {
        HealthPanelHeader(HealthPanelIcon.BODY_BATTERY, header)
        Spacer(GlanceModifier.defaultWeight())
        CombinedChartImage(
            bodyBattery = bodyBattery,
            stress = stress,
            bodyBatteryValue = data.bodyBattery,
            stressValue = data.stress,
            widthDp = spec.panelChartWidthDp.dp,
            heightDp = spec.panelChartHeightDp.dp,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
        )
    }
}

@Composable
private fun HealthPanelHeader(icon: HealthPanelIcon, header: HealthPanelHeaderPresentation) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(drawHealthPanelIconBitmap(icon, LayoutMetrics.dpToRenderPx(14f))),
            contentDescription = healthPanelIconContentDescription(icon),
            modifier = GlanceModifier.width(12.dp).height(12.dp),
        )
        if (header.title != null) {
            Spacer(GlanceModifier.width(3.dp))
            Text(
                text = header.title,
                style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 8.sp),
                maxLines = 1,
            )
        }
        if (header.trailingValue != null) {
            Spacer(GlanceModifier.width(5.dp))
            Text(
                text = header.trailingValue,
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EqualPanel(
    widthDp: Dp,
    heightDp: Dp,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.TopStart,
        modifier = GlanceModifier
            .width(widthDp)
            .height(heightDp)
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        Box(
            contentAlignment = Alignment.TopStart,
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(HEALTH_PANEL_CARD_COLOR))),
        ) {
            content()
        }
    }
}

@Composable
private fun SleepRing(
    data: WidgetResponse,
    modifier: GlanceModifier = GlanceModifier,
    ringDp: Dp = 52.dp,
    scoreFontSp: Float = 13f,
    durationFontSp: Float = 8f,
) {
    val px = LayoutMetrics.dpToRenderPx(ringDp.value)
    Box(contentAlignment = Alignment.Center, modifier = modifier.width(ringDp).height(ringDp)) {
        Image(
            provider = ImageProvider(drawSleepRingBitmap(px, buildSleepRingSegments(data.sleepStages))),
            contentDescription = sleepRingContentDescription(data.sleepScore, data.sleepStages != null),
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = data.sleepScore?.toString() ?: "—",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = scoreFontSp.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                text = formatSleepDuration(data.sleepDurationSeconds),
                style = TextStyle(
                    color = ColorProvider(Color(0xFF9FB5BB)),
                    fontSize = durationFontSp.sp,
                ),
                maxLines = 1,
            )
        }
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
    rangeStart: Instant?,
    rangeEnd: Instant?,
) {
    val (wPx, hPx) = LayoutMetrics.chartRenderSize(widthDp.value, heightDp.value)
    Image(
        provider = ImageProvider(drawCombinedChartBitmap(wPx, hPx, bodyBattery, stress, rangeStart, rangeEnd)),
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
    maxHeartRate: Int?,
) {
    if (heightDp.value < 8f) return
    val (wPx, hPx) = LayoutMetrics.chartRenderSize(widthDp.value, heightDp.value)
    val bitmap = drawActivityHrChartBitmap(wPx, hPx, timeline, maxHeartRate) ?: return
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "Activity heart rate chart",
        modifier = GlanceModifier.width(widthDp).height(heightDp),
    )
}

@Composable
private fun ActivityStrip(
    activity: LastActivity?,
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
            .cornerRadius(10.dp)
            .background(ColorProvider(Color(HEALTH_PANEL_CARD_COLOR)))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                )
                if (activity.maxHeartRate != null) {
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = "Max HR ${formatHr(activity.maxHeartRate)}",
                        style = TextStyle(color = ColorProvider(Color.White), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
            }
        }
        if (showHrChart && activity.heartRateTimeline.size >= 2) {
            Spacer(GlanceModifier.height(2.dp))
            ActivityHrChartImage(
                timeline = activity.heartRateTimeline,
                widthDp = chartWidthDp,
                heightDp = hrChartHeightDp,
                maxHeartRate = activity.maxHeartRate,
            )
        }
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

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
import com.zndtoshi.garminwidget.R
import com.zndtoshi.garminwidget.data.HrvTrendPoint
import com.zndtoshi.garminwidget.data.LastActivity
import com.zndtoshi.garminwidget.data.LocalStatus
import com.zndtoshi.garminwidget.data.LowerCardKind
import com.zndtoshi.garminwidget.data.LowerCardState
import com.zndtoshi.garminwidget.data.SettingsStore
import com.zndtoshi.garminwidget.data.TimelinePoint
import com.zndtoshi.garminwidget.data.WidgetResponse
import com.zndtoshi.garminwidget.data.WidgetStore
import com.zndtoshi.garminwidget.data.resolveVisibleLowerCard
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
            val store = WidgetStore(context)
            val state = store.read()
            WidgetContent(
                data = state.data,
                status = resolveVisibleStatus(settings.isConfigured(), state.status, refreshRevision),
                opacityPercent = settings.widgetOpacityPercent(),
                lowerCard = state.lowerCard,
            )
        }
    }
}

/** Sole widget provider identity — keep class name so already-placed widgets survive updates. */
class GarminWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GarminWidget()
}

@Composable
private fun WidgetContent(
    data: WidgetResponse?,
    status: LocalStatus,
    opacityPercent: Int,
    lowerCard: LowerCardState,
) {
    val spec = LayoutMetrics.fromSize(LocalSize.current)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF101B2A).copy(alpha = opacityPercentToAlpha(opacityPercent))))
            .padding(LayoutMetrics.OUTER_PADDING_DP.dp),
    ) {
        HeaderRow(status)
        Spacer(GlanceModifier.height(LayoutMetrics.AFTER_HEADER_SPACER_DP.dp))
        if (data == null) {
            EmptyState(status)
        } else {
            TwoRowLayout(data, spec, lowerCard)
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
    spec: AdaptiveLayoutSpec,
    lowerCard: LowerCardState,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(spec.healthRowDp.dp)
            .clickable(actionRunCallback<OpenGarminAction>()),
        verticalAlignment = Alignment.Top,
    ) {
        EqualPanel(widthDp = spec.panelWidthDp.dp, heightDp = spec.healthRowDp.dp) {
            SleepPanel(data, spec)
        }
        EqualPanel(widthDp = spec.panelWidthDp.dp, heightDp = spec.healthRowDp.dp) {
            HrvPanel(data, spec)
        }
        EqualPanel(widthDp = spec.panelWidthDp.dp, heightDp = spec.healthRowDp.dp) {
            TrainingReadinessPanel(data, spec)
        }
    }
    if (spec.afterHealthSpacerDp > 0) {
        Spacer(GlanceModifier.height(spec.afterHealthSpacerDp.dp))
    }
    LowerCardHost(
        data = data,
        kind = resolveVisibleLowerCard(data, lowerCard),
        heightDp = spec.activityDp.dp,
        showHrChart = spec.showActivityHrChart,
        hrChartHeightDp = spec.activityHrChartHeightDp.dp,
        chartWidthDp = spec.activityChartContentWidthDp.dp,
    )
}

@Composable
private fun LowerCardHost(
    data: WidgetResponse,
    kind: LowerCardKind,
    heightDp: Dp,
    showHrChart: Boolean,
    hrChartHeightDp: Dp,
    chartWidthDp: Dp,
) {
    when (kind) {
        LowerCardKind.BODY_BATTERY -> BodyBatteryStrip(
            data = data,
            heightDp = heightDp,
            chartHeightDp = hrChartHeightDp,
            chartWidthDp = chartWidthDp,
        )
        LowerCardKind.ACTIVITY -> ActivityStrip(
            activity = data.lastActivity,
            heightDp = heightDp,
            showHrChart = showHrChart,
            hrChartHeightDp = hrChartHeightDp,
            chartWidthDp = chartWidthDp,
        )
        LowerCardKind.NONE -> Spacer(GlanceModifier.fillMaxWidth().height(heightDp))
    }
}

@Composable
private fun SleepPanel(data: WidgetResponse, spec: AdaptiveLayoutSpec) {
    val header = sleepHeaderPresentation(spec.showPanelTitles)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.Top,
    ) {
        HealthPanelHeader(HealthPanelIcon.SLEEP, header)
        Spacer(GlanceModifier.defaultWeight())
        SleepRing(
            data = data,
            ringDp = spec.sleepRingDp.dp,
            scoreFontSp = sleepScoreFontSp(spec.widthDp),
            durationFontSp = sleepDurationFontSp(spec.widthDp),
        )
        Spacer(GlanceModifier.defaultWeight())
    }
}

@Composable
private fun HrvPanel(data: WidgetResponse, spec: AdaptiveLayoutSpec) {
    val header = hrvHeaderPresentation(data.overnightHrv, data.hrvStatus, spec.showPanelTitles)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
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
                maxPoints = 28,
                widthDp = spec.hrvGraphWidthDp.dp,
                heightDp = spec.hrvGraphHeightDp.dp,
                showMidLabel = true,
            )
        }
    }
}

@Composable
private fun TrainingReadinessPanel(data: WidgetResponse, spec: AdaptiveLayoutSpec) {
    val header = trainingReadinessHeaderPresentation(spec.showPanelTitles)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HealthPanelHeader(HealthPanelIcon.TRAINING_READINESS, header)
        Spacer(GlanceModifier.defaultWeight())
        TrainingReadinessRing(
            score = data.trainingReadiness,
            ringDp = spec.trainingReadinessRingDp.dp,
            scoreFontSp = trainingReadinessScoreFontSp(spec.widthDp),
            classificationFontSp = trainingReadinessClassificationFontSp(spec.widthDp),
        )
        Spacer(GlanceModifier.defaultWeight())
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
                    fontSize = HEALTH_TRAILING_VALUE_FONT_SP.sp,
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
            provider = ImageProvider(
                drawSleepRingBitmap(
                    px,
                    buildSleepRingSegments(data.sleepStages),
                    formatRemRingDuration(data.sleepStages?.remSeconds),
                ),
            ),
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
                    color = ColorProvider(Color.White),
                    fontSize = durationFontSp.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TrainingReadinessRing(
    score: Int?,
    modifier: GlanceModifier = GlanceModifier,
    ringDp: Dp = 48.dp,
    scoreFontSp: Float = 16f,
    classificationFontSp: Float = 9f,
) {
    val px = LayoutMetrics.dpToRenderPx(ringDp.value)
    Box(contentAlignment = Alignment.Center, modifier = modifier.width(ringDp).height(ringDp)) {
        Image(
            provider = ImageProvider(drawTrainingReadinessRingBitmap(px, score)),
            contentDescription = trainingReadinessContentDescription(score),
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = trainingReadinessScoreLabel(score),
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = scoreFontSp.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                text = trainingReadinessClassification(score),
                style = TextStyle(
                    color = ColorProvider(Color(0xFFCFD8DC)),
                    fontSize = classificationFontSp.sp,
                ),
                maxLines = 1,
            )
        }
    }
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
    timeline: List<com.zndtoshi.garminwidget.data.ActivityHeartRatePoint>,
    speedTimeline: List<com.zndtoshi.garminwidget.data.ActivitySpeedPoint> = emptyList(),
    widthDp: Dp,
    heightDp: Dp,
    maxHeartRate: Int?,
) {
    if (heightDp.value < 8f) return
    val (wPx, hPx) = LayoutMetrics.chartRenderSize(widthDp.value, heightDp.value)
    val bitmap = drawActivityHrChartBitmap(wPx, hPx, timeline, maxHeartRate, speedTimeline) ?: return
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = activityChartContentDescription(timeline, speedTimeline),
        modifier = GlanceModifier.width(widthDp).height(heightDp),
    )
}

@Composable
private fun BodyBatteryStrip(
    data: WidgetResponse,
    heightDp: Dp,
    chartHeightDp: Dp,
    chartWidthDp: Dp,
) {
    val zone = ZoneId.systemDefault()
    val bodyBattery = appendCurrentBodyBatteryPoint(
        points = filterTimelineForResponseDate(data.bodyBatteryTimeline, data.date, zone),
        currentValue = data.bodyBattery,
        refreshedAt = data.refreshedAt,
        responseDate = data.date,
        zoneId = zone,
    )
    val stress = filterTimelineForResponseDate(data.stressTimeline, data.date, zone)
    val dayRange = timelineDayRange(data.date, zone)
    val header = bodyBatteryHeaderPresentation(data.bodyBattery, showTitle = true)
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(heightDp)
            .padding(horizontal = LayoutMetrics.ACTIVITY_CARD_OUTER_HORIZONTAL_PADDING_DP.dp),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.activity_card_background))
                .clickable(actionRunCallback<ToggleLowerCardAction>())
                .padding(
                    horizontal = LayoutMetrics.ACTIVITY_CARD_INNER_HORIZONTAL_PADDING_DP.dp,
                    vertical = LayoutMetrics.ACTIVITY_CARD_INNER_VERTICAL_PADDING_DP.dp,
                ),
        ) {
            Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        provider = ImageProvider(
                            drawHealthPanelIconBitmap(
                                HealthPanelIcon.BODY_BATTERY,
                                LayoutMetrics.dpToRenderPx(14f),
                            ),
                        ),
                        contentDescription = healthPanelIconContentDescription(HealthPanelIcon.BODY_BATTERY),
                        modifier = GlanceModifier.width(12.dp).height(12.dp),
                    )
                    Spacer(GlanceModifier.width(4.dp))
                    Text(
                        text = header.title ?: "Body Battery",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFF9FB5BB)),
                            fontSize = 8.sp,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = header.trailingValue ?: "—",
                        style = TextStyle(
                            color = ColorProvider(Color.White),
                            fontSize = HEALTH_TRAILING_VALUE_FONT_SP.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                }
                Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    LowerCardDismissButton()
                }
            }
            Spacer(GlanceModifier.height(LayoutMetrics.ACTIVITY_HEADER_CHART_GAP_DP.dp))
            CombinedChartImage(
                bodyBattery = bodyBattery,
                stress = stress,
                bodyBatteryValue = data.bodyBattery,
                stressValue = data.stress,
                widthDp = chartWidthDp,
                heightDp = chartHeightDp,
                rangeStart = dayRange?.first,
                rangeEnd = dayRange?.second,
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
    if (heightDp.value < 8f) return
    val (wPx, hPx) = LayoutMetrics.chartRenderSize(widthDp.value, heightDp.value)
    Image(
        provider = ImageProvider(
            drawCombinedChartBitmap(wPx, hPx, bodyBattery, stress, rangeStart, rangeEnd),
        ),
        contentDescription = chartContentDescription(
            bodyBattery.size,
            stress.size,
            bodyBatteryValue,
            stressValue,
        ),
        modifier = GlanceModifier.width(widthDp).height(heightDp),
    )
}

@Composable
private fun LowerCardDismissButton() {
    Box(
        modifier = GlanceModifier
            .width(22.dp)
            .height(18.dp)
            .clickable(actionRunCallback<DismissLowerCardAction>()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "×",
            style = TextStyle(
                color = ColorProvider(Color(0xFFD7DEE3)),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
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
        Spacer(GlanceModifier.fillMaxWidth().height(heightDp))
        return
    }
    val typeKey = activityTypeIcon(activity.typeKey)
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(heightDp)
            .padding(horizontal = LayoutMetrics.ACTIVITY_CARD_OUTER_HORIZONTAL_PADDING_DP.dp),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ImageProvider(R.drawable.activity_card_background))
                .clickable(actionRunCallback<ToggleLowerCardAction>())
                .padding(
                    horizontal = LayoutMetrics.ACTIVITY_CARD_INNER_HORIZONTAL_PADDING_DP.dp,
                    vertical = LayoutMetrics.ACTIVITY_CARD_INNER_VERTICAL_PADDING_DP.dp,
                ),
        ) {
            Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                Row(
                    modifier = GlanceModifier.width((chartWidthDp.value * ACTIVITY_NAME_MAX_WIDTH_FRACTION).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        provider = ImageProvider(drawActivityIconBitmap(typeKey, LayoutMetrics.dpToRenderPx(18f))),
                        contentDescription = activityIconContentDescription(activity.typeKey),
                        modifier = GlanceModifier.width(16.dp).height(16.dp),
                    )
                    Spacer(GlanceModifier.width(4.dp))
                    Text(
                        text = activity.name ?: activity.typeKey ?: "Activity",
                        style = TextStyle(
                            color = ColorProvider(Color(0xFFF2F5F7)),
                            fontSize = ACTIVITY_TITLE_FONT_SP.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                }
                if (activity.maxHeartRate != null) {
                    Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Text(
                            text = "Max HR ${formatHr(activity.maxHeartRate)}",
                            style = TextStyle(
                                color = ColorProvider(Color(0xFFF8FAFB)),
                                fontSize = ACTIVITY_MAX_HR_FONT_SP.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                    }
                }
                Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    LowerCardDismissButton()
                }
            }
            if (showHrChart && (
                    activity.heartRateTimeline.size >= 2 || activity.speedTimeline.size >= 2
                    )
            ) {
                Spacer(GlanceModifier.height(LayoutMetrics.ACTIVITY_HEADER_CHART_GAP_DP.dp))
                ActivityHrChartImage(
                    timeline = activity.heartRateTimeline,
                    speedTimeline = activity.speedTimeline,
                    widthDp = chartWidthDp,
                    heightDp = hrChartHeightDp,
                    maxHeartRate = activity.maxHeartRate,
                )
            }
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

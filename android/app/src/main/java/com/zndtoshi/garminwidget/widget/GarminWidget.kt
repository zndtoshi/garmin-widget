package com.zndtoshi.garminwidget.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.zndtoshi.garminwidget.data.LocalStatus
import com.zndtoshi.garminwidget.data.SettingsStore
import com.zndtoshi.garminwidget.data.WidgetResponse
import com.zndtoshi.garminwidget.data.WidgetStore

class GarminWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(
        setOf(
            androidx.compose.ui.unit.DpSize(180.dp, 110.dp),
            androidx.compose.ui.unit.DpSize(300.dp, 180.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = WidgetStore(context).read()
        val configured = SettingsStore(context).isConfigured()
        provideContent {
            WidgetContent(
                data = state.data,
                status = if (configured) state.status else LocalStatus.NOT_CONFIGURED,
            )
        }
    }
}

class GarminWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GarminWidget()
}

@Composable
private fun WidgetContent(
    data: WidgetResponse?,
    status: LocalStatus,
) {
    val isExpanded = LocalSize.current.width >= 260.dp
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF102A32)))
            .padding(12.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionRunCallback<OpenGarminAction>()),
            ) {
                Text(
                    text = "GARMIN",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF71E2D0)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = data?.date ?: statusTitle(status),
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp),
                )
            }
            Text(
                text = if (status == LocalStatus.REFRESHING) "Refreshing…" else "↻ Refresh",
                modifier = GlanceModifier
                    .background(ColorProvider(Color(0xFF21454F)))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clickable(actionRunCallback<RefreshAction>()),
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 12.sp),
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionRunCallback<OpenGarminAction>()),
        ) {
            if (data == null) {
                Text(
                    text = statusMessage(status),
                    style = TextStyle(color = ColorProvider(Color(0xFFD6E2E5)), fontSize = 13.sp),
                )
            } else {
                MetricRow(
                    firstLabel = "Sleep",
                    firstValue = data.sleepScore?.toString() ?: "—",
                    secondLabel = "Battery",
                    secondValue = data.bodyBattery?.toString() ?: "—",
                    thirdLabel = "Readiness",
                    thirdValue = data.trainingReadiness?.toString() ?: "—",
                )
                if (isExpanded) {
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    MetricRow(
                        firstLabel = "HRV",
                        firstValue = data.overnightHrv?.toString() ?: "—",
                        secondLabel = "Stress",
                        secondValue = data.stress?.toString() ?: "—",
                        thirdLabel = "Resting HR",
                        thirdValue = data.restingHeartRate?.toString() ?: "—",
                    )
                }
                Spacer(modifier = GlanceModifier.height(6.dp))
                Text(
                    text = footer(data, status),
                    style = TextStyle(
                        color = ColorProvider(if (data.stale) Color(0xFFFFC46B) else Color(0xFF9FB5BB)),
                        fontSize = 10.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MetricRow(
    firstLabel: String,
    firstValue: String,
    secondLabel: String,
    secondValue: String,
    thirdLabel: String,
    thirdValue: String,
) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        Metric(firstLabel, firstValue, GlanceModifier.defaultWeight())
        Metric(secondLabel, secondValue, GlanceModifier.defaultWeight())
        Metric(thirdLabel, thirdValue, GlanceModifier.defaultWeight())
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = TextStyle(
                color = ColorProvider(Color.White),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = label,
            style = TextStyle(color = ColorProvider(Color(0xFF9FB5BB)), fontSize = 10.sp),
        )
    }
}

private fun statusTitle(status: LocalStatus): String = when (status) {
    LocalStatus.NOT_CONFIGURED -> "Setup required"
    LocalStatus.AUTH_ERROR -> "Authentication failed"
    LocalStatus.NETWORK_ERROR -> "Offline"
    LocalStatus.REFRESHING -> "Refreshing"
    else -> "No cached data"
}

private fun statusMessage(status: LocalStatus): String = when (status) {
    LocalStatus.NOT_CONFIGURED -> "Open the app and save your private widget token."
    LocalStatus.AUTH_ERROR -> "Open the app and check your widget token."
    LocalStatus.NETWORK_ERROR -> "Could not reach the backend. Tap refresh to retry."
    LocalStatus.REFRESHING -> "Fetching your latest Garmin metrics…"
    else -> "Tap refresh to load today’s Garmin metrics."
}

private fun footer(data: WidgetResponse, status: LocalStatus): String {
    val prefix = when {
        data.stale -> "Stale data"
        status == LocalStatus.NETWORK_ERROR -> "Offline · cached data"
        status == LocalStatus.AUTH_ERROR -> "Token rejected · cached data"
        data.refreshStatus.name == "COOLDOWN" -> "Cooldown · cached data"
        else -> "Updated"
    }
    return "$prefix · tap card for Garmin Connect"
}

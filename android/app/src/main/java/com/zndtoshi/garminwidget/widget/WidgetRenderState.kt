package com.zndtoshi.garminwidget.widget

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition

internal val RefreshRevisionKey = longPreferencesKey("refresh_revision")

internal fun nextRefreshRevision(current: Long?): Long = (current ?: 0L) + 1L

suspend fun bumpWidgetRefreshRevision(context: Context) {
    val manager = GlanceAppWidgetManager(context)
    val glanceIds = manager.getGlanceIds(GarminWidget::class.java)
    for (glanceId in glanceIds) {
        updateAppWidgetState(
            context = context,
            definition = PreferencesGlanceStateDefinition,
            glanceId = glanceId,
        ) { preferences ->
            preferences.toMutablePreferences().apply {
                this[RefreshRevisionKey] = nextRefreshRevision(this[RefreshRevisionKey])
            }
        }
    }
    GarminWidget().updateAll(context)
}

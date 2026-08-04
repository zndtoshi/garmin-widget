package com.zndtoshi.garminwidget.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.zndtoshi.garminwidget.data.WidgetStore

class ToggleLowerCardAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        WidgetStore(context).toggleVisibleLowerCard()
        bumpWidgetRefreshRevision(context)
    }
}

class DismissLowerCardAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        WidgetStore(context).dismissVisibleLowerCard()
        bumpWidgetRefreshRevision(context)
    }
}

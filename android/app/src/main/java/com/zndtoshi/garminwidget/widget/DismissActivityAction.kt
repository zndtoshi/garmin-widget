package com.zndtoshi.garminwidget.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.zndtoshi.garminwidget.data.WidgetStore

internal val DismissActivityIdentityKey =
    ActionParameters.Key<String>("dismiss_activity_identity")

class DismissActivityAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val identity = parameters[DismissActivityIdentityKey] ?: return
        WidgetStore(context).dismissActivity(identity)
        bumpWidgetRefreshRevision(context)
    }
}

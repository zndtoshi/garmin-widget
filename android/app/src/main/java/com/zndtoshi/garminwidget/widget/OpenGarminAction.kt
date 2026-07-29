package com.zndtoshi.garminwidget.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

class OpenGarminAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val intent = context.packageManager.getLaunchIntentForPackage(GARMIN_CONNECT_PACKAGE)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(GARMIN_CONNECT_URL))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private companion object {
        const val GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"
        const val GARMIN_CONNECT_URL = "https://connect.garmin.com/modern/"
    }
}

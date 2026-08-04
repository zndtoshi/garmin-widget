package com.zndtoshi.garminwidget.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.zndtoshi.garminwidget.data.WidgetStore
import com.zndtoshi.garminwidget.work.RefreshScheduler

class OpenGarminAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val packageManager = context.packageManager
        val hasGarminConnect = runCatching {
            packageManager.getPackageInfo(GARMIN_CONNECT_PACKAGE, PackageManager.GET_ACTIVITIES)
            true
        }.getOrElse { false }
        val intent = if (hasGarminConnect) {
            packageManager.getLaunchIntentForPackage(GARMIN_CONNECT_PACKAGE)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    `package` = GARMIN_CONNECT_PACKAGE
                }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(GARMIN_CONNECT_URL))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        WidgetStore(context).restoreBodyBatteryCard()
        bumpWidgetRefreshRevision(context)
        RefreshScheduler.scheduleAfterGarminConnect(context)
        context.startActivity(intent)
    }

    private companion object {
        const val GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"
        const val GARMIN_CONNECT_URL = "https://connect.garmin.com/modern/"
    }
}

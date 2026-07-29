package com.zndtoshi.garminwidget.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zndtoshi.garminwidget.data.WidgetStore
import com.zndtoshi.garminwidget.widget.GarminWidget

object RefreshScheduler {
    suspend fun enqueue(context: Context) {
        WidgetStore(context).markRefreshing()
        GarminWidget().updateAll(context)
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private const val UNIQUE_WORK_NAME = "garmin-widget-manual-refresh"
}

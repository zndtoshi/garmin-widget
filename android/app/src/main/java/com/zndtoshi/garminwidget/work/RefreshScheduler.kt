package com.zndtoshi.garminwidget.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.zndtoshi.garminwidget.data.WidgetStore
import com.zndtoshi.garminwidget.widget.bumpWidgetRefreshRevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

object RefreshScheduler {
    fun schedulePeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(
            AUTOMATIC_REFRESH_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun enqueue(context: Context): UUID {
        val workManager = WorkManager.getInstance(context)
        val runningRequestId = withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
                .get()
                .firstOrNull { !it.state.isFinished }
                ?.id
        }
        if (runningRequestId != null) {
            return runningRequestId
        }

        WidgetStore(context).markRefreshing()
        bumpWidgetRefreshRevision(context)
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .build()
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
        return withContext(Dispatchers.IO) {
            workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
                .get()
                .firstOrNull { !it.state.isFinished }
                ?.id
                ?: request.id
        }
    }

    fun scheduleAfterGarminConnect(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(GARMIN_SYNC_REFRESH_DELAY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            GARMIN_SYNC_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    internal const val AUTOMATIC_REFRESH_INTERVAL_MINUTES = 15L
    internal const val GARMIN_SYNC_REFRESH_DELAY_MINUTES = 2L
    internal const val PERIODIC_WORK_NAME = "garmin-widget-periodic-refresh"
    internal const val GARMIN_SYNC_WORK_NAME = "garmin-widget-after-garmin-sync"
    private const val UNIQUE_WORK_NAME = "garmin-widget-manual-refresh"
}

package com.zndtoshi.garminwidget.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.zndtoshi.garminwidget.data.WidgetStore
import com.zndtoshi.garminwidget.widget.bumpWidgetRefreshRevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object RefreshScheduler {
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

    private const val UNIQUE_WORK_NAME = "garmin-widget-manual-refresh"
}

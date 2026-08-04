package com.zndtoshi.garminwidget.work

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zndtoshi.garminwidget.data.LocalStatus
import com.zndtoshi.garminwidget.data.SettingsStore
import com.zndtoshi.garminwidget.data.WidgetStore
import com.zndtoshi.garminwidget.network.WidgetApiClient
import com.zndtoshi.garminwidget.network.WidgetAuthException
import com.zndtoshi.garminwidget.widget.bumpWidgetRefreshRevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)
        val store = WidgetStore(applicationContext)
        val token = settings.bearerToken()
        if (token == null) {
            store.saveFailure(LocalStatus.NOT_CONFIGURED)
            bumpWidgetRefreshRevision(applicationContext)
            return Result.failure()
        }

        return try {
            val rawJson = withContext(Dispatchers.IO) {
                WidgetApiClient().refresh(settings.backendUrl(), token)
            }
            check(store.saveSuccessAndReconcile(rawJson)) {
                "Widget refresh returned a malformed response"
            }
            bumpWidgetRefreshRevision(applicationContext)
            Result.success()
        } catch (error: WidgetAuthException) {
            Log.e("GarminRefreshWorker", "Widget refresh authentication failed", error)
            store.saveFailure(LocalStatus.AUTH_ERROR)
            bumpWidgetRefreshRevision(applicationContext)
            Result.failure()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.e("GarminRefreshWorker", "Widget refresh network request failed", error)
            store.saveFailure(statusAfterTransientFailure(store.read().data != null))
            bumpWidgetRefreshRevision(applicationContext)
            Result.retry()
        } catch (error: RuntimeException) {
            Log.e("GarminRefreshWorker", "Widget refresh response processing failed", error)
            store.saveFailure(statusAfterTransientFailure(store.read().data != null))
            bumpWidgetRefreshRevision(applicationContext)
            Result.retry()
        }
    }
}

internal fun statusAfterTransientFailure(hasCachedData: Boolean): LocalStatus =
    if (hasCachedData) LocalStatus.READY else LocalStatus.NETWORK_ERROR

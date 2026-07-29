package com.zndtoshi.garminwidget.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zndtoshi.garminwidget.data.LocalStatus
import com.zndtoshi.garminwidget.data.SettingsStore
import com.zndtoshi.garminwidget.data.WidgetStore
import com.zndtoshi.garminwidget.network.WidgetApiClient
import com.zndtoshi.garminwidget.network.WidgetAuthException
import com.zndtoshi.garminwidget.widget.GarminWidget
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
            GarminWidget().updateAll(applicationContext)
            return Result.failure()
        }

        return try {
            val rawJson = withContext(Dispatchers.IO) {
                WidgetApiClient().refresh(settings.backendUrl(), token)
            }
            store.saveSuccess(rawJson)
            GarminWidget().updateAll(applicationContext)
            Result.success()
        } catch (_: WidgetAuthException) {
            store.saveFailure(LocalStatus.AUTH_ERROR)
            GarminWidget().updateAll(applicationContext)
            Result.failure()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            store.saveFailure(LocalStatus.NETWORK_ERROR)
            GarminWidget().updateAll(applicationContext)
            Result.failure()
        } catch (_: RuntimeException) {
            store.saveFailure(LocalStatus.NETWORK_ERROR)
            GarminWidget().updateAll(applicationContext)
            Result.failure()
        }
    }
}

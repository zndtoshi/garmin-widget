package com.zndtoshi.garminwidget

import android.app.Application
import androidx.work.Configuration
import com.zndtoshi.garminwidget.work.RefreshScheduler

class GarminWidgetApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        RefreshScheduler.schedulePeriodic(this)
    }
}

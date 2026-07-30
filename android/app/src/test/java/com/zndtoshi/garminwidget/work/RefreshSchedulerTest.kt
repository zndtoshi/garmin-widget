package com.zndtoshi.garminwidget.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshSchedulerTest {
    @Test
    fun `automatic refresh uses Android minimum periodic interval`() {
        assertEquals(15L, RefreshScheduler.AUTOMATIC_REFRESH_INTERVAL_MINUTES)
        assertTrue(RefreshScheduler.PERIODIC_WORK_NAME.contains("periodic"))
    }

    @Test
    fun `opening Garmin schedules refresh after cloud sync window`() {
        assertEquals(2L, RefreshScheduler.GARMIN_SYNC_REFRESH_DELAY_MINUTES)
        assertTrue(RefreshScheduler.GARMIN_SYNC_WORK_NAME.contains("garmin-sync"))
    }
}

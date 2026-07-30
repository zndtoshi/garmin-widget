package com.zndtoshi.garminwidget.work

import com.zndtoshi.garminwidget.data.LocalStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshWorkerStatusTest {
    @Test
    fun `transient failure keeps cached widget ready`() {
        assertEquals(LocalStatus.READY, statusAfterTransientFailure(hasCachedData = true))
    }

    @Test
    fun `transient failure is visible when no cache exists`() {
        assertEquals(LocalStatus.NETWORK_ERROR, statusAfterTransientFailure(hasCachedData = false))
    }
}

package com.zndtoshi.garminwidget.widget

import com.zndtoshi.garminwidget.data.LocalStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GarminWidgetStatusTest {
    @Test
    fun `uses stored status when configured`() {
        assertEquals(
            LocalStatus.READY,
            resolveVisibleStatus(configured = true, storedStatus = LocalStatus.READY, refreshRevision = 1L),
        )
        assertEquals(
            LocalStatus.AUTH_ERROR,
            resolveVisibleStatus(configured = true, storedStatus = LocalStatus.AUTH_ERROR, refreshRevision = 2L),
        )
    }

    @Test
    fun `forces not configured status when token missing`() {
        assertEquals(
            LocalStatus.NOT_CONFIGURED,
            resolveVisibleStatus(configured = false, storedStatus = LocalStatus.REFRESHING, refreshRevision = 3L),
        )
    }
}

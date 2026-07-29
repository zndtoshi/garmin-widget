package com.zndtoshi.garminwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRenderStateTest {
    @Test
    fun `increments revision from zero when missing`() {
        assertEquals(1L, nextRefreshRevision(null))
    }

    @Test
    fun `increments revision by one when present`() {
        assertEquals(42L, nextRefreshRevision(41L))
    }
}

package com.zndtoshi.garminwidget.ui

import com.zndtoshi.garminwidget.data.LocalStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshResultTextTest {
    @Test
    fun `returns success message for ready status`() {
        assertEquals(
            "Refresh complete. The widget is up to date.",
            refreshResultText(LocalStatus.READY),
        )
    }

    @Test
    fun `returns auth error message`() {
        assertEquals(
            "Token rejected. Check the Render bearer token.",
            refreshResultText(LocalStatus.AUTH_ERROR),
        )
    }

    @Test
    fun `returns network error message`() {
        assertEquals(
            "Could not reach the backend. Try again.",
            refreshResultText(LocalStatus.NETWORK_ERROR),
        )
    }

    @Test
    fun `returns configuration prompt when not configured`() {
        assertEquals(
            "Enter and save the widget token.",
            refreshResultText(LocalStatus.NOT_CONFIGURED),
        )
    }

    @Test
    fun `returns fallback message for non-terminal states`() {
        assertEquals(
            "Refresh did not complete. Try again.",
            refreshResultText(LocalStatus.REFRESHING),
        )
    }
}

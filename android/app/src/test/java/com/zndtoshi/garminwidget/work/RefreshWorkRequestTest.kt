package com.zndtoshi.garminwidget.work

import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshWorkRequestTest {
    @Test
    fun `manual refresh work has no network constraint`() {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>().build()
        val constraints = request.workSpec.constraints
        assertEquals(
            NetworkType.NOT_REQUIRED,
            constraints.requiredNetworkType,
        )
    }
}

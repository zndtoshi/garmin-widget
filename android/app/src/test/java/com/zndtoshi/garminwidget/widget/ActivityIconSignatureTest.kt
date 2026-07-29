package com.zndtoshi.garminwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityIconSignatureTest {
    @Test
    fun major_icon_categories_are_distinct_and_nonblank() {
        val kinds = listOf(
            "running", "walking", "hiking", "cycling", "strength_training",
            "swimming", "cardio", "yoga", "skiing", "generic",
        )
        val plans = kinds.map { activityIconDrawPlan(it) }
        assertEquals(10, plans.toSet().size)
        assertNotEquals(activityIconDrawPlan("running"), activityIconDrawPlan("walking"))
        assertNotEquals(activityIconDrawPlan("walking"), activityIconDrawPlan("hiking"))

        val signatures = kinds.associateWith { activityIconPixelSignature(it) }
        assertEquals(10, signatures.values.toSet().size)
    }
}

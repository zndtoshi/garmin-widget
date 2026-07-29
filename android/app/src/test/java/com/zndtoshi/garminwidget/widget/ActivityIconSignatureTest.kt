package com.zndtoshi.garminwidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun health_panel_icons_are_distinct_with_content_descriptions() {
        val kinds = HealthPanelIcon.entries
        val plans = kinds.map { healthPanelIconDrawPlan(it) }
        assertEquals(3, plans.toSet().size)
        assertNotEquals(healthPanelIconDrawPlan(HealthPanelIcon.SLEEP), healthPanelIconDrawPlan(HealthPanelIcon.HRV))
        assertNotEquals(healthPanelIconDrawPlan(HealthPanelIcon.HRV), healthPanelIconDrawPlan(HealthPanelIcon.BODY_BATTERY))
        kinds.forEach { kind ->
            assertTrue(healthPanelIconContentDescription(kind).isNotBlank())
            val bmp = drawHealthPanelIconBitmap(kind, 48)
            assertEquals(48, bmp.width)
            assertEquals(48, bmp.height)
        }
    }
}

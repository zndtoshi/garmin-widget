package com.zndtoshi.garminwidget.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class WidgetProviderManifestTest {
    @Test
    fun `merged source manifest declares exactly three exported widget receivers`() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("missing ${manifest.absolutePath}", manifest.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val receivers = doc.getElementsByTagName("receiver")
        val widgetReceivers = mutableListOf<Element>()
        for (i in 0 until receivers.length) {
            val receiver = receivers.item(i) as Element
            val filters = receiver.getElementsByTagName("intent-filter")
            var isWidget = false
            for (j in 0 until filters.length) {
                val actions = (filters.item(j) as Element).getElementsByTagName("action")
                for (k in 0 until actions.length) {
                    val action = actions.item(k) as Element
                    if (action.getAttribute("android:name") == "android.appwidget.action.APPWIDGET_UPDATE") {
                        isWidget = true
                    }
                }
            }
            if (isWidget) widgetReceivers += receiver
        }
        assertEquals(3, widgetReceivers.size)
        assertTrue(widgetReceivers.all { it.getAttribute("android:exported") == "true" })

        val names = widgetReceivers.map { it.getAttribute("android:name") }.toSet()
        assertEquals(
            setOf(
                ".widget.GarminCompactWidgetReceiver",
                ".widget.GarminWidgetReceiver",
                ".widget.GarminLargeWidgetReceiver",
            ),
            names,
        )

        val resources = widgetReceivers.map { receiver ->
            val metas = receiver.getElementsByTagName("meta-data")
            var resource: String? = null
            for (i in 0 until metas.length) {
                val meta = metas.item(i) as Element
                if (meta.getAttribute("android:name") == "android.appwidget.provider") {
                    resource = meta.getAttribute("android:resource")
                }
            }
            resource
        }.toSet()
        assertEquals(
            setOf(
                "@xml/garmin_widget_info_compact",
                "@xml/garmin_widget_info",
                "@xml/garmin_widget_info_large",
            ),
            resources,
        )

        listOf(
            "src/main/res/xml/garmin_widget_info_compact.xml",
            "src/main/res/xml/garmin_widget_info.xml",
            "src/main/res/xml/garmin_widget_info_large.xml",
        ).forEach { path ->
            assertTrue(File(path).isFile)
        }
        assertFalse(
            "unused wide provider XML must not exist",
            File("src/main/res/xml/garmin_widget_info_wide.xml").exists(),
        )
    }
}

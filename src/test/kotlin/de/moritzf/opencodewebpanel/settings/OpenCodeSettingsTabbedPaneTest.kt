package de.moritzf.opencodewebpanel.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Dimension
import javax.swing.JPanel

class OpenCodeSettingsTabbedPaneTest {

    @Test
    fun reportedHeightFollowsTheSelectedTab() {
        System.setProperty("java.awt.headless", "true")
        val short = JPanel().apply { preferredSize = Dimension(400, 300) }
        val tall = JPanel().apply { preferredSize = Dimension(400, 900) }
        val pane = OpenCodeSettingsTabbedPane().apply {
            addTab("short", short)
            addTab("tall", tall)
        }

        pane.selectedIndex = 0
        val whenShortSelected = pane.preferredSize.height
        pane.selectedIndex = 1
        val whenTallSelected = pane.preferredSize.height

        // The tab strip adds the same constant to both, so the difference is exactly the
        // difference between the two tabs — the short tab no longer inherits the long one.
        assertEquals(600, whenTallSelected - whenShortSelected)
        assertTrue("short tab must still fit its content", whenShortSelected >= 300)
    }

    private fun height(base: Int, tallest: Int, selected: Int) =
        OpenCodeSettingsTabbedPane.preferredHeightForSelectedTab(base, tallest, selected)

    @Test
    fun shortTabDropsTheHeightTheLongTabContributes() {
        // Tab strip + insets add 40px on top of the tallest tab (900), so a 500px tab must report
        // 540 — otherwise the settings dialog keeps the long tab's scrollbar on the short one.
        assertEquals(540, height(base = 940, tallest = 900, selected = 500))
    }

    @Test
    fun tallestTabKeepsItsOwnHeight() {
        assertEquals(940, height(base = 940, tallest = 900, selected = 900))
    }

    @Test
    fun equalTabsAreUnaffected() {
        assertEquals(640, height(base = 640, tallest = 600, selected = 600))
    }

    @Test
    fun neverReportsLessThanTheSelectedTabNeeds() {
        // Defensive: a base height that does not actually contain the tallest tab (stale or
        // clamped by a look and feel) must not shrink below the visible content.
        assertEquals(500, height(base = 200, tallest = 900, selected = 500))
    }

    @Test
    fun aSelectedTabTallerThanTheReportedMaximumIsNotGrown() {
        assertEquals(940, height(base = 940, tallest = 600, selected = 900))
    }
}

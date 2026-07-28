package de.moritzf.opencodewebpanel.settings

import com.intellij.ui.components.JBTabbedPane
import java.awt.Dimension

/**
 * A [JBTabbedPane] whose preferred height follows the **selected** tab instead of the tallest one.
 *
 * `JTabbedPane` reports the maximum preferred size over all tabs, so the settings dialog wraps the
 * whole page in a scroll pane as soon as *any* tab is long — a short tab then shows a scrollbar
 * and a large empty area below its content. Reporting the selected tab's height instead lets the
 * dialog drop the scrollbar whenever the visible tab actually fits.
 */
internal class OpenCodeSettingsTabbedPane : JBTabbedPane() {
    init {
        // The dialog's scroll pane is a validate root, so revalidating here re-runs its layout
        // and updates the scrollbar for the newly selected tab.
        addChangeListener {
            revalidate()
            repaint()
        }
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        val selectedHeight = selectedComponent?.preferredSize?.height ?: return base
        var tallest = 0
        for (index in 0 until tabCount) {
            val tab = getComponentAt(index) ?: continue
            tallest = maxOf(tallest, tab.preferredSize.height)
        }
        return Dimension(base.width, preferredHeightForSelectedTab(base.height, tallest, selectedHeight))
    }

    companion object {
        /**
         * Removes the height the tallest tab contributes to [baseHeight] and puts the selected
         * tab's height back, keeping whatever the tab strip and insets add. Never grows the
         * reported height, so a tab that is the tallest one is unaffected.
         */
        fun preferredHeightForSelectedTab(baseHeight: Int, tallestTabHeight: Int, selectedTabHeight: Int): Int {
            val surplus = tallestTabHeight - selectedTabHeight
            if (surplus <= 0) return baseHeight
            return (baseHeight - surplus).coerceAtLeast(selectedTabHeight)
        }
    }
}

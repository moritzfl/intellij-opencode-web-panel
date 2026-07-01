package de.moritzf.opencodewebpanel.toolWindow

import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeZoomTest {

    @Test
    fun zoomStepsInTenPercentIncrements() {
        assertEquals(110, OpenCodeZoom.zoomedIn(100))
        assertEquals(90, OpenCodeZoom.zoomedOut(100))
    }

    @Test
    fun zoomIsClampedToConfiguredBounds() {
        assertEquals(OpenCodeSettingsState.MAX_UI_ZOOM_PERCENT, OpenCodeZoom.zoomedIn(OpenCodeSettingsState.MAX_UI_ZOOM_PERCENT))
        assertEquals(OpenCodeSettingsState.MAX_UI_ZOOM_PERCENT, OpenCodeZoom.zoomedIn(OpenCodeSettingsState.MAX_UI_ZOOM_PERCENT - 5))
        assertEquals(OpenCodeSettingsState.MIN_UI_ZOOM_PERCENT, OpenCodeZoom.zoomedOut(OpenCodeSettingsState.MIN_UI_ZOOM_PERCENT))
        assertEquals(OpenCodeSettingsState.MIN_UI_ZOOM_PERCENT, OpenCodeZoom.zoomedOut(OpenCodeSettingsState.MIN_UI_ZOOM_PERCENT + 5))
    }
}

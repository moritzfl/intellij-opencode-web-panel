package de.moritzf.opencodewebpanel.settings

import com.intellij.openapi.application.ApplicationManager

/**
 * Searchable options are built by creating the settings pages headless. Their index must hold
 * labels, not live status, so pages leave status texts empty while it is built.
 */
internal fun buildingSearchIndex(): Boolean {
    val app = ApplicationManager.getApplication()
    return app.isHeadlessEnvironment && !app.isUnitTestMode
}

package de.moritzf.opencodewebpanel

import com.intellij.openapi.application.PathManager
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenCodeArchitectureTest {
    @Test
    fun lowerLayersDoNotDependOnFeaturesOrPanelGlue() {
        val root = javaClass.packageName
        // Import the production artifact directly: IntelliJ's instrumentTestCode output is not
        // covered by ArchUnit's standard Maven/Gradle test-directory exclusion.
        val productionLocation =
            requireNotNull(PathManager.getJarForClass(OpenCodeServerProtocol::class.java))
        val classes = ClassFileImporter().importUrl(productionLocation.toUri().toURL())
        assertFalse("Architecture checks must exclude test classes", classes.contain(javaClass))

        // Settings is shared infrastructure; its Apply/restart adapter also calls panel glue.
        // Check the dependency direction between the four layers documented in AGENTS.md.
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Configuration")
            .definedBy("$root.configuration..")
            .layer("Server")
            .definedBy("$root.server..")
            .layer("Browser")
            .definedBy("$root.browser..")
            .layer("Features")
            .definedBy("$root.features..")
            .layer("Panel")
            .definedBy("$root.toolWindow..")
            .whereLayer("Configuration")
            .mayNotAccessAnyLayer()
            .whereLayer("Server")
            .mayOnlyAccessLayers("Configuration")
            .whereLayer("Browser")
            .mayOnlyAccessLayers("Configuration", "Server")
            .whereLayer("Features")
            .mayOnlyAccessLayers("Configuration", "Server", "Browser")
            .check(classes)
    }
}

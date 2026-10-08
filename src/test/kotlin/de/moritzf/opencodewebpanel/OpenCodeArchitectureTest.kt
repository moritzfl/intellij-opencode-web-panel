package de.moritzf.opencodewebpanel

import com.intellij.openapi.application.PathManager
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import de.moritzf.opencodewebpanel.server.OpenCodeApiContract
import de.moritzf.opencodewebpanel.server.OpenCodeHttpTransport
import de.moritzf.opencodewebpanel.server.OpenCodePendingRequestSummary
import de.moritzf.opencodewebpanel.server.OpenCodePermissionResponse
import de.moritzf.opencodewebpanel.server.OpenCodeProcessLaunch
import de.moritzf.opencodewebpanel.server.OpenCodeProtocolResult
import de.moritzf.opencodewebpanel.server.OpenCodeRecoveryClassifier
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeSessionApi
import de.moritzf.opencodewebpanel.server.OpenCodeSessionInfo
import de.moritzf.opencodewebpanel.server.OpenCodeSessionSummary
import de.moritzf.opencodewebpanel.server.OpenCodeSnapshotFileDiff
import de.moritzf.opencodewebpanel.server.OpenCodeToolPartChange
import de.moritzf.opencodewebpanel.server.OpenCodeWireProtocol
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenCodeArchitectureTest {
    @Test
    fun everyProductionClassRespectsLayerDirection() {
        val root = javaClass.packageName
        assertFalse("Architecture checks must exclude test classes", classes.contain(javaClass))

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
            .layer("Ui")
            .definedBy("$root.ui..")
            .layer("Settings")
            .definedBy("$root.settings..")
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
            .whereLayer("Ui")
            .mayOnlyAccessLayers("Configuration", "Server")
            .whereLayer("Settings")
            .mayOnlyAccessLayers("Configuration", "Server", "Ui")
            .whereLayer("Panel")
            .mayOnlyAccessLayers("Configuration", "Server", "Browser", "Features", "Ui", "Settings")
            .ensureAllClassesAreContainedInArchitecture()
            .check(classes)
    }

    @Test
    fun productionPackagesAreAcyclic() {
        slices().matching("${javaClass.packageName}.(*)..").should().beFreeOfCycles().check(classes)
    }

    @Test
    fun protocolImplementationsAndModelsDoNotDependOnTheirFacade() {
        noClasses()
            .that()
            .belongToAnyOf(
                OpenCodeApiContract::class.java,
                OpenCodeHttpTransport::class.java,
                OpenCodeSessionApi::class.java,
                OpenCodeProcessLaunch::class.java,
                OpenCodeRecoveryClassifier::class.java,
                OpenCodeWireProtocol::class.java,
                OpenCodeProtocolResult::class.java,
                OpenCodeSessionInfo::class.java,
                OpenCodeSessionSummary::class.java,
                OpenCodeSnapshotFileDiff::class.java,
                OpenCodeToolPartChange::class.java,
                OpenCodePendingRequestSummary::class.java,
                OpenCodePermissionResponse::class.java,
            )
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(OpenCodeServerProtocol::class.java)
            .check(classes)
    }

    companion object {
        // IntelliJ's instrumentTestCode output bypasses standard test-directory exclusions.
        // Import the production artifact itself; no optional layers or unclassified classes.
        private val classes by lazy {
            val location =
                requireNotNull(PathManager.getJarForClass(OpenCodeServerProtocol::class.java))
            ClassFileImporter().importUrl(location.toUri().toURL())
        }
    }
}

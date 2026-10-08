package de.moritzf.opencodewebpanel.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import de.moritzf.opencodewebpanel.server.OpenCodeServerLifecycleState
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SbxExposure
import de.moritzf.opencodewebpanel.server.SbxLaunchSpec
import de.moritzf.opencodewebpanel.server.SbxOpenCodeServerBackend
import de.moritzf.opencodewebpanel.server.SbxSandboxRecordStore
import de.moritzf.opencodewebpanel.toolWindow.requestOpenCodeSandboxReset
import de.moritzf.opencodewebpanel.toolWindow.requestOpenCodeServerRestart
import java.nio.file.Files
import java.nio.file.Path

/** Filesystem and runtime side effects of Apply. The configurable owns form binding and dialogs. */
internal class OpenCodeProjectSettingsApplier(private val project: Project) {
    fun prepare(
        values: OpenCodeProjectSettingsValues,
        spec: SbxLaunchSpec,
        historyNote: String,
    ): OpenCodeProjectSettingsApplyPlan {
        val validWorkingDirectory =
            SbxLaunchSpec.parseYaml(spec.toYaml()) != null &&
                runCatching {
                        val root = Path.of(spec.canonicalDirectory).toRealPath()
                        val workdir = Path.of(spec.hostWorkingDirectory()).toRealPath()
                        workdir.startsWith(root) && Files.isDirectory(workdir)
                    }
                    .getOrDefault(false)
        if (!validWorkingDirectory) {
            throw ConfigurationException(
                "OpenCode working directory must be an existing folder inside the mounted repository."
            )
        }
        val settings = OpenCodeProjectSettingsState.getInstance(project)
        val oldDirectory = settings.effectiveProjectDirectory(project.basePath)
        val stored = SbxLaunchSpec.load(spec.canonicalDirectory)
        // Compare against the destination, not the project we are leaving. Missing YAML means Host
        // CLI with the XML port fallback; legacy application sandbox options never seed a spec.
        val baseline =
            stored
                ?: SbxLaunchSpec.fromSettings(
                        OpenCodeSettingsState.getInstance(),
                        spec.canonicalDirectory,
                        hostPort = settings.hostPortOrNull(),
                    )
                    .copy(useSandbox = SbxLaunchSpec.usesSandbox(spec.canonicalDirectory))
        return OpenCodeProjectSettingsApplyPlan.build(
            values = values,
            spec = stored?.let { spec.adoptStoredName(it) } ?: spec,
            baselineSpec = baseline,
            oldDirectory = oldDirectory,
            canonicalOldDirectory =
                OpenCodeServerProtocol.canonicalOpenCodeDirectory(oldDirectory) ?: oldDirectory,
            oldUsesSandbox =
                OpenCodeServerBackendRegistry.getInstance().backendFor(project)
                    is SbxOpenCodeServerBackend,
            hasVm = SbxSandboxRecordStore.getInstance().recordFor(spec.canonicalDirectory) != null,
            historyNote = historyNote,
        )
    }

    fun apply(plan: OpenCodeProjectSettingsApplyPlan, exposure: SbxExposure) {
        val registry = OpenCodeServerBackendRegistry.getInstance()
        // Capture before persisting useSandbox: the registry subsequently selects the new runtime.
        val oldBackend = registry.backendFor(project)
        if (SbxLaunchSpec.persist(plan.spec) == null) {
            throw ConfigurationException(
                "Could not save ${SbxLaunchSpec.PROJECT_SPEC_NAME}. Check the project directory permissions and IDE log."
            )
        }
        if (plan.spec.useSandbox) {
            SbxSandboxRecordStore.getInstance()
                .acknowledgeExposure(plan.spec.canonicalDirectory, exposure.fingerprint)
        }
        plan.values.saveTo(OpenCodeProjectSettingsState.getInstance(project))
        val modality = ModalityState.defaultModalityState()
        fun onUi(action: () -> Unit) {
            ApplicationManager.getApplication()
                .invokeLater(
                    { if (!project.isDisposed) action() },
                    modality,
                )
        }
        val shared =
            plan.backendChanged &&
                ProjectManager.getInstance().openProjects.any { other ->
                    other !== project &&
                        !other.isDisposed &&
                        registry.backendFor(other) === oldBackend
                }
        plan.applyRuntime(
            leavingSharedBackend = shared,
            stop = oldBackend::stopServer,
            restart = { onUi { requestOpenCodeServerRestart(project) } },
            resetSandbox = {
                onUi { requestOpenCodeSandboxReset(project, dropGuestOpenCode = false) }
            },
            applyLive = {
                val sandbox = oldBackend as? SbxOpenCodeServerBackend
                if (sandbox != null) {
                    val reload =
                        plan.preview.reloadPage &&
                            sandbox.getLifecycleState() == OpenCodeServerLifecycleState.RUNNING
                    sandbox.applyLiveSettings { error ->
                        onUi {
                            if (error != null) {
                                NotificationGroupManager.getInstance()
                                    .getNotificationGroup("OpenCode Web Panel")
                                    .createNotification(
                                        "Sandbox settings not applied",
                                        StringUtil.escapeXmlEntities(error).replace("\n", "<br>"),
                                        NotificationType.WARNING,
                                    )
                                    .notify(project)
                            } else if (reload) {
                                project.messageBus
                                    .syncPublisher(OpenCodeProjectSettingsListener.TOPIC)
                                    .serverReloadRequested()
                            }
                        }
                    }
                }
            },
        )
    }
}

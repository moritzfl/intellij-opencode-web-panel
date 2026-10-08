package de.moritzf.opencodewebpanel.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.serviceContainer.NonInjectable
import de.moritzf.opencodewebpanel.configuration.OpenCodeProjectSettingsState
import java.util.concurrent.atomic.AtomicReference

/** Project commands shared by settings and panel actions, including projects without a browser. */
@Service(Service.Level.PROJECT)
internal class OpenCodeProjectRuntime
@NonInjectable
internal constructor(
    private val project: Project,
    private val backend: () -> OpenCodeServerBackend,
) {
    constructor(
        project: Project
    ) : this(
        project,
        { OpenCodeServerBackendRegistry.getInstance().backendFor(project) },
    )

    private val panelRestart = AtomicReference<(() -> Boolean)?>(null)

    /** The stable panel owner binds once; its handler chooses the currently installed browser. */
    fun bindPanelRestart(owner: Disposable, handler: () -> Boolean) {
        panelRestart.set(handler)
        Disposer.register(owner) { panelRestart.compareAndSet(handler, null) }
    }

    fun restart() {
        if (project.isDisposed) return
        if (panelRestart.get()?.invoke() == true) return
        backend()
            .restartServer(
                project,
                OpenCodeProjectSettingsState.getInstance(project)
                    .effectiveProjectDirectory(project.basePath),
                callbackActive = { !project.isDisposed },
                onStarted = {},
                onFailed = {},
            )
    }

    fun resetSandbox(dropGuestOpenCode: Boolean = true) {
        if (project.isDisposed) return
        val sandbox = backend() as? SbxOpenCodeServerBackend ?: return
        sandbox.resetSandbox(
            project,
            callbackActive = { !project.isDisposed },
            onStarted = {},
            onFailed = {},
            dropGuestOpenCode = dropGuestOpenCode,
        )
        restart()
    }

    companion object {
        fun getInstance(project: Project): OpenCodeProjectRuntime =
            project.getService(OpenCodeProjectRuntime::class.java)
    }
}

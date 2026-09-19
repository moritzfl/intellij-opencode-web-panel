package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import de.moritzf.opencodewebpanel.server.OpenCodeServerBackendRegistry
import java.awt.Component
import java.awt.Container
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Keeps one project panel attached while hosts come and go. All methods are EDT-only. */
internal class OpenCodePanelCoordinator private constructor(
    private val project: Project?,
    initialPanelComponent: Component?,
    private val disposeInitialPanel: (() -> Unit)?,
    private val parkingContainer: Container,
) : Disposable {
    constructor(
        panelComponent: Component,
        disposePanel: () -> Unit,
        parkingContainer: Container,
    ) : this(null, panelComponent, disposePanel, parkingContainer)

    private data class Placement(
        val container: Container,
        val placeholder: Component,
        val host: OpenCodePanelHost?,
    )

    private val placements = linkedMapOf<String, Placement>()
    private var panelComponent: Component? = initialPanelComponent
    private var panel: OpenCodeWebToolWindowContent? = null
    private var activeHost: OpenCodePanelHost? = null
    private var failureComponent: Component? = null
    private var activePlacement: String? = null
    private var generation = 0L
    private var disposed = false
    private var replacementPending = false

    companion object {
        private val INSTANCE_KEY = Key.create<OpenCodePanelCoordinator>("opencode.panel.coordinator")

        fun getInstance(project: Project): OpenCodePanelCoordinator {
            project.getUserData(INSTANCE_KEY)?.let { return it }
            return synchronized(project) {
                project.getUserData(INSTANCE_KEY) ?: createForProject(project).also { coordinator ->
                    project.putUserData(INSTANCE_KEY, coordinator)
                    Disposer.register(project, coordinator)
                }
            }
        }

        private fun createForProject(project: Project): OpenCodePanelCoordinator {
            return OpenCodePanelCoordinator(project, null, null, JPanel())
        }
    }

    fun registerPlacement(id: String, container: Container, placeholder: Component) {
        registerPlacement(id, container, placeholder, null)
    }

    fun registerPlacement(
        id: String,
        container: Container,
        placeholder: Component,
        host: OpenCodePanelHost?,
    ) {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        require(id !in placements) { "Placement already registered: $id" }
        require(placements.values.none { it.container === container }) {
            "Container already registered"
        }
        placements[id] = Placement(container, placeholder, host)
        render()
    }

    fun unregisterPlacement(id: String) {
        requireEdt()
        if (disposed) return
        val wasActive = activePlacement == id
        placements.remove(id) ?: return
        if (wasActive) {
            activePlacement = placements.keys.firstOrNull()
            activeHost = activePlacement?.let { placements[it]?.host }
            generation++
        }
        render()
        if (activePlacement == null) parkPanel()
    }

    fun isPlacementRegistered(id: String): Boolean = placements.containsKey(id)

    fun panel(): OpenCodeWebToolWindowContent? = panel

    fun panelFor(host: OpenCodePanelHost, sessionId: String?): OpenCodeWebToolWindowContent? {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        panel?.let { return it }
        if (project == null) return null
        val created = createPanel(sessionId) ?: return null
        panel = created
        panelComponent = created.getContent()
        failureComponent = null
        activeHost = host
        return created
    }

    fun place(id: String) {
        requireEdt()
        val placementGeneration = beginPlacement(id)
        completePlacement(id, placementGeneration)
    }

    fun placeIfUnoccupied(id: String): Boolean {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        check(placements.containsKey(id)) { "Unknown placement: $id" }
        if (activePlacement != null) return false
        place(id)
        return true
    }

    fun beginPlacement(id: String): Long {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        check(placements.containsKey(id)) { "Unknown placement: $id" }
        return ++generation
    }

    fun completePlacement(id: String, placementGeneration: Long): Boolean {
        requireEdt()
        if (disposed || generation != placementGeneration || !placements.containsKey(id)) return false
        activePlacement = id
        activeHost = placements[id]?.host ?: activeHost
        render()
        return true
    }

    fun park() {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        generation++
        activePlacement = null
        activeHost = null
        render()
        parkPanel()
    }

    /** Recreates the shared browser, keeping the old browser until its successor is ready. */
    fun replacePanel() {
        requireEdt()
        if (disposed || replacementPending || project == null) return
        val previous = panel
        if (activeHost == null && placements.values.none { it.host != null }) return
        val liveBackendId = OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId
        val replacement = createPanel(sessionId = null) ?: return
        if (previous == null) {
            panel = replacement
            panelComponent = replacement.getContent()
            failureComponent = null
            render()
            replacement.openSession(null)
            return
        }

        replacementPending = true
        replacement.prepareBrowserForReplacement().whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater {
                replacementPending = false
                if (disposed) {
                    Disposer.dispose(replacement)
                    return@invokeLater
                }
                if (error != null) {
                    Disposer.dispose(replacement)
                    return@invokeLater
                }
                if (OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId != liveBackendId) {
                    Disposer.dispose(replacement)
                    replacePanel()
                    return@invokeLater
                }
                panel = replacement
                panelComponent = replacement.getContent()
                failureComponent = null
                render()
                Disposer.dispose(previous)
                replacement.openSession(null)
            }
        }
    }

    fun showFailure() {
        requireEdt()
        if (disposed || replacementPending) return
        panel?.let(Disposer::dispose)
        panel = null
        panelComponent = null
        failureComponent = OpenCodePanelFailureCard {
            OpenCodeRendererWatchdog.resetProcessRecreatesAfterStall()
            replacePanel()
        }.component
        render()
    }

    override fun dispose() {
        requireEdt()
        if (disposed) return
        disposed = true
        panel?.let(Disposer::dispose)
        panel = null
        disposeInitialPanel?.invoke()
    }

    private fun render() {
        placements.forEach { (id, placement) ->
            val component = if (id == activePlacement) {
                panelComponent ?: failureComponent ?: placement.placeholder
            } else {
                placement.placeholder
            }
            placement.container.removeAll()
            placement.container.add(component)
            placement.container.revalidate()
            placement.container.repaint()
        }
    }

    private fun parkPanel() {
        panelComponent?.let { component ->
            parkingContainer.removeAll()
            parkingContainer.add(component)
            parkingContainer.revalidate()
            parkingContainer.repaint()
        }
    }

    private fun createPanel(sessionId: String?): OpenCodeWebToolWindowContent? {
        return try {
            OpenCodeWebToolWindowContent(PanelCoordinatorHost(this), sessionId)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Throwable) {
            Logger.getInstance(OpenCodeWebToolWindowContent::class.java)
                .warn("Could not create the shared OpenCode panel", e)
            null
        }
    }

    private class PanelCoordinatorHost(
        private val coordinator: OpenCodePanelCoordinator,
    ) : OpenCodePanelHost {
        override val project: Project
            get() = coordinator.project ?: error("Project host is unavailable")

        override fun isDisposed(): Boolean = coordinator.isDisposed()

        override fun isActive(component: javax.swing.JComponent): Boolean = coordinator.isActive(component)

        override fun isPanelInView(component: javax.swing.JComponent): Boolean = coordinator.isPanelInView(component)

        override fun activate(component: javax.swing.JComponent, action: () -> Unit) = coordinator.activate(component, action)

        override fun replacePanel() = coordinator.replacePanel()

        override fun showFailure() = coordinator.showFailure()

        override fun updateHeading() = coordinator.updateHeading()

        override fun updateAgentStatus(state: String, icons: com.intellij.ui.BadgeIconSupplier) =
            coordinator.updateAgentStatus(state, icons)
    }

    private fun isDisposed(): Boolean = disposed || project?.isDisposed == true

    private fun isActive(component: javax.swing.JComponent): Boolean =
        activeHost?.isActive(component) == true

    private fun isPanelInView(component: javax.swing.JComponent): Boolean =
        activeHost?.isPanelInView(component) == true

    private fun activate(component: javax.swing.JComponent, action: () -> Unit) {
        activeHost?.activate(component, action) ?: action()
    }

    private fun updateHeading() {
        activeHost?.updateHeading()
    }

    private fun updateAgentStatus(state: String, icons: com.intellij.ui.BadgeIconSupplier) {
        activeHost?.updateAgentStatus(state, icons)
    }

    private fun requireEdt() {
        check(SwingUtilities.isEventDispatchThread()) {
            "OpenCodePanelCoordinator must be used on the EDT"
        }
    }
}

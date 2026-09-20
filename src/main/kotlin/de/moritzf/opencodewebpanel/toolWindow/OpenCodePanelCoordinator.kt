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
import java.util.concurrent.CompletableFuture
import javax.swing.JPanel
import javax.swing.SwingUtilities

internal interface OpenCodePanelHandle : Disposable {
    val component: Component

    fun prepareBrowserForReplacement(): CompletableFuture<Unit>

    fun openSession(sessionId: String?)

    fun onPlacementTransferred()

    fun onPlacementSelected() {}
}

/** Keeps one project panel attached while hosts come and go. All methods are EDT-only. */
internal class OpenCodePanelCoordinator private constructor(
    private val project: Project?,
    initialPanel: OpenCodePanelHandle?,
    private val parkingContainer: Container,
    private val panelFactory: ((String?) -> OpenCodePanelHandle?)?,
) : Disposable {
    constructor(
        panelComponent: Component,
        disposePanel: () -> Unit,
        parkingContainer: Container,
    ) : this(null, ComponentPanelHandle(panelComponent, disposePanel), parkingContainer, null)

    internal constructor(
        initialPanel: OpenCodePanelHandle,
        parkingContainer: Container,
        panelFactory: (String?) -> OpenCodePanelHandle?,
    ) : this(null, initialPanel, parkingContainer, panelFactory)

    private data class Placement(
        val container: Container,
        val placeholder: Component,
        val host: OpenCodePanelHost?,
    )

    private val placements = linkedMapOf<String, Placement>()
    private var panelComponent: Component? = initialPanel?.component
    private var panel: OpenCodePanelHandle? = initialPanel
    private var failureComponent: Component? = null
    private var activePlacement: String? = null
    private var generation = 0L
    private var disposed = false
    private var pendingReplacement: OpenCodePanelHandle? = null
    private var lastAgentStatus: Pair<String, com.intellij.ui.BadgeIconSupplier>? = null

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
            return OpenCodePanelCoordinator(project, null, JPanel(), null)
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
            activePlacement = null
            generation++
        }
        render()
        if (activePlacement == null) parkPanel()
    }

    fun releaseEditorPlacement(
        editorPlacementId: String,
        toolWindowPlacementId: String,
        onTransferComplete: () -> Unit = {},
    ) {
        requireEdt()
        if (disposed) return
        if (activePlacement == editorPlacementId) {
            if (placements.containsKey(toolWindowPlacementId)) {
                place(toolWindowPlacementId)
            } else {
                park()
            }
            onTransferComplete()
        }
        unregisterPlacement(editorPlacementId)
    }

    fun isPlacementRegistered(id: String): Boolean = placements.containsKey(id)

    fun isPlacementActive(id: String): Boolean = activePlacement == id

    fun notifyPlacementSelected(id: String) {
        requireEdt()
        if (!disposed && activePlacement == id) panel?.onPlacementSelected()
    }

    fun hasPanelComponent(): Boolean = panelComponent != null || failureComponent != null

    fun panel(): OpenCodeWebToolWindowContent? = panel as? OpenCodeWebToolWindowContent

    fun panelFor(sessionId: String?): OpenCodeWebToolWindowContent? {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        panel?.let { return it as? OpenCodeWebToolWindowContent }
        if (project == null && panelFactory == null) return null
        val created = createPanel(sessionId) ?: return null
        panel = created
        panelComponent = created.component
        failureComponent = null
        render()
        return created as? OpenCodeWebToolWindowContent
    }

    fun panelForActivePlacement(
        placementId: String,
        sessionId: String?,
    ): OpenCodeWebToolWindowContent? {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        check(placements.containsKey(placementId)) { "Unknown placement: $placementId" }
        if (activePlacement != placementId) return panel()
        return panelFor(sessionId)
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
        val moved = activePlacement != id
        activePlacement = id
        render()
        if (moved) {
            panel?.onPlacementTransferred()
            reapplyHostState()
        }
        return true
    }

    fun park() {
        requireEdt()
        check(!disposed) { "OpenCodePanelCoordinator is disposed" }
        generation++
        activePlacement = null
        render()
        parkPanel()
    }

    /** Recreates the shared browser, keeping the old browser until its successor is ready. */
    fun replacePanel() {
        requireEdt()
        if (disposed || pendingReplacement != null || (project == null && panelFactory == null)) return
        val previous = panel
        if (currentHost() == null && placements.values.none { it.host != null }) return
        val liveBackendId = project?.let {
            OpenCodeServerBackendRegistry.getInstance().backendFor(it).backendId
        }
        val replacement = createPanel(sessionId = null) ?: return
        if (previous == null) {
            panel = replacement
            panelComponent = replacement.component
            failureComponent = null
            render()
            replacement.openSession(null)
            return
        }

        pendingReplacement = replacement
        val readiness = runCatching { replacement.prepareBrowserForReplacement() }
            .getOrElse { CompletableFuture.failedFuture(it) }
        readiness.whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater {
                val ownedReplacement = takePendingReplacement(replacement) ?: return@invokeLater
                if (disposed) {
                    Disposer.dispose(ownedReplacement)
                    return@invokeLater
                }
                if (error != null) {
                    Disposer.dispose(ownedReplacement)
                    return@invokeLater
                }
                if (project != null &&
                    OpenCodeServerBackendRegistry.getInstance().backendFor(project).backendId != liveBackendId
                ) {
                    Disposer.dispose(ownedReplacement)
                    replacePanel()
                    return@invokeLater
                }
                panel = ownedReplacement
                panelComponent = ownedReplacement.component
                failureComponent = null
                // Render only now: activePlacement may have changed while JCEF created the successor.
                render()
                ownedReplacement.onPlacementTransferred()
                Disposer.dispose(previous)
                ownedReplacement.openSession(null)
            }
        }
    }

    fun showFailure() {
        requireEdt()
        if (disposed || pendingReplacement != null) return
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
        pendingReplacement?.let { replacement ->
            pendingReplacement = null
            Disposer.dispose(replacement)
        }
        panel?.let(Disposer::dispose)
        panel = null
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

    private fun createPanel(sessionId: String?): OpenCodePanelHandle? {
        if (panelFactory != null) return panelFactory(sessionId)
        if (project == null) return null
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

    private fun takePendingReplacement(replacement: OpenCodePanelHandle): OpenCodePanelHandle? {
        if (pendingReplacement !== replacement) return null
        pendingReplacement = null
        return replacement
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

    private fun currentHost(): OpenCodePanelHost? =
        activePlacement?.let { placements[it]?.host }

    internal fun isActive(component: javax.swing.JComponent): Boolean =
        currentHost()?.isActive(component) == true

    internal fun isPanelInView(component: javax.swing.JComponent): Boolean =
        currentHost()?.isPanelInView(component) == true

    internal fun activate(component: javax.swing.JComponent, action: () -> Unit) {
        currentHost()?.activate(component, action) ?: action()
    }

    internal fun updateHeading() {
        currentHost()?.updateHeading()
    }

    internal fun updateAgentStatus(state: String, icons: com.intellij.ui.BadgeIconSupplier) {
        lastAgentStatus = state to icons
        currentHost()?.updateAgentStatus(state, icons)
    }

    private fun reapplyHostState() {
        currentHost()?.updateHeading()
        lastAgentStatus?.let { (state, icons) ->
            currentHost()?.updateAgentStatus(state, icons)
        }
    }

    private fun requireEdt() {
        check(SwingUtilities.isEventDispatchThread()) {
            "OpenCodePanelCoordinator must be used on the EDT"
        }
    }

    private class ComponentPanelHandle(
        override val component: Component,
        private val disposeAction: () -> Unit,
    ) : OpenCodePanelHandle {
        override fun prepareBrowserForReplacement(): CompletableFuture<Unit> =
            CompletableFuture.failedFuture(UnsupportedOperationException())

        override fun openSession(sessionId: String?) = Unit

        override fun onPlacementTransferred() = Unit

        override fun dispose() = disposeAction()
    }
}

package de.moritzf.opencodewebpanel.toolWindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.ApplicationRule
import de.moritzf.opencodewebpanel.features.OpenCodeChatInputService
import org.junit.Assert.*
import org.junit.ClassRule
import org.junit.Test
import java.util.concurrent.CompletableFuture
import javax.swing.JPanel

class OpenCodePanelControllerTest {
    @Test
    fun chatStaysWithPredecessorUntilSuccessorIsReady() {
        val first = TestPanel()
        val next = TestPanel()
        val candidates = ArrayDeque(listOf(first, next))
        lateinit var controller: OpenCodePanelController
        val project = ProjectManager.getInstance().defaultProject
        val chat = OpenCodeChatInputService.getInstance(project)
        onEdt {
            controller = OpenCodePanelController(project) { candidates.removeFirst() }
            controller.ensurePanel()
            controller.replacePanel()
            assertTrue(chat.send(listOf("draft")))
            assertEquals(1, first.deliveries.size)
            assertTrue(next.deliveries.isEmpty())
            assertEquals(0, first.disposals)
            assertSame(first.component, controller.component.getComponent(0))
        }
        next.ready.complete(Unit)
        onEdt {
            assertTrue(controller.isCurrent(next))
            assertEquals(1, first.disposals)
            assertEquals(1, next.loads)
            assertFalse(chat.acknowledge(first.deliveries.single().attemptID, true))
            assertTrue(chat.dispatchPending())
            assertEquals(first.deliveries.single().batch, next.deliveries.single().batch)
            assertTrue(chat.acknowledge(next.deliveries.single().attemptID, true))
            Disposer.dispose(controller)
            assertEquals(1, next.disposals)
            assertFalse(chat.activatePanel())
        }
    }

    @Test
    fun failedReplacementRetainsPanelAndAllowsRetry() {
        val first = TestPanel()
        val failed = TestPanel()
        val retry = TestPanel()
        val candidates = ArrayDeque(listOf(first, failed, retry))
        lateinit var controller: OpenCodePanelController
        onEdt {
            controller = OpenCodePanelController(ProjectManager.getInstance().defaultProject) { candidates.removeFirst() }
            controller.ensurePanel()
            controller.replacePanel()
        }
        failed.ready.completeExceptionally(IllegalStateException("renderer creation failed"))
        onEdt {
            assertTrue(controller.isCurrent(first))
            assertEquals(0, first.disposals)
            assertEquals(1, failed.disposals)
            controller.replacePanel()
        }
        retry.ready.complete(Unit)
        onEdt {
            assertTrue(controller.isCurrent(retry))
            assertSame(retry.component, controller.component.getComponent(0))
            Disposer.dispose(controller)
        }
    }

    @Test
    fun disposalReleasesPendingBrowserOnceDespiteLateAcknowledgement() {
        val first = TestPanel()
        val next = TestPanel()
        val candidates = ArrayDeque(listOf(first, next))
        onEdt {
            val controller = OpenCodePanelController(ProjectManager.getInstance().defaultProject) { candidates.removeFirst() }
            controller.ensurePanel()
            controller.replacePanel()
            Disposer.dispose(controller)
        }
        next.ready.complete(Unit)
        onEdt {
            assertEquals(1, first.disposals)
            assertEquals(1, next.disposals)
            assertEquals(0, next.loads)
        }
    }

    @Test
    fun failureCardCanRecoverWithoutReplacingItsHostContainer() {
        val first = TestPanel()
        val next = TestPanel()
        val candidates = ArrayDeque(listOf(first, next))
        lateinit var controller: OpenCodePanelController
        onEdt {
            controller = OpenCodePanelController(ProjectManager.getInstance().defaultProject) { candidates.removeFirst() }
            controller.ensurePanel()
            controller.showFailure()
            assertEquals(1, first.disposals)
            assertFalse(controller.component.getComponent(0) === first.component)
            controller.replacePanel()
        }
        next.ready.complete(Unit)
        onEdt {
            assertSame(next.component, controller.component.getComponent(0))
            Disposer.dispose(controller)
        }
    }

    private class TestPanel : OpenCodePanel {
        override val component = JPanel()
        override val preferredFocus = component
        val ready = CompletableFuture<Unit>()
        val deliveries = mutableListOf<OpenCodeChatInputService.Delivery>()
        var loads = 0
        var disposals = 0
        override fun prepareBrowserForReplacement() = ready
        override fun checkAndLoadContent() { loads++ }
        override fun dispatchChatBatch(delivery: OpenCodeChatInputService.Delivery): Boolean {
            deliveries += delivery
            return true
        }
        override fun onHostChanged() = Unit
        override fun dispose() { disposals++ }
    }

    private fun onEdt(action: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(action)

    companion object {
        @ClassRule @JvmField val application = ApplicationRule()
    }
}

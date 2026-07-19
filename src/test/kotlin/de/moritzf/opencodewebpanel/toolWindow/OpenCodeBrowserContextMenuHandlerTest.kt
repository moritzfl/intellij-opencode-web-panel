package de.moritzf.opencodewebpanel.toolWindow

import org.cef.callback.CefMenuModel
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeBrowserContextMenuHandlerTest {

    @Test
    fun toleratesMissingMenuModel() {
        // JBCef dispatches to all registered handlers; a null model must never throw.
        OpenCodeBrowserContextMenuHandler().onBeforeContextMenu(null, null, null, null)
    }

    @Test
    fun removesOnlyTheViewSourceEntry() {
        val removed = mutableListOf<Int>()
        val model = java.lang.reflect.Proxy.newProxyInstance(
            CefMenuModel::class.java.classLoader,
            arrayOf(CefMenuModel::class.java),
        ) { _, method, args ->
            if (method.name == "remove") removed.add(args[0] as Int)
            when (method.returnType) {
                java.lang.Boolean.TYPE -> true
                Integer.TYPE -> 0
                else -> null
            }
        } as CefMenuModel

        OpenCodeBrowserContextMenuHandler().onBeforeContextMenu(null, null, null, model)

        assertEquals(listOf(CefMenuModel.MenuId.MENU_ID_VIEW_SOURCE), removed)
    }
}

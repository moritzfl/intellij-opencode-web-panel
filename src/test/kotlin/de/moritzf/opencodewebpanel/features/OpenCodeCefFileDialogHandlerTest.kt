package de.moritzf.opencodewebpanel.features

import org.cef.handler.CefDialogHandler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeCefFileDialogHandlerTest {
    @Test
    fun shouldHandleOpenDialogsAndLeaveSaveToCef() {
        assertTrue(OpenCodeCefFileDialogHandler.shouldHandle(CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN))
        assertTrue(OpenCodeCefFileDialogHandler.shouldHandle(CefDialogHandler.FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE))
        assertFalse(OpenCodeCefFileDialogHandler.shouldHandle(CefDialogHandler.FileDialogMode.FILE_DIALOG_SAVE))
        assertFalse(OpenCodeCefFileDialogHandler.shouldHandle(null))
    }
}

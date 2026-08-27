package de.moritzf.opencodewebpanel.browser

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Alarm

class OpenCodeBrowserScriptSchedulerTest : BasePlatformTestCase() {

    fun testScheduleActionOnDisposedAlarmDoesNotError() {
        val parent = Disposer.newDisposable()
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
        Disposer.dispose(parent)
        assertTrue(alarm.isDisposed)

        val scheduler = OpenCodeBrowserScriptScheduler(project, alarm) { _, _ -> }
        scheduler.scheduleAction { fail("disposed alarm must not run actions") }
        scheduler.scheduleAt(1) { fail("disposed alarm must not run actions") }
        scheduler.schedule("ignored", "http://127.0.0.1/")
    }
}

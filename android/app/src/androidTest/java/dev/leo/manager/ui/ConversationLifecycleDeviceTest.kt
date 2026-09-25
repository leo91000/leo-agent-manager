package dev.leo.manager.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationLifecycleDeviceTest : ConversationLifecycleCases() {
    override fun captureSwipe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "conversation-lifecycle").apply { mkdirs() }
        File(directory, "swipe-fil.png").outputStream().use {
            check(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}

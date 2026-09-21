package dev.leo.manager.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryFollowDeviceTest : HistoryFollowCases() {
    // Capture the display through Android rather than drawing Compose from the test thread.
    override fun capturePinnedImage(): android.graphics.Bitmap =
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())

    // Reuse the active Compose rule so CI's existing device selection also checks paging.
    @Test
    fun olderPageDoesNotCascadeWhileTheHeaderIsVisible() {
        object : HistoryPagingCases(compose) {}
            .visibleHeaderWithMorePagesDoesNotLoadTheEntireHistory()
    }

    @Test
    fun olderPageKeepsTheTextUnderAnActiveFinger() {
        object : HistoryPagingCases(compose) {}
            .pageArrivingDuringAHeldDragPreservesTextAndDoesNotCascade()
    }

    // The existing CI device entry point also exercises the complete native app.
    @Test
    fun compactConversationAndKeyboard() =
        ParityDeviceCases(compose).compactChatEvidenceAndSwitcher()

    @Test
    fun taskConversationAndManagement() = ParityDeviceCases(compose).taskConversationAndManagement()

    @Test fun adaptiveTaskLayout() = ParityDeviceCases(compose).adaptiveTaskLayout()
}

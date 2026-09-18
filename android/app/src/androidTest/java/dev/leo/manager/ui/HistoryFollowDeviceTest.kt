package dev.leo.manager.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryFollowDeviceTest : HistoryFollowCases() {
    // Reuse the active Compose rule so CI's existing device selection also checks paging.
    @Test fun olderPageDoesNotCascadeWhileTheHeaderIsVisible() {
        object : HistoryPagingCases(compose) {}.visibleHeaderWithMorePagesDoesNotLoadTheEntireHistory()
    }

    @Test fun olderPageKeepsTheTextUnderAnActiveFinger() {
        object : HistoryPagingCases(compose) {}.pageArrivingDuringAHeldDragPreservesTextAndDoesNotCascade()
    }
}

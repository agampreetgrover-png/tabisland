package com.tabisland

import android.app.Notification.CallStyle
import android.app.Notification.GROUP_ALERT_ALL
import android.app.Notification.GROUP_ALERT_CHILDREN
import android.app.Notification.GROUP_ALERT_SUMMARY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaListenerTest {
    @Test fun onlyIncomingCallsRing() {
        assertTrue(isRinging(CallStyle.CALL_TYPE_INCOMING, fullScreen = false))
        assertFalse(isRinging(CallStyle.CALL_TYPE_ONGOING, fullScreen = true)) // dialing with CallStyle
        assertFalse(isRinging(CallStyle.CALL_TYPE_SCREENING, fullScreen = true))
        assertFalse(isRinging(CallStyle.CALL_TYPE_UNKNOWN, fullScreen = false)) // dialing without CallStyle
        assertTrue(isRinging(CallStyle.CALL_TYPE_UNKNOWN, fullScreen = true))
    }

    @Test fun groupMutingMatchesTheSystem() {
        assertTrue(groupMuted("g", summary = true, GROUP_ALERT_CHILDREN))
        assertFalse(groupMuted("g", summary = true, GROUP_ALERT_ALL)) // Gmail's summary pops
        assertFalse(groupMuted("g", summary = true, GROUP_ALERT_SUMMARY))
        assertTrue(groupMuted("g", summary = false, GROUP_ALERT_SUMMARY)) // Gmail's child stays quiet
        assertFalse(groupMuted("g", summary = false, GROUP_ALERT_ALL))
        assertFalse(groupMuted("g", summary = false, GROUP_ALERT_CHILDREN))
        assertFalse(groupMuted(null, summary = true, GROUP_ALERT_CHILDREN)) // no group: never muted
        assertFalse(groupMuted(null, summary = false, GROUP_ALERT_SUMMARY))
        assertTrue(groupMuted("g", summary = true, GROUP_ALERT_ALL, loudChild = true)) // child and summary both ALL: one card
    }

    @Test fun deliveriesShowMinutesNotPercent() {
        assertEquals("12 min", eta("Your order is on the way", "Arriving in 12 mins"))
        assertEquals("10–15 min", eta("Arriving in 10 - 15 minutes", ""))
        assertEquals("1 hr", eta("ETA 1 hr", ""))
        assertEquals("7:45 pm", eta("Arriving by 7:45 pm", ""))
        assertEquals("5 min", eta("Arriving by 7:45 pm", "5 min away")) // minutes beat a clock time
        assertNull(eta("Downloading", "photo_2024.jpg")) // a download keeps its percent
        assertNull(eta("Administrator", "")) // "min" inside a word isn't a time
    }
}

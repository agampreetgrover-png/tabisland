package com.tabisland

import android.app.Notification.CallStyle
import org.junit.Assert.assertFalse
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
}

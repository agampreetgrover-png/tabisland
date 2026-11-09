package com.tabisland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IslandTest {
    private val now = 1_000_000L
    private val all: (String) -> Boolean = { true }
    private fun flash(until: Long = now + 3000) = Flash("notif", "Mom", "hi", until = until)
    private val call = Call("c", "Mom", "", null, emptyList(), null, "dialer", null)
    private val timer = Timer("t", "Timer", null, 0L, true, "clock", null)
    private fun media(playing: Boolean, lastPlayed: Long = now) =
        Media("Song", "Artist", null, playing, lastPlayed, 0, 0, 0, 1f, "spotify", null)

    @Test fun callBeatsEverything() = assertSame(call, pick(call, flash(), timer, media(true), now, all))

    @Test fun flashBeatsTimerUntilItExpires() {
        val f = flash()
        assertSame(f, pick(null, f, timer, null, now, all))
        assertSame(timer, pick(null, flash(until = now), timer, null, now, all))
    }

    @Test fun disabledFeatureFallsThrough() {
        val m = media(true)
        assertSame(m, pick(call, flash(), null, m, now) { it == "media" })
    }

    @Test fun pausedMediaStaysForFiveMinutes() {
        val m = media(false, now - PAUSED_KEEP_MS + 1)
        assertSame(m, pick(null, null, null, m, now, all))
        assertEquals(now + 1, expiry(m))
        assertNull(pick(null, null, null, media(false, now - PAUSED_KEEP_MS), now, all))
    }

    @Test fun idleWhenNothing() = assertNull(pick(null, null, null, null, now, all))
}

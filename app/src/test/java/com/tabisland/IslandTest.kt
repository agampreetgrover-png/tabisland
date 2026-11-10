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
    private val nav = Ongoing("n", "nav", "200 m", "", null, WHITE, null, 3, "maps", null)
    private val download = Ongoing("d", "progress", "", "40%", null, WHITE, 40, 1, "chrome", null)
    private fun media(playing: Boolean, lastPlayed: Long = now) =
        Media("Song", "Artist", null, playing, lastPlayed, 0, 0, 0, 1f, "spotify", null)

    @Test fun callBeatsEverything() = assertSame(call, pick(call, flash(), timer, listOf(nav), media(true), now, all))

    @Test fun flashBeatsAnsweredCall() {
        val answered = Call("c", "Mom", "", null, emptyList(), 0L, "dialer", null)
        val f = flash()
        assertSame(f, pick(answered, f, timer, emptyList(), null, now, all))
        assertSame(answered, pick(answered, flash(until = now), timer, emptyList(), null, now, all))
    }

    @Test fun flashBeatsTimerUntilItExpires() {
        val f = flash()
        assertSame(f, pick(null, f, timer, emptyList(), null, now, all))
        assertSame(timer, pick(null, flash(until = now), timer, emptyList(), null, now, all))
    }

    @Test fun disabledFeatureFallsThrough() {
        val m = media(true)
        assertSame(m, pick(call, flash(), null, listOf(nav), m, now) { it == "media" })
    }

    @Test fun pausedMediaStaysForFiveMinutes() {
        val m = media(false, now - PAUSED_KEEP_MS + 1)
        assertSame(m, pick(null, null, null, emptyList(), m, now, all))
        assertEquals(now + 1, expiry(m))
        assertNull(pick(null, null, null, emptyList(), media(false, now - PAUSED_KEEP_MS), now, all))
    }

    @Test fun navigationShowsOverMusicButDownloadsDoNot() {
        val m = media(true)
        assertSame(nav, pick(null, null, null, listOf(download, nav), m, now, all))
        assertSame(m, pick(null, null, null, listOf(download), m, now, all))
        assertSame(download, pick(null, null, null, listOf(download), null, now, all))
    }

    @Test fun idleWhenNothing() = assertNull(pick(null, null, null, emptyList(), null, now, all))
}

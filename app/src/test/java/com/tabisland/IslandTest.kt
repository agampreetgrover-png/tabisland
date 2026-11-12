package com.tabisland

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IslandTest {
    @Before fun reset() { Island.call = null; Island.onChange = null; repeat(10) { Island.dismissFlash() } } // Island is a singleton
    private val now = 1_000_000L
    private val all: (String) -> Boolean = { true }
    private fun flash(until: Long = now + 3000) = Flash("notif", "Mom", "hi", until = until)
    private val call = Call("c", "Mom", "", null, emptyList(), null, "dialer", null)
    private val timer = Timer("t", "Timer", null, 0L, true, "clock", null)
    private val nav = Ongoing("n", "nav", "200 m", "", null, WHITE, null, 3, "maps", null)
    private val download = Ongoing("d", "progress", "", "40%", null, WHITE, 40, 1, "chrome", null)
    private fun media(playing: Boolean, lastPlayed: Long = now) =
        Media("Song", "Artist", null, playing, lastPlayed, 0, 0, 0, 1f, "spotify", null)

    @Test fun dominantIgnoresWhiteAndGrey() {
        val red = 0xFFFF0000.toInt()
        // a red glyph on a white tile with a transparent margin: the red wins
        assertEquals(red, dominant(intArrayOf(red, WHITE, WHITE, 0x00000000, 0xFF808080.toInt())))
        assertNull(dominant(intArrayOf(WHITE, 0xFF808080.toInt(), 0xFF000000.toInt(), 0)))
        assertNull(dominant(intArrayOf(0))) // Notification.COLOR_DEFAULT
    }

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

    @Test fun dialingCallIsOnlyAPill() {
        val dialing = Call("c", "Mom", "", null, emptyList(), null, "dialer", null, ringing = false)
        val f = flash()
        assertSame(f, pick(dialing, f, timer, emptyList(), null, now, all))
        assertSame(dialing, pick(dialing, flash(until = now), timer, emptyList(), null, now, all))
        assertSame(call, pick(call, f, timer, emptyList(), null, now, all))
    }

    private fun queued(id: String, until: Long = System.currentTimeMillis() + 60_000, text: String = "hi") =
        Flash("notif", "Mom", text, id = id, until = until)

    @Test fun liveFlashQueuesTheNextWithItsFullTime() {
        val a = queued("a")
        Island.post(a)
        Island.post(queued("b", until = System.currentTimeMillis() + 4000))
        assertSame(a, Island.flash)
        Island.dismissFlash()
        assertEquals("b", Island.flash?.id)
        assertTrue(Island.flash!!.until - System.currentTimeMillis() in 3000L..4000L)
        Island.dismissFlash()
        assertNull(Island.flash)
    }

    @Test fun removeFlashDropsCurrentAndQueued() {
        Island.post(queued("a")); Island.post(queued("b")); Island.post(queued("c"))
        Island.removeFlash("b")
        Island.removeFlash("a")
        assertEquals("c", Island.flash?.id)
        Island.dismissFlash()
        assertNull(Island.flash)
    }

    @Test fun updateReplacesItsOwnFlash() {
        Island.post(queued("a", text = "1")); Island.post(queued("a", text = "2"))
        assertEquals("2", Island.flash?.text)
        Island.dismissFlash()
        assertNull(Island.flash)
    }

    @Test fun topAdvancesPastAnExpiredFlash() {
        val t = System.currentTimeMillis()
        Island.post(queued("a", until = t + 60_000)); Island.post(queued("b", until = t + 60_000))
        assertEquals("b", (Island.top(t + 60_001, all) as Flash).id)
    }

    @Test fun switchedOffFlashDoesNotHoldTheQueue() {
        Island.post(Flash("silent", "Silent", "On", until = System.currentTimeMillis() + 60_000)); Island.post(queued("a"))
        assertEquals("a", (Island.top(System.currentTimeMillis(), { it != "silent" }) as Flash).id)
    }

    @Test fun queuingBehindALiveFlashStillRenders() {
        var renders = 0
        Island.post(Flash("silent", "Silent", "On", until = System.currentTimeMillis() + 60_000))
        Island.onChange = { renders++ }
        Island.post(queued("a"))
        Island.onChange = null
        assertEquals(1, renders) // the render that lets top() skip a flash whose switch is off
    }

    @Test fun ringingCallParksMessagesUntilItEnds() {
        val t = System.currentTimeMillis()
        Island.call = call
        Island.post(queued("a", until = t + 4000))
        assertSame(call, Island.top(t, all))
        assertNull(Island.flash)
        assertSame(call, Island.top(t + 10_000, all)) // long past its 4 s: still waiting, unseen
        Island.call = null
        val f = Island.top(t + 10_000, all) as Flash
        assertEquals("a", f.id)
        assertTrue(f.until - (t + 10_000) in 3000L..4000L)
    }

    @Test fun dismissingAStaleCardLeavesTheNextOne() {
        val a = queued("a")
        Island.post(a); Island.post(queued("b"))
        Island.dismissFlash()
        Island.dismissFlash(a) // a tap on a, which was still on screen while renders were held
        assertEquals("b", Island.flash?.id)
        Island.dismissFlash(Island.flash!!.copy(text = "update"))
        assertNull(Island.flash)
    }
}

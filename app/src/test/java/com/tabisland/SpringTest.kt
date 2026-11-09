package com.tabisland

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class SpringTest {
    private fun run(response: Float, damping: Float, seconds: Float): Pair<Spring, Float> {
        val s = Spring(0f).apply { target = 100f }
        var peak = 0f
        var t = 0f
        while (t < seconds) { s.step(0.004f, response, damping); peak = max(peak, s.value); t += 0.004f }
        return s to peak
    }

    @Test fun growOvershootsSlightlyThenSettles() {
        val (s, peak) = run(0.42f, 0.8f, 1.5f)
        assertTrue("peak=$peak", peak in 100.5f..104f)
        assertTrue(s.settled(0.5f))
    }

    @Test fun criticallyDampedShrinkNeverOvershoots() {
        val (s, peak) = run(0.34f, 1f, 1.5f)
        assertTrue("peak=$peak", peak <= 100.5f)
        assertTrue(s.settled(0.5f))
    }

    @Test fun retargetKeepsVelocity() {
        val s = Spring(0f).apply { target = 100f }
        repeat(25) { s.step(0.004f, 0.42f, 0.66f) }
        val v = s.velocity
        s.target = 0f
        assertTrue(v > 0f && s.velocity == v)
    }
}

package com.tabisland

import kotlin.math.PI
import kotlin.math.abs

/**
 * Spring in Apple's terms: [response] ≈ seconds per oscillation, damping ratio (1 = no overshoot, <1 = bounce).
 * Retargeting keeps the current value and velocity, so interrupted motion never jumps.
 */
class Spring(var value: Float) {
    var velocity = 0f
    var target = value

    fun step(dt: Float, response: Float, damping: Float) {
        val w = 2f * PI.toFloat() / response
        velocity += (-w * w * (value - target) - 2f * damping * w * velocity) * dt
        value += velocity * dt
    }

    fun settled(eps: Float) = abs(value - target) < eps && abs(velocity) < eps * 20

    fun snap() { value = target; velocity = 0f }
}

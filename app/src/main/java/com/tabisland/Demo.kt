package com.tabisland

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * "Try each style": fills the island with sample data for a few seconds so every look can be checked on demand,
 * without waiting for a real call, timer or navigation. Demo items use their own keys and never touch real ones.
 */
object Demo {
    /** (style, button label) for the settings screen. */
    val styles = listOf(
        "call" to "Incoming call",
        "incall" to "Call in progress",
        "notif" to "Message",
        "timer" to "Timer",
        "nav" to "Navigation",
        "download" to "Download",
        "torch" to "Flashlight",
        "charge" to "Charging",
        "battery" to "Low battery",
        "silent" to "Silent mode",
        "unlock" to "Unlock",
    )

    private const val SHOW_MS = 6000L
    private const val CALL = "demo-call"
    private const val TIMER = "demo-timer"
    private const val NAV = "demo-nav"
    private const val DOWNLOAD = "demo-download"
    private const val TORCH = "demo-torch"
    private const val FLASH = "demo-flash"
    private val handler = Handler(Looper.getMainLooper())

    fun show(ctx: Context, style: String) {
        clear()
        val now = System.currentTimeMillis()
        val appIcon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        when (style) {
            // the demo call's buttons do nothing: there is no real call behind them
            "call" -> Island.call = Call(CALL, "Mom", "Incoming call", appIcon, listOf(action("Decline"), action("Answer")), null, null, null)
            "incall" -> Island.call = Call(CALL, "Mom", "", appIcon, listOf(action("Hang up")), SystemClock.elapsedRealtime() - 42_000, null, null)
            "notif" -> Island.flash = Flash("notif", "Mom", "Are you coming home for dinner tonight? I made your favourite.",
                appIcon, pkg = ctx.packageName, id = FLASH, until = now + SHOW_MS)
            "timer" -> Island.timer = Timer(TIMER, "Timer", ctx.tinted(R.drawable.ic_timer, ORANGE),
                SystemClock.elapsedRealtime() + 5 * 60_000, true, null, null)
            "nav" -> Island.putOngoing(Ongoing(NAV, "nav", "200 m", "", ctx.tinted(R.drawable.ic_turn_right, WHITE), WHITE, null, 3, null, null))
            "download" -> download(appIcon, 0)
            "torch" -> Island.putOngoing(Ongoing(TORCH, "torch", "Flashlight", "", ctx.tinted(R.drawable.ic_flashlight, YELLOW), YELLOW, null, 2, null, null))
            "charge" -> flash(ctx, R.drawable.ic_bolt, "Charging", "80%", GREEN, 80)
            "battery" -> flash(ctx, R.drawable.ic_battery_alert, "Low Battery", "20%", RED, 20)
            "silent" -> flash(ctx, R.drawable.ic_bell_off, "Silent", "On", RED, null)
            "unlock" -> Island.flash = Flash("preview", "", "", icon = UnlockDrawable(WHITE), id = FLASH, until = now + 1800)
        }
        handler.postDelayed({ clear() }, SHOW_MS)
    }

    /** Removes whatever demo is showing; real calls, timers and live activities are left alone. */
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        if (Island.call?.key == CALL) Island.call = null
        if (Island.timer?.key == TIMER) Island.timer = null
        if (Island.flash?.id == FLASH) Island.flash = null
        Island.removeOngoing(NAV)
        Island.removeOngoing(DOWNLOAD)
        Island.removeOngoing(TORCH)
    }

    /** Counts 0→100% over 5 s so the ring can be seen filling. Rank 2 so it shows even while music plays. */
    private fun download(icon: Drawable, pct: Int) {
        Island.putOngoing(Ongoing(DOWNLOAD, "progress", "", "$pct%", icon, BLUE, pct, 2, null, null))
        if (pct < 100) handler.postDelayed({ download(icon, pct + 5) }, 250)
    }

    // "preview" is always on, so system-popup demos show even if that popup's own switch is off
    private fun flash(ctx: Context, icon: Int, title: String, text: String, accent: Int, level: Int?) {
        Island.flash = Flash("preview", title, text, icon = ctx.tinted(icon, accent), accent = accent, level = level,
            id = FLASH, until = System.currentTimeMillis() + 3000)
    }

    private fun action(title: String) = Notification.Action.Builder(null as Icon?, title, null).build()
}

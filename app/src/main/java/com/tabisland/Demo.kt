package com.tabisland

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
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
        "call-photo" to "Call: photo",
        "call-letter" to "Call: name only",
        "call-unknown" to "Call: unknown",
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
        Island.demo = true // the demo shows even when its own switch is off
        val now = System.currentTimeMillis()
        val appIcon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        when (style) {
            // the three avatars a real call can bring: a contact photo, the dialer's letter tile, the unknown-caller silhouette
            "call-photo" -> call("Ananya", ctx.getDrawable(R.drawable.demo_caller)!!) // stock photo (Unsplash license)
            "call-letter" -> call("Rahul", avatar(ctx) { c, s ->
                c.drawColor(0xFF00796B.toInt())
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = WHITE; textSize = s * 0.5f; textAlign = Paint.Align.CENTER; typeface = Typeface.create("google-sans", Typeface.NORMAL)
                }
                c.drawText("R", s / 2, s / 2 - (p.descent() + p.ascent()) / 2, p)
            })
            "call-unknown" -> call("+91 98765 43210", avatar(ctx) { c, s ->
                c.drawColor(0xFF5F6368.toInt())
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDADCE0.toInt() }
                c.drawCircle(s / 2, s * 0.38f, s * 0.17f, p) // head
                c.drawCircle(s / 2, s * 1.0f, s * 0.36f, p)  // shoulders, cut off by the tile's edge
            })
            // after clear() only a real call/timer can be here; never overwrite it, clear() couldn't give it back
            "incall" -> if (Island.call == null) Island.call = Call(CALL, "Mom", "", appIcon, listOf(action("Hang up")), SystemClock.elapsedRealtime() - 42_000, null, null)
            "notif" -> Island.post(Flash("notif", "Mom", "Are you coming home for dinner tonight? I made your favourite.",
                appIcon, pkg = ctx.packageName, id = FLASH, until = now + SHOW_MS))
            "timer" -> if (Island.timer == null) Island.timer = Timer(TIMER, "Timer", ctx.tinted(R.drawable.ic_timer, ORANGE),
                SystemClock.elapsedRealtime() + 5 * 60_000, true, null, null)
            "nav" -> Island.putOngoing(Ongoing(NAV, "nav", "200 m", "", ctx.tinted(R.drawable.ic_turn_right, WHITE), WHITE, null, 3, null, null))
            "download" -> download(appIcon, 0)
            "torch" -> Island.putOngoing(Ongoing(TORCH, "torch", "Flashlight", "", ctx.tinted(R.drawable.ic_flashlight, YELLOW), YELLOW, null, 2, null, null))
            "charge" -> flash(ctx, R.drawable.ic_bolt, "Charging", "80%", GREEN, 80)
            "battery" -> flash(ctx, R.drawable.ic_battery_alert, "Low Battery", "20%", RED, 20)
            "silent" -> flash(ctx, R.drawable.ic_bell_off, "Silent", "On", RED, null)
            "unlock" -> Island.post(Flash("preview", "", "", icon = UnlockDrawable(WHITE), id = FLASH, until = now + 1800))
        }
        handler.postDelayed({ clear() }, SHOW_MS)
    }

    /** Removes whatever demo is showing; real calls, timers and live activities are left alone. */
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        if (Island.call?.key == CALL) Island.call = null
        if (Island.timer?.key == TIMER) Island.timer = null
        Island.removeFlash(FLASH)
        Island.removeOngoing(NAV)
        Island.removeOngoing(DOWNLOAD)
        Island.removeOngoing(TORCH)
        Island.demo = false
    }

    /** Counts 0→100% over 5 s so the ring can be seen filling. Rank 2 so it shows even while music plays. */
    private fun download(icon: Drawable, pct: Int) {
        Island.putOngoing(Ongoing(DOWNLOAD, "progress", "", "$pct%", icon, BLUE, pct, 2, null, null))
        if (pct < 100) handler.postDelayed({ download(icon, pct + 5) }, 250)
    }

    private fun flash(ctx: Context, icon: Int, title: String, text: String, accent: Int, level: Int?) {
        Island.post(Flash("preview", title, text, icon = ctx.tinted(icon, accent), accent = accent, level = level,
            id = FLASH, until = System.currentTimeMillis() + 3000))
    }

    // since = null: an incoming, ringing call. Its buttons do nothing: there is no real call behind them
    private fun call(name: String, icon: Drawable) {
        if (Island.call == null) Island.call = Call(CALL, name, "Incoming call", icon, listOf(action("Decline"), action("Answer")), null, null, null)
    }

    private fun avatar(ctx: Context, draw: (Canvas, Float) -> Unit): Drawable {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), 160f)
        return BitmapDrawable(ctx.resources, b)
    }

    private fun action(title: String) = Notification.Action.Builder(null as Icon?, title, null).build()
}

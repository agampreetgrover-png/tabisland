package com.tabisland

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioManager
import android.media.session.MediaController
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import android.view.WindowManager.LayoutParams as WLP

private const val TAG = "TabIsland"
private const val BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" // hidden API; Pixel's Bluetooth stack sends it
private const val BT_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
private const val BASE_FLAGS = WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS

/**
 * Accessibility service only because TYPE_ACCESSIBILITY_OVERLAY is the one window type that draws above the
 * status bar without root. It reads no screen content. Owns the island window, system-event popups and gestures.
 */
class IslandService : AccessibilityService(), IslandView.Host, SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var island: IslandView
    private val handler = Handler(Looper.getMainLooper())
    private val renderLater = Runnable { render() }
    private var added = false
    private var shown: Live? = null
    private var expanded = false
    private var barsVisible = true
    private var battery = -1
    private var earbudsAt = 0L

    private val lp = WLP(1, 1, WLP.TYPE_ACCESSIBILITY_OVERLAY, BASE_FLAGS or WLP.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = "TabIsland"
    }

    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        island = IslandView(this, this)
        island.setOnApplyWindowInsetsListener { _, insets ->
            val bars = WindowInsets.Type.statusBars()
            // overlays above the status bar may get no status-bar insets at all; treat "no info" as visible, never as fullscreen
            val visible = insets.isVisible(bars) || insets.getInsetsIgnoringVisibility(bars).top == 0
            Log.d(TAG, "statusBars visible=${insets.isVisible(bars)} top=${insets.getInsetsIgnoringVisibility(bars).top}")
            if (visible != barsVisible) { barsVisible = visible; render() }
            insets
        }
        place()
        wm.addView(island, lp)
        added = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BT_BATTERY)
        }
        // exported: the Bluetooth broadcasts come from the Bluetooth uid, not system; all actions here are protected broadcasts
        registerReceiver(events, filter, RECEIVER_EXPORTED)
        Island.onChange = ::render
        render()
    }

    override fun onDestroy() {
        Island.onChange = null
        handler.removeCallbacksAndMessages(null)
        if (added) {
            unregisterReceiver(events)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            wm.removeView(island)
            added = false
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val r = Rect()
        windows.forEach { w -> w.getBoundsInScreen(r); Log.d(TAG, "probe type=${w.type} layer=${w.layer} title=${w.title} bounds=$r") }
        Log.d(TAG, "probe ---")
    }
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (added) { place(); render() }
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        place(); render()
    }

    /** Centres the window on the camera hole, plus the user's calibration offsets. */
    private fun place() {
        val m = wm.currentWindowMetrics
        val sw = m.bounds.width()
        val cutout = m.windowInsets.displayCutout
        // the cutout path is the real hole (Pixel 9a: 83 px circle); boundingRectTop runs from the screen edge down and is too tall
        val hole = cutout?.cutoutPath?.let { p ->
            val f = RectF()
            @Suppress("DEPRECATION") p.computeBounds(f, true)
            Rect().also { f.roundOut(it) }
        }
        // shortcut: guessed hole when no cutout is reported (e.g. landscape, where the pill is hidden anyway)
        val cut = hole?.takeUnless { it.isEmpty }
            ?: cutout?.boundingRectTop?.takeUnless { it.isEmpty }
            ?: Rect(sw / 2 - dp(12), dp(10), sw / 2 + dp(12), dp(34))
        Log.d(TAG, "camera hole=$cut")
        val pad = dp(5)
        val pillH = cut.height() + 2 * pad + dp(prefs.getInt("h", 0))
        val gapW = cut.width() + 2 * pad
        lp.x = cut.centerX() - sw / 2 + dp(prefs.getInt("x", 0))
        lp.y = cut.centerY() - pillH / 2 + dp(prefs.getInt("y", 0))
        island.haptics = prefs.getBoolean("haptics", true)
        island.geometry(pillH, gapW, max(pillH, gapW + dp(prefs.getInt("w", 0))), sw - dp(24) - 2 * abs(lp.x))
        if (added) wm.updateViewLayout(island, lp)
    }

    private fun render() {
        if (!added) return
        handler.removeCallbacks(renderLater)
        val now = System.currentTimeMillis()
        val live = Island.top(now) { prefs.getBoolean(it, true) }
        val prev = shown
        if (live == null || prev == null || live::class != prev::class) expanded = false
        shown = live
        expiry(live)?.let { handler.postDelayed(renderLater, it - now + 16) }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hidden = landscape || (!barsVisible && prefs.getBoolean("fullscreen", true))
        Log.d(TAG, "render ${live?.javaClass?.simpleName ?: "idle"} expanded=$expanded hidden=$hidden")
        island.show(live, expanded, prefs.getBoolean("idle", true), hidden)
    }

    // ---- IslandView.Host ----

    override fun window(w: Int, h: Int, touchable: Boolean) {
        val flags = BASE_FLAGS or (if (touchable) 0 else WLP.FLAG_NOT_TOUCHABLE)
        val ww = max(w, 1)
        val hh = max(h, 1)
        if (lp.width == ww && lp.height == hh && lp.flags == flags) return
        lp.width = ww; lp.height = hh; lp.flags = flags
        if (added) wm.updateViewLayout(island, lp)
    }

    override fun tap() {
        when (val l = shown) {
            is Media -> if (!expanded) { expanded = true; render() } else open(l.open)
            is Flash -> { send(l.open); Island.flash = null }
            null -> {}
            else -> open(l.open)
        }
    }

    override fun longPress() {
        val pkg = shown?.pkg ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (expanded) { expanded = false; render() }
    }

    override fun swipeUp() {
        when {
            expanded -> { expanded = false; render() }
            shown is Flash -> Island.flash = null
        }
    }

    override fun media(action: (MediaController.TransportControls) -> Unit) {
        Island.controller?.transportControls?.let(action)
    }

    override fun send(pi: PendingIntent?) {
        pi ?: return
        val opts = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 36) {
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
        } else if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        try {
            pi.send(this, 0, null, null, null, null, opts.toBundle())
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "pending intent no longer valid", e)
        }
    }

    private fun open(pi: PendingIntent?) {
        send(pi)
        if (expanded) { expanded = false; render() }
    }

    // ---- system popups ----

    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> flash("charge", "⚡", "Charging", "$battery%", GREEN)
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (battery > 20 && level in 0..20 && !plugged) flash("battery", "🪫", "Low Battery", "$level%", RED)
                    battery = level
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> if (!isInitialStickyBroadcast) {
                    when (i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                        AudioManager.RINGER_MODE_SILENT -> flash("silent", "🔕", "Silent", "On", RED)
                        AudioManager.RINGER_MODE_VIBRATE -> flash("silent", "📳", "Vibrate", "On", ORANGE)
                        AudioManager.RINGER_MODE_NORMAL -> flash("silent", "🔔", "Ring", "On", WHITE)
                    }
                }
                Intent.ACTION_USER_PRESENT -> flash("unlock", "🔓", "", "", WHITE, 1200)
                BluetoothDevice.ACTION_ACL_CONNECTED -> earbuds(i, null)
                BT_BATTERY -> earbuds(i, i.getIntExtra(BT_LEVEL, -1))
            }
        }
    }

    /** Connect shows "Connected"; a battery report within 10 s of connecting replaces it with the percentage. */
    private fun earbuds(i: Intent, level: Int?) {
        @Suppress("DEPRECATION")
        val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val name = try {
            if (dev.bluetoothClass?.majorDeviceClass != BluetoothClass.Device.Major.AUDIO_VIDEO) return
            dev.alias ?: dev.name
        } catch (e: SecurityException) {
            return // BLUETOOTH_CONNECT not granted
        } ?: "Headphones"
        val now = System.currentTimeMillis()
        if (level == null) {
            earbudsAt = now
            flash("earbuds", "🎧", name, "Connected", WHITE)
        } else if (level in 0..100 && now - earbudsAt < 10_000) {
            flash("earbuds", "🎧", name, "$level%", if (level <= 20) RED else GREEN)
        }
    }

    private fun flash(key: String, glyph: String, title: String, text: String, accent: Int, ms: Long = 3000) {
        Island.flash = Flash(key, title, text, glyph = glyph, accent = accent, until = System.currentTimeMillis() + ms)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}

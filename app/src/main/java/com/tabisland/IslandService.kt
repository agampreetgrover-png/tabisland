package com.tabisland

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.camera2.CameraManager
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import android.view.WindowManager.LayoutParams as WLP

private const val TAG = "TabIsland"
private const val SPOTIFY = "com.spotify.music"
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
    private var dpi = 0

    private var cameraX = 0

    /**
     * Two windows. The drawing window is full-width at x=0, never touchable, and only ever changes height: resizing a
     * centred window shows a frame of the old drawing at the new left edge, which made the pill slide sideways; with
     * the top and left fixed a resize can only clip the bottom. It is just as tall as the pill (1px when hidden), so
     * it doesn't sit over apps or video. The touch window is invisible, sized to the pill, and forwards its touches.
     */
    private val lp = overlayParams(WLP.FLAG_NOT_TOUCHABLE).apply { title = "TabIsland" }
    private val touchLp = overlayParams(WLP.FLAG_NOT_TOUCHABLE).apply { title = "TabIsland touch" }
    private lateinit var touchView: View

    private fun overlayParams(extra: Int) = WLP(1, 1, WLP.TYPE_ACCESSIBILITY_OVERLAY, BASE_FLAGS or extra, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT // absolute screen x, also in right-to-left locales
        layoutInDisplayCutoutMode = WLP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onServiceConnected() {
        wm = getSystemService(WindowManager::class.java)
        dpi = resources.configuration.densityDpi
        prefs =getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        island = IslandView(this, this)
        touchView = View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { _, e ->
                val ev = MotionEvent.obtain(e)
                ev.offsetLocation((touchLp.x - lp.x).toFloat(), (touchLp.y - lp.y).toFloat())
                island.dispatchTouchEvent(ev)
                ev.recycle()
                true
            }
        }
        place()
        wm.addView(island, lp)
        wm.addView(touchView, touchLp)
        added = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        // all actions here are protected system broadcasts, so no other app can send them
        registerReceiver(events, filter, RECEIVER_EXPORTED)
        getSystemService(CameraManager::class.java).registerTorchCallback(torch, handler)
        // session callbacks arrive in bursts of 6–12; coalesce them into one render per frame
        Island.onChange = { handler.removeCallbacks(renderLater); handler.post(renderLater) }
        render()
    }

    override fun onDestroy() {
        Island.onChange = null
        Island.showing = false; Island.syncHeadsUp(this) // island gone: give the system banners back
        Island.removeOngoing("torch") // torch callbacks stop with us; MediaListener keeps this key, a restart re-adds it if still on
        handler.removeCallbacksAndMessages(null)
        if (added) {
            unregisterReceiver(events)
            getSystemService(CameraManager::class.java).unregisterTorchCallback(torch)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            wm.removeView(island)
            wm.removeView(touchView)
            added = false
        }
        super.onDestroy()
    }

    /**
     * Fullscreen detection. Our overlay sits above the status bar so it never receives status-bar insets; instead,
     * the status bar is a full-width TYPE_SYSTEM window at the top, and it leaves the window list when an app hides it
     * (measured on the Pixel 9a: Rect(0, 0 - 1080, 152) present normally, absent in fullscreen).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!added) return
        val list = windows
        if (list.isEmpty()) return // no window info: never guess fullscreen
        val sw = wm.currentWindowMetrics.bounds.width()
        val r = Rect()
        val visible = list.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && run { it.getBoundsInScreen(r); r.top == 0 && r.width() >= sw } }
        if (visible != barsVisible) {
            barsVisible = visible
            Log.d(TAG, "status bar visible=$visible")
            render()
        }
    }
    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!added) return
        if (newConfig.densityDpi != dpi) { // IslandView bakes pixel sizes in at construction: rebuild it
            dpi = newConfig.densityDpi
            island.show(null, false, false, true) // stops the old view's spring clock so it can't resize our windows
            wm.removeView(island)
            island = IslandView(this, this)
            wm.addView(island, lp)
        }
        place(); render()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        place(); render()
    }

    /** Lines the island up with the camera hole, plus the user's calibration offsets. */
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
        cameraX = cut.centerX() + dp(prefs.getInt("x", 0))
        val dx = cameraX - sw / 2
        lp.x = 0
        lp.y = cut.centerY() - pillH / 2 + dp(prefs.getInt("y", 0))
        lp.width = sw
        touchLp.y = lp.y
        island.haptics = prefs.getBoolean("haptics", true)
        island.geometry(pillH, gapW, max(pillH, gapW + dp(prefs.getInt("w", 0))), sw - dp(24) - 2 * abs(dx), dx)
        if (added) {
            wm.updateViewLayout(island, lp)
            wm.updateViewLayout(touchView, touchLp)
        }
    }

    private fun render() {
        if (!added) return
        handler.removeCallbacks(renderLater)
        val now = System.currentTimeMillis()
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // message text over the keyguard would bypass the system's "hide sensitive content" setting
        val live = Island.top(now) { prefs.getBoolean(it, true) && !(locked && it == "notif") }
        val prev = shown
        if (live == null || prev == null || live::class != prev::class) expanded = false
        maybePeek(live, now)
        shown = live
        expiry(live)?.let { handler.postDelayed(renderLater, it - now + 16) }
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hidden = landscape || (!barsVisible && prefs.getBoolean("fullscreen", true))
        // hidden (landscape, fullscreen), locked (messages dropped above) or notifications switched off: the system banner must show instead
        Island.showing = !hidden && !locked && prefs.getBoolean("notif", true); Island.syncHeadsUp(this)
        Log.d(TAG, "render ${live?.javaClass?.simpleName ?: "idle"} expanded=$expanded hidden=$hidden")
        island.show(live, expanded, prefs.getBoolean("idle", true), hidden)
    }

    // ---- IslandView.Host ----

    override fun touch(w: Int, h: Int, on: Boolean) {
        val flags = BASE_FLAGS or (if (on) 0 else WLP.FLAG_NOT_TOUCHABLE)
        val ww = max(w, 1)
        val hh = max(h, 1)
        val x = cameraX - ww / 2
        if (touchLp.width == ww && touchLp.height == hh && touchLp.flags == flags && touchLp.x == x) return
        touchLp.width = ww; touchLp.height = hh; touchLp.flags = flags; touchLp.x = x
        if (added) wm.updateViewLayout(touchView, touchLp)
    }

    override fun canvas(h: Int) {
        val hh = max(h, 1)
        if (lp.height == hh) return
        lp.height = hh
        if (added) wm.updateViewLayout(island, lp)
    }

    // ---- song announcement: Spotify starting opens the music card for 3 s, then it shrinks back ----

    private var wasPlaying: Boolean? = null
    private var stoppedAt = 0L
    private var peeking = false
    private val endPeek = Runnable {
        if (peeking) { peeking = false; expanded = false; render() }
    }

    private fun maybePeek(live: Live?, now: Long) {
        val m = Island.media
        val playing = m?.playing == true
        val was = wasPlaying
        wasPlaying = playing
        if (was == null) return // first look after the service starts: don't announce what was already playing
        if (was && !playing) stoppedAt = now
        // a start, not a skip or buffering blip (those stop for well under 1.5 s)
        val started = !was && playing && now - stoppedAt > 1500
        if (started && live is Media && m?.pkg == SPOTIFY && !expanded && prefs.getBoolean("peek", true)) {
            peeking = true
            expanded = true
            handler.removeCallbacks(endPeek)
            handler.postDelayed(endPeek, 3000)
        }
    }

    /** Any touch on the island takes over from the automatic announcement. */
    private fun stopPeek() {
        peeking = false
        handler.removeCallbacks(endPeek)
    }

    /** End of a drag: always re-render, since renders were held while the finger owned the island. */
    override fun expand(open: Boolean) {
        stopPeek()
        if (shown is Media) expanded = open
        render()
    }

    override fun tap() {
        stopPeek()
        when (val l = shown) {
            is Media -> if (!expanded) { expanded = true; render() } else open(l.open)
            is Flash -> { open(l.open); Island.flash = null }
            is Ongoing -> if (l.key == "torch") torchOff() else open(l.open)
            null -> {}
            else -> open(l.open)
        }
    }

    override fun longPress() {
        stopPeek()
        val pkg = shown?.pkg ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        if (expanded) { expanded = false; render() }
    }

    override fun swipeUp() {
        stopPeek()
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
        // locked with a PIN: an activity opens behind the keyguard, but a broadcast/service would act without unlocking
        if (pi != null && !pi.isActivity && getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        send(pi)
        if (expanded) { expanded = false; render() }
    }

    // ---- flashlight live activity: shown while the torch is on; tapping it turns the torch off ----

    private var torchId: String? = null
    private val torch = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (enabled) {
                torchId = cameraId
                Island.putOngoing(Ongoing("torch", "torch", "Flashlight", "", tinted(R.drawable.ic_flashlight, YELLOW),
                    YELLOW, null, 2, null, null))
            } else if (cameraId == torchId) {
                Island.removeOngoing("torch")
            }
        }

        // a camera app opening turns the torch off but reports "unavailable", not "changed"
        override fun onTorchModeUnavailable(cameraId: String) {
            if (cameraId == torchId) Island.removeOngoing("torch")
        }
    }

    private fun torchOff() {
        val id = torchId ?: return
        try {
            getSystemService(CameraManager::class.java).setTorchMode(id, false)
        } catch (e: Exception) { // CameraAccessException / IllegalArgumentException: camera busy or gone
            Log.w(TAG, "could not turn the torch off", e)
        }
    }

    // ---- system popups ----

    private val events = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> flash("charge", R.drawable.ic_bolt, "Charging", "$battery%", GREEN, battery)
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (battery > 20 && level in 0..20 && !plugged) flash("battery", R.drawable.ic_battery_alert, "Low Battery", "$level%", RED, level)
                    battery = level
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> if (!isInitialStickyBroadcast) {
                    when (i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                        AudioManager.RINGER_MODE_SILENT -> flash("silent", R.drawable.ic_bell_off, "Silent", "On", RED)
                        AudioManager.RINGER_MODE_VIBRATE -> flash("silent", R.drawable.ic_vibrate, "Vibrate", "On", ORANGE)
                        AudioManager.RINGER_MODE_NORMAL -> flash("silent", R.drawable.ic_bell, "Ring", "On", WHITE)
                    }
                }
                // closed lock grows out of the camera, springs open at ~0.45 s, then the pill shrinks back (~1.8 s in all)
                Intent.ACTION_USER_PRESENT -> Island.flash = Flash("unlock", "", "", icon = UnlockDrawable(WHITE),
                    until = System.currentTimeMillis() + 1800)
                // a message card still up at screen-off would otherwise reappear over the lock screen on wake
                Intent.ACTION_SCREEN_OFF -> if (Island.flash?.key == "notif") Island.flash = null
                // nothing else re-renders on lock: hand pop-ups back to the system while the keyguard is up
                Intent.ACTION_SCREEN_ON -> render()
            }
        }
    }

    private fun flash(key: String, icon: Int, title: String, text: String, accent: Int, level: Int? = null, ms: Long = 3000) {
        Island.flash = Flash(key, title, text, icon = tinted(icon, accent), accent = accent, level = level,
            until = System.currentTimeMillis() + ms)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}

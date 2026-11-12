package com.tabisland

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeProvider
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import android.view.WindowManager.LayoutParams as WLP

private const val TAG = "TabIsland"
private const val SPOTIFY = "com.spotify.music"
private const val BASE_FLAGS = WLP.FLAG_NOT_FOCUSABLE or WLP.FLAG_LAYOUT_IN_SCREEN or WLP.FLAG_LAYOUT_NO_LIMITS

/** The front camera hole in screen pixels. Shared with the settings preview so both line up the same way. */
fun cameraHole(m: WindowMetrics, density: Float): Rect {
    val sw = m.bounds.width()
    val cutout = m.windowInsets.displayCutout
    // the cutout path is the real hole (Pixel 9a: 83 px circle); boundingRectTop runs from the screen edge down and is too tall
    val hole = cutout?.cutoutPath?.let { p ->
        val f = RectF()
        @Suppress("DEPRECATION") p.computeBounds(f, true)
        Rect().also { f.roundOut(it) }
    }
    fun dp(v: Int) = (v * density).roundToInt()
    // shortcut: guessed hole when no cutout is reported (e.g. landscape, where the pill is hidden anyway)
    return hole?.takeUnless { it.isEmpty }
        ?: cutout?.boundingRectTop?.takeUnless { it.isEmpty }
        ?: Rect(sw / 2 - dp(12), dp(10), sw / 2 + dp(12), dp(34))
}

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
    // every way the card closes cancels the 8 s auto-collapse, or an old one would cut a later card (a peek) short
    private var expanded = false
        set(v) { field = v; if (!v) handler.removeCallbacks(collapse) }
    private var minimised: String? = null // key of the ringing call the user swiped up to the pill
    private val collapse = Runnable { if (expanded && !island.held) { expanded = false; render() } }
    private var barsVisible = true
    private var lockedShown = false // the keyguard state the last render used
    private var battery = -1
    private var lowArmed = true
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
        touchView = object : View(this) {
            override fun getAccessibilityNodeProvider() = a11y
            // explore-by-touch arrives as hover: announce the island control under the finger
            override fun dispatchHoverEvent(e: MotionEvent): Boolean {
                val at = if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1
                    else a11yViews().indexOfLast { screenRect(it).contains(e.rawX.toInt(), e.rawY.toInt()) }
                // enter before exit, as the framework orders them
                if (at != hovered) { val old = hovered; hovered = at; a11yEvent(at, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER); a11yEvent(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) }
                return at >= 0 || super.dispatchHoverEvent(e)
            }
        }.apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnTouchListener { _, e ->
                // a touch anywhere else closes the music card, as tapping outside a sheet would
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    if (expanded) { stopPeek(); expanded = false; render() }
                    return@setOnTouchListener true
                }
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
        Island.clearFlashes("notif") // a later restart mustn't show these as new
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
        // lockdown locks the keyguard with the screen on and no broadcast: the keyguard window coming up is our cue
        if (keyguardLocked() != lockedShown) render()
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
        val cut = cameraHole(m, resources.displayMetrics.density)
        Log.d(TAG, "camera hole=$cut")
        val pad = dp(5)
        val pillH = cut.height() + 2 * pad + dp(prefs.getInt("h", 0))
        val gapW = cut.width() + 2 * pad
        // cards are centred on the camera: a corner one would leave them a sliver (or a negative width), so the
        // island moves in from it as far as needed to keep cards at least 280dp wide
        val maxDx = ((sw - dp(16 + 280)) / 2).coerceAtLeast(0)
        val dx = (cut.centerX() + dp(prefs.getInt("x", 0)) - sw / 2).coerceIn(-maxDx, maxDx)
        cameraX = sw / 2 + dx
        lp.x = 0
        lp.y = cut.centerY() - pillH / 2 + dp(prefs.getInt("y", 0))
        lp.width = sw
        touchLp.y = lp.y
        island.haptics = prefs.getBoolean("haptics", true)
        // cards run edge to edge with an 8dp gutter: with their 40dp radius they follow the screen's own rounded corners
        island.geometry(pillH, gapW, max(pillH, gapW) + dp(prefs.getInt("w", 0).coerceAtLeast(0)), sw - dp(16) - 2 * abs(dx), dx)
        if (added) {
            wm.updateViewLayout(island, lp)
            wm.updateViewLayout(touchView, touchLp)
        }
    }

    private fun render() {
        // the finger owns the island: a card must not change or expire under it; released() re-renders
        if (!added || island.held) return
        handler.removeCallbacks(renderLater)
        val now = System.currentTimeMillis()
        val locked = keyguardLocked()
        lockedShown = locked
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hidden = landscape || (!barsVisible && prefs.getBoolean("fullscreen", true))
        val on = { k: String -> k == Island.demo || prefs.getBoolean(k, true) }
        // message text over the keyguard would bypass the system's "hide sensitive content" setting; locked or
        // hidden, messages wait unseen instead of running out
        Island.hold = locked || hidden
        val live = Island.top(now) { on(it) && !(locked && it == "notif") }
        val prev = shown
        if (live == null || prev == null || live::class != prev::class) expanded = false
        if (Island.call?.let { it.ringing && it.key == minimised } != true) minimised = null
        maybePeek(live, now, hidden || locked)
        shown = live
        expiry(live)?.let { handler.postDelayed(renderLater, it - now + 16) }
        // hidden (landscape, fullscreen), locked (messages wait above), notifications off, or calls off (Android picks a
        // call's pop-up when it is posted, before the island could hand it back): the system's own pop-ups must show
        // the real switches, not demo mode: a demo must not take real notifications away from the system banner
        Island.showing = !hidden && !locked && prefs.getBoolean("notif", true) && prefs.getBoolean("call", true)
        Island.syncHeadsUp(this)
        val big = if (live is Call) live.ringing && live.key != minimised else expanded
        Log.d(TAG, "render ${live?.javaClass?.simpleName ?: "idle"} expanded=$big hidden=$hidden")
        island.show(live, big, prefs.getBoolean("idle", true) || Island.calibrating, hidden)
        touchView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) // the mirrored controls below changed
    }

    // ---- TalkBack: the drawing window is never touchable, so accessibility doesn't list it; the touch window (touchable
    // while something shows, sized to the pill) mirrors the pill and its controls as virtual views instead ----

    private var a11yFocus = -1
    private var hovered = -1

    /** What TalkBack stops on: the pill (the island's one child) and every clickable or labelled view in it, in order. */
    private fun a11yViews(): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (!v.isShown) return
            if (v.isClickable || !v.contentDescription.isNullOrEmpty()) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        island.getChildAt(0)?.let(::walk)
        return out
    }

    private fun screenRect(v: View): Rect {
        val at = IntArray(2).also(v::getLocationOnScreen)
        return Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
    }

    private fun a11yEvent(id: Int, type: Int) {
        val v = a11yViews().getOrNull(id) ?: return
        if (!getSystemService(AccessibilityManager::class.java).isEnabled) return // sending would throw
        touchView.parent?.requestSendAccessibilityEvent(touchView, AccessibilityEvent(type).apply {
            setSource(touchView, id)
            packageName = this@IslandService.packageName
            className = v.accessibilityClassName
            contentDescription = v.contentDescription
            if (v is TextView) text.add(v.text)
        })
    }

    private val a11y = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
            val views = a11yViews()
            if (id == AccessibilityNodeProvider.HOST_VIEW_ID) return AccessibilityNodeInfo(touchView).apply {
                touchView.onInitializeAccessibilityNodeInfo(this)
                views.indices.forEach { addChild(touchView, it) }
            }
            val v = views.getOrNull(id) ?: return null
            return AccessibilityNodeInfo(touchView, id).apply {
                setParent(touchView)
                packageName = this@IslandService.packageName
                className = v.accessibilityClassName
                contentDescription = v.contentDescription
                if (v is TextView) text = v.text
                isEnabled = v.isEnabled
                isVisibleToUser = true
                setBoundsInScreen(screenRect(v))
                if (v.isClickable) { isClickable = true; addAction(AccessibilityAction.ACTION_CLICK) }
                if (v === island.getChildAt(0)) { // the pill: its long-press and swipe-up gestures
                    isLongClickable = true
                    addAction(AccessibilityAction.ACTION_LONG_CLICK)
                    addAction(AccessibilityAction.ACTION_DISMISS)
                }
                isAccessibilityFocused = id == a11yFocus
                addAction(if (id == a11yFocus) AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            }
        }

        override fun performAction(id: Int, action: Int, args: Bundle?): Boolean {
            if (id == AccessibilityNodeProvider.HOST_VIEW_ID) return touchView.performAccessibilityAction(action, args)
            val v = a11yViews().getOrNull(id) ?: return false
            when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> if (a11yFocus != id) {
                    a11yEvent(a11yFocus, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    a11yFocus = id
                    a11yEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> if (a11yFocus == id) {
                    a11yFocus = -1
                    a11yEvent(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                }
                AccessibilityNodeInfo.ACTION_CLICK -> return v.performClick() // the pill's click is tap()
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> if (v === island.getChildAt(0)) longPress() else return false
                AccessibilityNodeInfo.ACTION_DISMISS -> if (v === island.getChildAt(0)) swipeUp() else return false
                else -> return false
            }
            return true
        }
    }

    // ---- IslandView.Host ----

    override fun touch(w: Int, h: Int, on: Boolean) {
        val flags = BASE_FLAGS or WLP.FLAG_WATCH_OUTSIDE_TOUCH or (if (on) 0 else WLP.FLAG_NOT_TOUCHABLE)
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
    private var lastSong: String? = null
    private var peeking = false
    // held: the finger took over from the announcement, released() ends it
    private val endPeek = Runnable {
        if (peeking && !island.held) { peeking = false; expanded = false; render() }
    }

    /** [quiet]: hidden or behind the keyguard, where an announcement would only be noise. */
    private fun maybePeek(live: Live?, now: Long, quiet: Boolean) {
        val m = Island.media
        val playing = m?.playing == true
        val was = wasPlaying
        wasPlaying = playing
        val song = if (playing) m?.title else lastSong
        val newSong = song != lastSong
        lastSong = song
        if (was == null) return // first look after the service starts: don't announce what was already playing
        if (was && !playing) stoppedAt = now
        // a different song (also the next track while playing), or music back after a real break; resuming the same
        // song after a pause, or a metadata refresh of it, isn't news
        val started = playing && (newSong || !was && now - stoppedAt >= 60_000)
        if (started && !quiet && live is Media && m?.pkg == SPOTIFY && !expanded && prefs.getBoolean("peek", true)) {
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
            is Flash -> {
                if (l.autoCancel) l.id?.let { Island.cancel?.invoke(it) }
                open(l.open); Island.dismissFlash(l)
            }
            // the call screen shows over the keyguard, ringing or answered (as the lock screen's own call chip opens
            // it): no PIN prompt; it can't reveal anything else, an activity that isn't show-when-locked opens behind
            is Call -> if (l.ringing && l.key == minimised) { minimised = null; render() }
                else if (l.open?.isActivity == true) send(l.open) else open(l.open)
            is Ongoing -> if (l.key == "torch") torchOff() else open(l.open)
            null -> {}
            else -> open(l.open)
        }
    }

    override fun longPress() {
        stopPeek()
        val pkg = shown?.pkg ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (keyguardLocked()) UnlockActivity.start(this, null, launch) else startActivity(launch)
        if (expanded) { expanded = false; render() }
    }

    override fun swipeUp() {
        stopPeek()
        val l = shown
        when {
            expanded -> { expanded = false; render() }
            l is Call && l.ringing && l.key != minimised -> { minimised = l.key; render() }
            l is Flash -> Island.dismissFlash(l)
        }
    }

    override fun action(f: Flash, a: Notification.Action) {
        stopPeek()
        if (a.remoteInputs.isNullOrEmpty()) send(a.actionIntent)
        else ReplyActivity.start(this, a, f.title)
        Island.dismissFlash(f) // an expired card may already have given way to the next one: leave that one up
    }

    /** End of every touch on the pill: renders were held while the finger was down. */
    override fun released() {
        stopPeek()
        // posted: a button's click is queued behind this UP, and a render now could rebuild the button and cancel it
        handler.post {
            render()
            handler.removeCallbacks(collapse)
            if (expanded) handler.postDelayed(collapse, 8000)
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
        // locked: unlock first (a PIN, or just dismissing a swipe keyguard); an activity would open unseen behind the
        // keyguard, and a broadcast/service would act without unlocking at all
        when {
            pi == null -> {}
            keyguardLocked() -> UnlockActivity.start(this, pi, null)
            pi.isActivity || !getSystemService(KeyguardManager::class.java).isDeviceLocked -> send(pi)
        }
        if (expanded) { expanded = false; render() }
    }

    // any keyguard, secure or not: requestDismissKeyguard (UnlockActivity) dismisses a swipe one without a prompt
    private fun keyguardLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

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
                    // re-armed only well clear of 20 %, so a level flapping between 20 and 21 alerts once
                    if (plugged || level >= 25) lowArmed = true
                    else if (lowArmed && battery > 20 && level in 0..20) {
                        lowArmed = false
                        flash("battery", R.drawable.ic_battery_alert, "Low Battery", "$level%", RED, level)
                    }
                    battery = level
                }
                AudioManager.RINGER_MODE_CHANGED_ACTION -> if (!isInitialStickyBroadcast) {
                    when (i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)) {
                        AudioManager.RINGER_MODE_SILENT -> flash("silent", R.drawable.ic_bell_off, "Silent", "On", RED)
                        AudioManager.RINGER_MODE_VIBRATE -> flash("silent", R.drawable.ic_vibrate, "Vibrate", "On", ORANGE)
                        AudioManager.RINGER_MODE_NORMAL -> flash("silent", R.drawable.ic_bell, "Ring", "On", WHITE)
                    }
                }
                // closed lock grows out of the camera, springs open at ~0.45 s, then the pill shrinks back (~1.8 s in all);
                // ahead of the messages that waited behind the keyguard, or it would play long after the unlock
                Intent.ACTION_USER_PRESENT -> Island.post(Flash("unlock", "", "", icon = UnlockDrawable(WHITE),
                    until = System.currentTimeMillis() + 1800), first = true)
                // message cards still up or queued at screen-off would otherwise reappear over the lock screen on wake
                Intent.ACTION_SCREEN_OFF -> {
                    Island.clearFlashes("notif")
                    expanded = false; stopPeek(); render()
                }
                // nothing else re-renders on lock: hand pop-ups back to the system while the keyguard is up
                Intent.ACTION_SCREEN_ON -> render()
            }
        }
    }

    private fun flash(key: String, icon: Int, title: String, text: String, accent: Int, level: Int? = null, ms: Long = 3000) {
        Island.post(Flash(key, title, text, icon = tinted(icon, accent), accent = accent, level = level,
            until = System.currentTimeMillis() + ms))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}

package com.tabisland

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Settings keys shared with IslandService. Switches default on, sliders default 0 (dp). */
object Prefs {
    const val FILE = "island"
    val groups = listOf(
        "What the island shows" to listOf(
            "media" to "Music",
            "peek" to "Show the song when Spotify starts",
            "call" to "Calls",
            "notif" to "Notifications",
            "timer" to "Timers and stopwatch",
            "nav" to "Navigation",
            "torch" to "Flashlight",
            "progress" to "Download progress",
        ),
        "System events" to listOf(
            "charge" to "Charging",
            "battery" to "Low battery (20%)",
            "silent" to "Silent, vibrate and ring",
            "unlock" to "Unlock",
        ),
        "Behaviour" to listOf(
            "idle" to "Show the pill when idle",
            "fullscreen" to "Hide in fullscreen apps",
            "haptics" to "Haptics",
        ),
    )

    class Slider(val key: String, val label: String, val min: Int, val max: Int)

    val sliders = listOf(
        Slider("x", "Left and right", -40, 40),
        Slider("y", "Up and down", -20, 20),
        Slider("w", "Idle pill width", 0, 40),
        Slider("h", "Pill height", -8, 16),
    )
}

// The island's own material: true black, with greys that keep ≥4.5:1 on the group surface.
private const val INK = 0xFF000000.toInt()
private const val BASALT = 0xFF161618.toInt()
private const val SEAM = 0xFF2A2A2E.toInt()
private const val PUMICE = 0xFF9E9EA6.toInt()
private const val BONE = 0xFFF4F4F1.toInt()
private const val TRACK = 0xFF6E6E76.toInt() // off-state switch track: 3.2:1 on BASALT, unlike the hairline SEAM
private const val GRANT = "adb shell pm grant com.tabisland android.permission.WRITE_SECURE_SETTINGS"
private const val UNDO = "adb shell settings put global heads_up_notifications_enabled 1"

private const val HOLE_Y = 44 // dp from the stage's top to the camera's centre: room for the pill at y −20

class MainActivity : Activity() {
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()
    private val prefs by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private class Step(val dot: ImageView, val action: TextView, val todo: String, val sub: TextView, val todoSub: String,
                       val doneSub: String, var done: Boolean? = null)
    private val steps = mutableListOf<Step>()
    private lateinit var col: LinearLayout
    private lateinit var status: TextView
    private lateinit var statusDot: GradientDrawable
    private lateinit var setup: LinearLayout
    private var setupAt = 0 // setup's place in col while it isn't finished; once it is, it moves to the end
    private lateinit var restrictedHint: View
    private lateinit var undoNote: View
    private lateinit var reset: View
    private lateinit var demoHint: TextView
    private lateinit var testAction: TextView
    private val seekBars = HashMap<String, SeekBar>()

    // the stage: the real IslandView at 1:1 scale under the camera ring, dragged to fit
    private lateinit var stage: FrameLayout
    private lateinit var preview: IslandView
    private lateinit var ring: View
    private lateinit var guide: View
    private lateinit var readout: TextView
    private lateinit var stageHint: TextView
    private var tab = 0

    private val renderLater = Runnable { renderPreview() }
    private val prefsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (Prefs.sliders.any { it.key == key }) {
            // a drag on the stage moves the sliders, and a slider moves the stage: prefs are the one source
            seekBars[key]?.let { val v = prefs.getInt(key, 0); if (it.progress != v) it.progress = v }
            layoutPreview()
        } else renderPreview()
    }

    // the preview only shows; every touch on the stage goes to the fitting gestures above it
    private val previewHost = object : IslandView.Host {
        override fun touch(w: Int, h: Int, on: Boolean) {}
        override fun canvas(h: Int) {}
        override fun expand(open: Boolean) {}
        override fun tap() {}
        override fun longPress() {}
        override fun swipeUp() {}
        override fun media(action: (MediaController.TransportControls) -> Unit) {}
        override fun send(pi: PendingIntent?) {}
        override fun action(f: Flash, a: Notification.Action) {}
        override fun released() = renderPreview()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        tab = savedInstanceState?.getInt("tab") ?: 0
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(text("Tab Island", 30f, BONE, 600).apply { letterSpacing = -0.02f; setPadding(dp(16), 0, dp(16), 0); isAccessibilityHeading = true })
        statusDot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PUMICE) }
        status = text("", 15f, PUMICE)
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
            addView(View(context).apply { background = statusDot }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8) })
            addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        col.addView(stage(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(208)).apply { topMargin = dp(18) })
        readout = text("", 13f, BONE, 500).apply { fontFeatureSettings = "tnum" }
        col.addView(LinearLayout(this).apply {
            setPadding(dp(16), dp(10), dp(16), 0)
            addView(text("Drag to move. Pinch to widen.", 13f, PUMICE), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(readout)
        })

        // demos: one tab per kind of island, its styles as chips that play on the stage (and the real island)
        val chips = Flow(this, dp(8))
        val tabs = Demo.groups.mapIndexed { i, (name, _) -> segment(name) { showTab(i, chips) } }
        // one basalt track with the selected tab inside it: reads as navigation, not as another row of chips
        col.addView(LinearLayout(this).apply {
            background = GradientDrawable().apply { setColor(BASALT); cornerRadius = dp(28).toFloat() }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            // each tab gets its label's width plus an equal share of what's left, so a long label never crowds its neighbour
            for (t in tabs) addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        col.addView(chips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        showTab(tab, chips)
        demoHint = text("Playing in the preview only. Turn on step 1 to see it on the real island.", 13f, PUMICE).apply {
            setPadding(dp(16), dp(10), dp(16), 0); visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        col.addView(demoHint)

        setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupAt = col.childCount
        col.addView(setup)
        setup.addView(heading("Set up"))
        setup.addView(group(
            step(1, "Show the island", "Accessibility") {
                // Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS is not in the public SDK
                open(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, IslandService::class.java).flattenToString()),
                    Settings.ACTION_ACCESSIBILITY_SETTINGS)
            },
            step(2, "Music, calls and notifications", "Notification access") {
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, MediaListener::class.java).flattenToString()),
                    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            },
            step(3, "Replace system pop-ups", "Optional. Run one adb command from a computer", "Copy",
                doneSub = "The island shows pop-ups instead of the system") {
                if (steps[2].done != true) {
                    copy(GRANT)
                    steps[2].action.text = "Copied"
                }
            },
        ))
        // only once pop-ups are replaced is there anything to undo
        val undo = actionRow(UNDO, "Copy") { copy(UNDO) }
        (undo.getChildAt(0) as TextView).apply { typeface = Typeface.MONOSPACE; textSize = 13f } // a command, set as code
        undoNote = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Before uninstalling, turn the island off first, or system pop-ups stay off. To bring them back, run:",
                13f, PUMICE).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) })
            addView(group(undo))
        }
        setup.addView(undoNote)
        restrictedHint = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(
                "If a switch is greyed out in Settings, open App info, tap ⋮, choose Allow restricted settings, then try again.",
                13f, PUMICE,
            ).apply { setPadding(dp(16), dp(10), dp(16), dp(10)) })
            addView(group(actionRow("App info", "Open") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }))
        }
        setup.addView(restrictedHint)

        val switches = HashMap<String, View>()
        val test = actionRow("Test fullscreen hiding", "Try") { toggleFullscreen() }
        testAction = test.getChildAt(1) as TextView
        // a child setting means nothing while its parent switch is off
        fun deps() {
            enable(switches.getValue("peek"), prefs.getBoolean("media", true))
            val hiding = prefs.getBoolean("fullscreen", true)
            enable(test, hiding)
            if (!hiding && fullscreen) toggleFullscreen() // its Stop row is disabled now, so stop here
        }
        for ((title, items) in Prefs.groups) {
            col.addView(heading(title))
            val rows = items.map { (key, label) ->
                switchRow(label, prefs.getBoolean(key, true)) { prefs.edit().putBoolean(key, it).apply(); deps() }.also { switches[key] = it }
            }
            val extra = if (title == "Behaviour") listOf(test) else emptyList()
            col.addView(group(*(rows + extra).toTypedArray()))
        }
        deps()

        col.addView(heading("Fit to your camera"))
        col.addView(text("Exact values for the preview at the top. The island moves as you drag.", 13f, PUMICE).apply { setPadding(dp(16), 0, dp(16), dp(10)) })
        val sliders = Prefs.sliders.map { s -> sliderRow(s, prefs.getInt(s.key, 0)) { prefs.edit().putInt(s.key, it).apply() } }
        // Reset needs no slider code: the prefs listener moves them back
        reset = actionRow("Position and size", "Reset") {
            prefs.edit().apply { Prefs.sliders.forEach { putInt(it.key, 0) } }.apply()
        }
        col.addView(group(*sliders.toTypedArray(), reset))
        prefs.registerOnSharedPreferenceChangeListener(prefsChanged)

        setContentView(ScrollView(this).apply {
            id = 1 // fixed id: a ScrollView only keeps its scroll position across recreation when it has one
            setBackgroundColor(INK)
            isVerticalScrollBarEnabled = false
            addView(col)
            clipToPadding = true // content stops at the status bar instead of scrolling under the clock and the island
            isVerticalFadingEdgeEnabled = true // and fades out there, instead of a hard cut through a line of text
            setFadingEdgeLength(dp(24))
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(0, bars.top, 0, 0)
                col.setPadding(dp(16) + bars.left, dp(28), dp(16) + bars.right, bars.bottom + dp(32))
                insets
            }
        })
        if (intent.getBooleanExtra("fullscreen", false)) toggleFullscreen()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsChanged)
        if (!isChangingConfigurations) Demo.clear() // a rotation shouldn't cut a demo short
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", tab)
    }

    override fun onPause() {
        Island.calibrating = false // a slider or stage touch can end without its up event when the app leaves
        Island.preview = null
        handler.removeCallbacks(renderLater)
        preview.show(null, false, false, true) // stops its spring clock; it grows out of the camera again on return
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        Island.syncHeadsUp(this) // repairs a value left at 0 by a process killed without callbacks (force-stop)
        val a11y = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true
        val running = Island.onChange != null // enabled in settings isn't enough: the service can be bound but dead
        val notif = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, MediaListener::class.java))
        val secure = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        listOf(running, notif, secure).forEachIndexed { i, done -> mark(i, done) }
        status.text = when {
            !a11y -> "The island is off. Start with step 1."
            !running -> "Enabled but not running. Turn it off and on again."
            !notif -> "The island is on. Finish setup to show music, calls and notifications."
            else -> "The island is on."
        }
        statusDot.setColor(when { !a11y -> RED; running && notif -> GREEN; else -> ORANGE })
        restrictedHint.visibility = if (a11y && notif) View.GONE else View.VISIBLE
        undoNote.visibility = if (secure) View.VISIBLE else View.GONE
        if (running) demoHint.visibility = View.GONE
        // finished setup is rarely needed again: it moves to the end, out of the way of the demos and switches
        val done = running && notif
        val at = if (done) col.childCount - 1 else setupAt
        if (col.indexOfChild(setup) != at) {
            col.removeView(setup)
            col.addView(setup, if (done) col.childCount else setupAt)
        }
        // the service owns onChange; the preview gets its own hook, coalesced to one render per frame like the service's
        Island.preview = { handler.removeCallbacks(renderLater); handler.post(renderLater) }
        layoutPreview()
    }

    // ---- the stage ----

    @SuppressLint("ClickableViewAccessibility")
    private fun stage(): View {
        preview = IslandView(this, previewHost).apply {
            haptics = false // the real island, or the fitting ticks, do the buzzing
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        // the camera drawn over the island as a ring, so you can see when the pill is centred on it
        ring = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(max(1, dp(1)), TRACK) }
        }
        guide = View(this).apply { alpha = 0f }
        // the empty stage says what it is for; it steps aside whenever the island has something to show
        stageHint = text("Tap a style below to play it here.", 13f, PUMICE).apply {
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // the stage's own description covers it
        }
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            var w0 = 0
            var span0 = 0f
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { w0 = prefs.getInt("w", 0); span0 = d.currentSpanX; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean { nudge("w", w0 + ((d.currentSpanX - span0) / density).roundToInt()); return true }
        })
        var x0 = 0; var y0 = 0; var fx = 0f; var fy = 0f; var pinched = false
        val touch = View(this).apply {
            contentDescription = "Island preview"
            stateDescription = "Drag to move it, pinch to widen it. Exact values are under Fit to your camera."
            setOnTouchListener { v, e ->
                pinch.onTouchEvent(e)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.parent.requestDisallowInterceptTouchEvent(true) // the page mustn't scroll away under the finger
                        x0 = prefs.getInt("x", 0); y0 = prefs.getInt("y", 0); fx = e.x; fy = e.y; pinched = false
                        fitting(true)
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> pinched = true // two fingers size it; it holds still until they lift
                    MotionEvent.ACTION_MOVE -> if (!pinched) {
                        nudge("x", x0 + ((e.x - fx) / density).roundToInt())
                        nudge("y", y0 + ((e.y - fy) / density).roundToInt())
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fitting(false)
                }
                true
            }
        }
        stage = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(BASALT); cornerRadius = dp(28).toFloat() }
            clipToOutline = true
            addView(stageHint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM).apply { bottomMargin = dp(20) })
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(guide, FrameLayout.LayoutParams(max(1, dp(1)), FrameLayout.LayoutParams.MATCH_PARENT))
            addView(ring, FrameLayout.LayoutParams(0, 0))
            addView(touch, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) post { layoutPreview() } }
        }
        return stage
    }

    /** A finger is fitting the island: the real one shows its idle pill too, so there is something to line up. */
    private fun fitting(on: Boolean) {
        Island.calibrating = on
        guide.animate().alpha(if (on) 1f else 0f).setDuration(150).start()
    }

    /** Sets a calibration value from the stage, within its slider's range. */
    private fun nudge(key: String, v: Int) {
        val s = Prefs.sliders.first { it.key == key }
        val c = v.coerceIn(s.min, s.max)
        val old = prefs.getInt(key, 0)
        if (c == old) return
        prefs.edit().putInt(key, c).apply()
        // a tick when it lines up with the camera again (0, or crossing it) and at either end of the range
        if ((c == 0 || old * c < 0 || c == s.min || c == s.max) && prefs.getBoolean("haptics", true))
            stage.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Places the preview exactly as IslandService.place() places the island, inside the stage instead of the screen. */
    private fun layoutPreview() {
        val w = stage.width
        if (w == 0) return // first layout pending; the stage's layout listener calls back
        val m = windowManager.currentWindowMetrics
        val cut = cameraHole(m, density)
        val camX = cut.centerX() - m.bounds.width() / 2 // camera centre relative to the screen centre
        val pad = dp(5)
        val pillH = cut.height() + 2 * pad + dp(prefs.getInt("h", 0))
        val gapW = cut.width() + 2 * pad
        val dx = camX + dp(prefs.getInt("x", 0))
        preview.geometry(pillH, gapW, max(pillH, gapW) + dp(prefs.getInt("w", 0).coerceAtLeast(0)), w - dp(16) - 2 * abs(dx), dx)
        val holeY = dp(HOLE_Y)
        preview.translationY = (holeY - pillH / 2 + dp(prefs.getInt("y", 0))).toFloat()
        ring.layoutParams = (ring.layoutParams as FrameLayout.LayoutParams).apply {
            width = cut.width(); height = cut.height()
            leftMargin = w / 2 + camX - cut.width() / 2; topMargin = holeY - cut.height() / 2
        }
        guide.translationX = (w / 2 + camX).toFloat()
        // the centre line lights up when the island is centred on the camera
        guide.setBackgroundColor(if (prefs.getInt("x", 0) == 0) BONE else TRACK)
        readout.text = Prefs.sliders.joinToString("   ") { "${it.key} ${signed(prefs.getInt(it.key, 0))}" }
        enable(reset, Prefs.sliders.any { prefs.getInt(it.key, 0) != 0 }) // nothing to reset at the defaults
        renderPreview()
    }

    /** The service's render(), minus everything that only matters on the real screen (keyguard, fullscreen, peeks). */
    private fun renderPreview() {
        handler.removeCallbacks(renderLater)
        val now = System.currentTimeMillis()
        val live = Island.top(now) { Island.demo || prefs.getBoolean(it, true) }
        // with "Show the pill when idle" off the real island is empty at rest; the stage still shows where it would sit,
        // dimmed, so there is something to drag
        val ghost = live == null && !prefs.getBoolean("idle", true) && !Island.calibrating
        preview.show(live, live is Call && live.ringing, true, false)
        preview.alpha = if (ghost) 0.4f else 1f
        stageHint.animate().alpha(if (live == null && !Island.calibrating) 1f else 0f).setDuration(150).start()
        if (!Island.demo) { playing?.isSelected = false; playing = null; playingStyle = null } // the demo ended: no chip is playing
        expiry(live)?.let { handler.postDelayed(renderLater, it - now + 16) }
    }

    // ---- demos ----

    private val tabViews = mutableListOf<TextView>()

    /** The style whose demo is on the island now, and its chip while that tab is showing; outlined until the demo ends. */
    private var playingStyle: String? = null
    private var playing: View? = null

    // only the island moves: the chips swap in place
    private fun showTab(i: Int, chips: Flow) {
        tab = i
        tabViews.forEachIndexed { j, t -> t.isSelected = j == i }
        chips.removeAllViews()
        playing = null
        for ((style, label) in Demo.groups[i].second) chips.addView(pillButton(label) { play(style, it) }.apply {
            // back on this tab mid-demo: its chip is still the one playing
            if (style == playingStyle) { isSelected = true; playing = this }
        }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun play(style: String, chip: View) {
        Demo.show(this, style)
        playing?.isSelected = false
        playingStyle = style
        playing = chip.apply { isSelected = true }
        // it still plays on the stage, but say why the real island stays empty
        if (Island.onChange == null) demoHint.visibility = View.VISIBLE
    }

    /** One tab of the segmented control: a bone segment when selected, the track showing through otherwise. */
    private fun segment(label: String, onClick: () -> Unit) = text(label, 14f, PUMICE, 600).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf())
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8)) // a large font size wraps the label onto two lines instead of cutting it
        setTextColor(ColorStateList(states, intArrayOf(INK, PUMICE)))
        val shape = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); color = ColorStateList(states, intArrayOf(BONE, Color.TRANSPARENT)) }
        background = ripple(shape)
        isClickable = true
        setOnClickListener { onClick() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name // TalkBack: "Calls, selected, button"
            }
        }
        tabViews += this
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun copy(cmd: String) =
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb command", cmd))

    /** The app's own page in Settings; the generic list if this build has no such page or keeps it to system apps. */
    private fun open(detail: Intent, fallback: String) =
        try { startActivity(detail) } catch (e: ActivityNotFoundException) { startActivity(Intent(fallback)) }
        catch (e: SecurityException) { startActivity(Intent(fallback)) } // ACCESSIBILITY_DETAILS_SETTINGS needs a system permission

    private fun enable(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.4f }

    /** Filled green check and what it now does when granted; numbered ring and what to do when not. */
    private fun mark(i: Int, done: Boolean) {
        val s = steps[i]
        s.done = done
        val ring = s.dot.background as GradientDrawable
        if (done) {
            ring.setColor(GREEN); ring.setStroke(0, 0)
            s.dot.setImageResource(R.drawable.ic_check); s.dot.imageTintList = ColorStateList.valueOf(INK)
            s.action.text = "On"; s.action.setTextColor(PUMICE)
        } else {
            ring.setColor(Color.TRANSPARENT); ring.setStroke(dp(2), PUMICE)
            s.dot.setImageDrawable(null)
            s.action.text = s.todo; s.action.setTextColor(BONE)
        }
        (s.dot.parent as FrameLayout).getChildAt(1).visibility = if (done) View.GONE else View.VISIBLE
        s.sub.text = if (done) s.doneSub else s.todoSub
    }

    private fun weight(w: Int) = Typeface.create(Typeface.DEFAULT, w, false)

    private fun text(s: String, size: Float, color: Int, w: Int = 400) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = weight(w)
        setLineSpacing(0f, 1.15f)
    }

    private fun heading(s: String) =
        text(s, 17f, BONE, 600).apply { setPadding(dp(16), dp(36), dp(16), dp(10)); isAccessibilityHeading = true }

    private fun ripple(shape: GradientDrawable? = null) =
        RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), shape, shape ?: GradientDrawable().apply { setColor(INK) })

    /** A rounded group of rows with inset hairlines: each group is a small island. */
    private fun group(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(BASALT); cornerRadius = dp(24).toFloat() }
        clipToOutline = true
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(SEAM) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(16) })
            addView(r)
        }
    }

    private fun row(onClick: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = ripple()
        setOnClickListener { onClick() }
    }

    private fun step(n: Int, title: String, sub: String, todo: String = "Allow", doneSub: String = sub, onClick: () -> Unit): View {
        val dot = ImageView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val num = text("$n", 13f, PUMICE, 600).apply { gravity = Gravity.CENTER }
        val action = text(todo, 15f, BONE, 600)
        val subView = text(sub, 13f, PUMICE)
        steps += Step(dot, action, todo, subView, sub, doneSub)
        return row(onClick).apply {
            addView(FrameLayout(context).apply {
                addView(dot, FrameLayout.LayoutParams(dp(28), dp(28)))
                addView(num, FrameLayout.LayoutParams(dp(28), dp(28)))
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f, BONE, 500))
                addView(subView)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(action)
        }
    }

    private fun actionRow(title: String, action: String, onClick: () -> Unit) = row(onClick).apply {
        addView(text(title, 16f, BONE), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(action, 15f, BONE, 600))
    }

    /** Monochrome switch: a white track when on, a grey one when off, black thumb either way, like the island over a bright screen. */
    private fun switchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; textSize = 16f; setTextColor(BONE); typeface = weight(400)
        isChecked = on
        minHeight = dp(56)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = ripple()
        // own drawables: the DeviceDefault track bakes in its own alpha, so tinting it came out grey
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        trackDrawable = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat(); setSize(dp(52), dp(32))
            color = ColorStateList(states, intArrayOf(BONE, TRACK))
        }
        // black on both tracks: a grey thumb would sink into the lighter off track
        thumbDrawable = InsetDrawable(GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setSize(dp(24), dp(24)); setColor(INK)
        }, dp(4))
        thumbTintList = null; trackTintList = null
        switchMinWidth = dp(52)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun sliderRow(s: Prefs.Slider, value: Int, onChange: (Int) -> Unit): LinearLayout {
        fun fmt(v: Int) = if (v > 0) "+$v dp" else "$v dp"
        val readout = text(fmt(value), 15f, PUMICE, 500).apply { fontFeatureSettings = "tnum" }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
            clipToPadding = false // the bar reaches into this padding so its thumb is whole at either end
            addView(LinearLayout(context).apply {
                addView(text(s.label, 16f, BONE), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(readout)
            })
            addView(SeekBar(context).apply {
                seekBars[s.key] = this // so a drag on the stage, or Reset, moves it too
                min = s.min; max = s.max; progress = value
                contentDescription = s.label
                stateDescription = readout.text // TalkBack reads "+4 dp", not a percentage of the range
                progressTintList = ColorStateList.valueOf(BONE)
                progressBackgroundTintList = ColorStateList.valueOf(PUMICE) // the framework track draws at ~30% alpha, so TRACK vanished
                thumbTintList = ColorStateList.valueOf(BONE)
                // room for the thumb at either end; the negative margins below keep the track in line with the labels
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                        readout.text = fmt(p); b.stateDescription = readout.text
                        if (user) onChange(p)
                    }
                    // the service shows the idle pill while dragging, even with "idle" off, so there is something to line up
                    override fun onStartTrackingTouch(b: SeekBar) { Island.calibrating = true }
                    override fun onStopTrackingTouch(b: SeekBar) { Island.calibrating = false }
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = -dp(12); marginEnd = -dp(12)
            })
        }
    }

    /** Pill-shaped chip in the group surface colour: the island's shape. Selected (playing): seam fill, bone outline. */
    private fun pillButton(label: String, onClick: (View) -> Unit) = text(label, 15f, BONE, 500).apply {
        val states = arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf())
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            color = ColorStateList(states, intArrayOf(SEAM, BASALT))
            setStroke(max(1, dp(1)) * 3 / 2, ColorStateList(states, intArrayOf(BONE, Color.TRANSPARENT)))
        }
        background = ripple(shape)
        isClickable = true
        setOnClickListener { onClick(it) }
        accessibilityDelegate = object : View.AccessibilityDelegate() { // View has no setter for the class name TalkBack reads as the role
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
            }
        }
    }

    private var fullscreen = false

    /** Hides the status bar like a video player would, so the island's fullscreen hiding can be checked. */
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        testAction.text = if (fullscreen) "Stop" else "Try"
        val c = window.insetsController ?: return
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) c.hide(WindowInsets.Type.statusBars()) else c.show(WindowInsets.Type.statusBars())
    }
}

/** Lays chips out in rows that wrap like words. */
private class Flow(ctx: Context, private val gap: Int) : ViewGroup(ctx) {
    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = MeasureSpec.getSize(ws)
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            measureChild(c, ws, hs)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + gap; rowH = 0 }
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
        setMeasuredDimension(maxW, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > r - l) { x = 0; y += rowH + gap; rowH = 0 }
            // mirrored in right-to-left layouts, so the first chip sits at the reading start
            val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) r - l - x - c.measuredWidth else x
            c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
    }
}

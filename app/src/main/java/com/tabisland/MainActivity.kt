package com.tabisland

import android.Manifest
import android.animation.ValueAnimator
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
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
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

class MainActivity : Activity() {
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private class Step(val dot: ImageView, val action: TextView, val todo: String, var done: Boolean? = null)
    private val steps = mutableListOf<Step>()
    private lateinit var status: TextView
    private lateinit var restrictedHint: View
    private lateinit var demos: View
    private lateinit var demoHint: TextView
    private lateinit var testAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(text("Tab Island", 34f, BONE, 600).apply { letterSpacing = -0.02f; setPadding(dp(16), 0, dp(16), 0); isAccessibilityHeading = true })
        status = text("", 15f, PUMICE).apply { setPadding(dp(16), dp(6), dp(16), 0) }
        col.addView(status)

        heading(col, "Set up")
        col.addView(group(
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
            step(3, "Replace system pop-ups", "Optional. Run one adb command from a computer", "Copy") {
                if (steps[2].done != true) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb command", GRANT))
                    steps[2].action.text = "Copied"
                }
            },
        ))
        col.addView(text(
            "Before uninstalling, turn the island off first, or system pop-ups stay off. To undo: adb shell settings put global heads_up_notifications_enabled 1",
            13f, PUMICE,
        ).apply { setPadding(dp(16), dp(10), dp(16), 0) })
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
        col.addView(restrictedHint)
        heading(col, "Try each style")
        col.addView(text("Each one plays on the island for a few seconds.", 13f, PUMICE).apply { setPadding(dp(16), 0, dp(16), dp(12)) })
        // two-column grid of pills: 11 full-width rows would push the switches a screen further down
        demos = GridLayout(this).apply {
            columnCount = 2
            for ((style, label) in Demo.styles) addView(pillButton(label) {
                // without the service nothing would appear, so say why instead of doing nothing
                if (Island.onChange == null) demoHint.visibility = View.VISIBLE else Demo.show(this@MainActivity, style)
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; height = dp(48); setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        col.addView(demos, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = -dp(4); marginEnd = -dp(4)
        })
        demoHint = text("Turn on step 1 first.", 13f, BONE).apply {
            setPadding(dp(16), dp(8), dp(16), 0); visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        col.addView(demoHint)

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
            heading(col, title)
            val rows = items.map { (key, label) ->
                switchRow(label, prefs.getBoolean(key, true)) { prefs.edit().putBoolean(key, it).apply(); deps() }.also { switches[key] = it }
            }
            val extra = if (title == "Behaviour") listOf(test) else emptyList()
            col.addView(group(*(rows + extra).toTypedArray()))
        }
        deps()

        heading(col, "Fit to your camera")
        col.addView(text("The island moves as you drag.", 13f, PUMICE).apply { setPadding(dp(16), 0, dp(16), dp(10)) })
        val sliders = Prefs.sliders.map { s -> sliderRow(s, prefs.getInt(s.key, 0)) { prefs.edit().putInt(s.key, it).apply() } }
        col.addView(group(*sliders.toTypedArray(), actionRow("Position and size", "Reset") {
            prefs.edit().apply { Prefs.sliders.forEach { putInt(it.key, 0) } }.apply()
            sliders.forEach { (it.getChildAt(1) as SeekBar).progress = 0 }
        }))

        setContentView(ScrollView(this).apply {
            id = 1 // fixed id: a ScrollView only keeps its scroll position across recreation when it has one
            setBackgroundColor(INK)
            isVerticalScrollBarEnabled = false
            addView(col)
            clipToPadding = true // content stops at the status bar instead of scrolling under the clock and the island
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
        if (!isChangingConfigurations) Demo.clear() // a rotation shouldn't cut a demo short
        super.onDestroy()
    }

    override fun onPause() {
        Island.calibrating = false // a slider touch can end without onStopTrackingTouch when the app leaves
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
        restrictedHint.visibility = if (a11y && notif) View.GONE else View.VISIBLE
        demos.alpha = if (running) 1f else 0.4f
        if (running) demoHint.visibility = View.GONE
    }

    /** The app's own page in Settings; the generic list if this build has no such page or keeps it to system apps. */
    private fun open(detail: Intent, fallback: String) =
        try { startActivity(detail) } catch (e: ActivityNotFoundException) { startActivity(Intent(fallback)) }
        catch (e: SecurityException) { startActivity(Intent(fallback)) } // ACCESSIBILITY_DETAILS_SETTINGS needs a system permission

    private fun enable(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.4f }

    /** Filled green check when granted, numbered ring when not. Pops only on a fresh grant, never on first bind. */
    private fun mark(i: Int, done: Boolean) {
        val s = steps[i]
        val fresh = s.done == false && done
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
        if (fresh && ValueAnimator.areAnimatorsEnabled()) {
            s.dot.scaleX = 0.6f; s.dot.scaleY = 0.6f
            s.dot.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(2.5f)).start()
        }
    }

    private fun weight(w: Int) = Typeface.create(Typeface.DEFAULT, w, false)

    private fun text(s: String, size: Float, color: Int, w: Int = 400) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = weight(w)
        setLineSpacing(0f, 1.15f)
    }

    private fun heading(col: LinearLayout, s: String) =
        col.addView(text(s, 17f, BONE, 600).apply { setPadding(dp(16), dp(36), dp(16), dp(10)); isAccessibilityHeading = true })

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

    private fun step(n: Int, title: String, sub: String, todo: String = "Allow", onClick: () -> Unit): View {
        val dot = ImageView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val num = text("$n", 13f, PUMICE, 600).apply { gravity = Gravity.CENTER }
        val action = text(todo, 15f, BONE, 600)
        steps += Step(dot, action, todo)
        return row(onClick).apply {
            addView(FrameLayout(context).apply {
                addView(dot, FrameLayout.LayoutParams(dp(28), dp(28)))
                addView(num, FrameLayout.LayoutParams(dp(28), dp(28)))
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 16f, BONE, 500))
                addView(text(sub, 13f, PUMICE))
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
            addView(LinearLayout(context).apply {
                addView(text(s.label, 16f, BONE), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(readout)
            })
            addView(SeekBar(context).apply {
                min = s.min; max = s.max; progress = value
                contentDescription = s.label
                stateDescription = readout.text // TalkBack reads "+4 dp", not a percentage of the range
                progressTintList = ColorStateList.valueOf(BONE)
                progressBackgroundTintList = ColorStateList.valueOf(PUMICE) // the framework track draws at ~30% alpha, so TRACK vanished
                thumbTintList = ColorStateList.valueOf(BONE)
                setPadding(0, dp(12), 0, dp(12))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                        readout.text = fmt(p); b.stateDescription = readout.text
                        if (user) onChange(p)
                    }
                    // the service shows the idle pill while dragging, even with "idle" off, so there is something to line up
                    override fun onStartTrackingTouch(b: SeekBar) { Island.calibrating = true }
                    override fun onStopTrackingTouch(b: SeekBar) { Island.calibrating = false }
                })
            })
        }
    }

    /** Pill-shaped button in the group surface colour: the island's shape. */
    private fun pillButton(label: String, onClick: () -> Unit) = text(label, 15f, BONE, 500).apply {
        gravity = Gravity.CENTER
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(12), 0, dp(12), 0)
        val shape = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(BASALT) }
        background = ripple(shape)
        isClickable = true
        setOnClickListener { onClick() }
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

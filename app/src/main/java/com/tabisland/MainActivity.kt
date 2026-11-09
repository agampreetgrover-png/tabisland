package com.tabisland

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.OvershootInterpolator
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
            "call" to "Calls",
            "notif" to "Notifications",
            "timer" to "Timers and stopwatch",
        ),
        "System events" to listOf(
            "charge" to "Charging",
            "battery" to "Low battery (20%)",
            "silent" to "Silent, vibrate and ring",
            "unlock" to "Unlock",
            "earbuds" to "Earbuds and their battery",
        ),
        "Behaviour" to listOf(
            "idle" to "Show the pill when nothing is happening",
            "fullscreen" to "Hide in fullscreen apps",
            "haptics" to "Haptics",
        ),
    )

    class Slider(val key: String, val label: String, val min: Int, val max: Int)

    val sliders = listOf(
        Slider("x", "Left and right", -40, 40),
        Slider("y", "Up and down", -20, 20),
        Slider("w", "Idle pill width", -10, 40),
        Slider("h", "Pill height", -8, 16),
    )
}

// The island's own material: true black, with greys that keep ≥4.5:1 on the group surface.
private const val INK = 0xFF000000.toInt()
private const val BASALT = 0xFF161618.toInt()
private const val SEAM = 0xFF2A2A2E.toInt()
private const val PUMICE = 0xFF9E9EA6.toInt()
private const val BONE = 0xFFF4F4F1.toInt()

class MainActivity : Activity() {
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    private class Step(val dot: ImageView, val action: TextView, var done: Boolean? = null)
    private val steps = mutableListOf<Step>()
    private lateinit var status: TextView
    private lateinit var restrictedHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(text("Tab Island", 34f, BONE, 600).apply { letterSpacing = -0.02f })
        status = text("", 15f, PUMICE).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(status)

        heading(col, "Set up")
        col.addView(group(
            step(1, "Show the island", "Accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            step(2, "Music, calls and notifications", "Notification access") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            step(3, "Earbuds", "Nearby devices permission") { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1) },
        ))
        restrictedHint = text(
            "If a switch is greyed out in Settings, open App info, tap ⋮, choose Allow restricted settings, then try again.",
            13f, PUMICE,
        ).apply { setPadding(dp(16), dp(10), dp(16), 0) }
        col.addView(restrictedHint)
        col.addView(pillButton("Preview the island") {
            Island.flash = Flash("preview", "Charging", "80%", icon = tinted(R.drawable.ic_bolt, GREEN), accent = GREEN,
                level = 80, until = System.currentTimeMillis() + 3000)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })

        for ((title, switches) in Prefs.groups) {
            heading(col, title)
            val rows = switches.map { (key, label) -> switchRow(label, prefs.getBoolean(key, true)) { prefs.edit().putBoolean(key, it).apply() } }
            val extra = if (title == "Behaviour") listOf(actionRow("Test fullscreen hiding", "Try") { toggleFullscreen() }) else emptyList()
            col.addView(group(*(rows + extra).toTypedArray()))
        }

        heading(col, "Fit to your camera")
        col.addView(text("The island moves as you drag. Values are in dp.", 13f, PUMICE).apply { setPadding(dp(16), 0, dp(16), dp(10)) })
        col.addView(group(*Prefs.sliders.map { s ->
            sliderRow(s, prefs.getInt(s.key, 0)) { prefs.edit().putInt(s.key, it).apply() }
        }.toTypedArray()))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(INK)
            isVerticalScrollBarEnabled = false
            addView(col)
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                col.setPadding(dp(16) + bars.left, bars.top + dp(28), dp(16) + bars.right, bars.bottom + dp(32))
                insets
            }
        })
        if (intent.getBooleanExtra("fullscreen", false)) toggleFullscreen()
    }

    override fun onResume() {
        super.onResume()
        val a11y = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true
        val notif = getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(this, MediaListener::class.java))
        val bt = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        listOf(a11y, notif, bt).forEachIndexed { i, done -> mark(i, done) }
        status.text = when {
            !a11y -> "The island is off. Start with step 1."
            !notif || !bt -> "The island is on. Finish setup to show music, calls and earbuds."
            else -> "The island is on."
        }
        restrictedHint.visibility = if (a11y && notif) View.GONE else View.VISIBLE
    }

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
            ring.setColor(Color.TRANSPARENT); ring.setStroke(dp(2), SEAM)
            s.dot.setImageDrawable(null)
            s.action.text = "Allow"; s.action.setTextColor(BONE)
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
        col.addView(text(s, 17f, BONE, 600).apply { setPadding(dp(16), dp(36), dp(16), dp(10)) })

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

    private fun step(n: Int, title: String, sub: String, onClick: () -> Unit): View {
        val dot = ImageView(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        val num = text("$n", 13f, PUMICE, 600).apply { gravity = Gravity.CENTER }
        val action = text("Allow", 15f, BONE, 600)
        steps += Step(dot, action)
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

    /** Monochrome switch: a white track with a black thumb when on, like the island over a bright screen. */
    private fun switchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; textSize = 16f; setTextColor(BONE); typeface = weight(400)
        isChecked = on
        minHeight = dp(56)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = ripple()
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(INK, PUMICE))
        trackTintList = ColorStateList(states, intArrayOf(BONE, SEAM))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun sliderRow(s: Prefs.Slider, value: Int, onChange: (Int) -> Unit): View {
        fun fmt(v: Int) = if (v > 0) "+$v" else "$v"
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
                progressTintList = ColorStateList.valueOf(BONE)
                progressBackgroundTintList = ColorStateList.valueOf(SEAM)
                thumbTintList = ColorStateList.valueOf(BONE)
                setPadding(0, dp(12), 0, dp(12))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                        readout.text = fmt(p)
                        if (user) onChange(p)
                    }
                    override fun onStartTrackingTouch(b: SeekBar) {}
                    override fun onStopTrackingTouch(b: SeekBar) {}
                })
            })
        }
    }

    /** Outlined pill: the island's shape, used for the one secondary action. */
    private fun pillButton(label: String, onClick: () -> Unit) = text(label, 16f, BONE, 600).apply {
        gravity = Gravity.CENTER
        val shape = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setStroke(dp(1), SEAM); setColor(INK) }
        background = ripple(shape)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private var fullscreen = false

    /** Hides the status bar like a video player would, so the island's fullscreen hiding can be checked. */
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val c = window.insetsController ?: return
        c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) c.hide(WindowInsets.Type.statusBars()) else c.show(WindowInsets.Type.statusBars())
    }
}

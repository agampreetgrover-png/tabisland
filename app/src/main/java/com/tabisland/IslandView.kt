package com.tabisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.session.MediaController
import android.os.SystemClock
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val GRAY = 0xFF9A9A9F.toInt()
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

// Damped spring: ~11% overshoot, settled by t = 1.
private val SPRING = TimeInterpolator { t -> 1f - exp(-7f * t) * cos(10f * t) }

/** The black pill. Lives in a window the service sizes via [Host.window]; morphs between idle, compact and cards. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class IslandView(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        fun window(w: Int, h: Int, touchable: Boolean)
        fun tap()
        fun longPress()
        fun swipeUp()
        fun media(action: (MediaController.TransportControls) -> Unit)
        fun send(pi: PendingIntent?)
    }

    var haptics = true
    private var pillH = dp(34)
    private var gapW = dp(34)
    private var idleW = dp(34)
    private var maxW = dp(360)

    private val bg = GradientDrawable().apply { setColor(Color.BLACK) }
    private val pill = FrameLayout(ctx).apply { background = bg; clipToOutline = true }

    // compact: [icon text] [camera gap] [text | waveform | chronometer]
    private val leftIcon = roundImage(dp(6))
    private val leftText = label(13f)
    private val rightText = label(13f).apply { setTypeface(null, Typeface.BOLD) }
    private val wave = Waveform(ctx)
    private val chrono = Chronometer(ctx).apply { textSize = 13f; setTypeface(null, Typeface.BOLD); fontFeatureSettings = "tnum" }
    private val gap = Space(ctx)
    private val compact = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(leftIcon, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) })
            addView(leftText)
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
        addView(gap, LinearLayout.LayoutParams(gapW, 0))
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(rightText)
            addView(wave, LinearLayout.LayoutParams(dp(20), dp(14)))
            addView(chrono)
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
    }

    // expanded media card
    private var media: Media? = null
    private var seeking = false
    private val art = roundImage(dp(12))
    private val title = label(15f).apply { setTypeface(null, Typeface.BOLD) }
    private val artist = label(13f).apply { setTextColor(GRAY) }
    private val seek = SeekBar(ctx).apply {
        progressTintList = ColorStateList.valueOf(WHITE)
        thumbTintList = ColorStateList.valueOf(WHITE)
        progressBackgroundTintList = ColorStateList.valueOf(GRAY)
    }
    private val play = mediaButton(android.R.drawable.ic_media_play, "Play") {
        val playing = media?.playing == true
        host.media { if (playing) it.pause() else it.play() }
    }
    private val mediaCard = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(8))
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(art, LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(column(title, artist), LinearLayout.LayoutParams(0, WRAP, 1f))
        })
        addView(seek, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER
            addView(mediaButton(android.R.drawable.ic_media_previous, "Previous") { host.media { it.skipToPrevious() } }, square(48))
            addView(play, square(56))
            addView(mediaButton(android.R.drawable.ic_media_next, "Next") { host.media { it.skipToNext() } }, square(48))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    // incoming call card
    private val callIcon = roundImage(dp(22))
    private val callName = label(15f).apply { setTypeface(null, Typeface.BOLD) }
    private val callText = label(13f).apply { setTextColor(GRAY) }
    private val callButtons = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
    private val callCard = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(14), 0)
        addView(callIcon, square(44))
        addView(column(callName, callText), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(callButtons)
    }

    // notification card
    private val notifIcon = roundImage(dp(10))
    private val notifTitle = label(14f).apply { setTypeface(null, Typeface.BOLD) }
    private val notifText = label(13f).apply { maxLines = 2; setTextColor(0xFFDDDDDD.toInt()) }
    private val notifCard = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        addView(notifIcon, square(40))
        addView(column(notifTitle, notifText), LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private var content: View? = null
    private var curW = 0
    private var curH = 0
    private var toW = -1
    private var toH = -1
    private var anim: ValueAnimator? = null

    private val ticker = object : Runnable {
        override fun run() {
            val m = media ?: return
            if (content !== mediaCard || !isAttachedToWindow) return
            if (!seeking) seek.progress = m.positionNow(SystemClock.elapsedRealtime()).toInt()
            postDelayed(this, 500)
        }
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { host.tap(); return true }
        override fun onLongPress(e: MotionEvent) {
            if (haptics) pill.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            host.longPress()
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (vy < -dp(400)) host.swipeUp()
            return true
        }
    })

    init {
        addView(pill, LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        for (v in listOf(compact, mediaCard, callCard, notifCard)) {
            v.visibility = GONE
            pill.addView(v, LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        pill.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        pill.setOnClickListener { host.tap() } // TalkBack activation; touches go through the gesture detector
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                seeking = false
                val to = s.progress.toLong()
                host.media { it.seekTo(to) }
            }
        })
    }

    /** Sizes measured from the camera cutout by the service. */
    fun geometry(pillH: Int, gapW: Int, idleW: Int, maxW: Int) {
        this.pillH = pillH; this.gapW = gapW; this.idleW = idleW; this.maxW = maxW
        gap.layoutParams = gap.layoutParams.apply { width = gapW }
        val inset = max((pillH - dp(22)) / 2, dp(6)) // keeps the icon concentric with the pill's round end
        compact.setPadding(inset, 0, inset, 0)
        toW = -1
    }

    fun show(live: Live?, expanded: Boolean, idleOn: Boolean, hidden: Boolean) {
        if (hidden) {
            val old = anim; anim = null; old?.cancel()
            pill.visibility = GONE
            size(0, 0); toW = -1
            host.window(1, 1, false)
            return
        }
        pill.visibility = VISIBLE
        media = live as? Media
        val (v, w, h) = bind(live, expanded, idleOn)
        if (v != null) v.layoutParams = (v.layoutParams as LayoutParams).apply { width = w; height = h }
        swap(v)
        pill.contentDescription = describe(live)
        morph(w, h, live != null)
        wave.invalidate()
        if (v === mediaCard) { removeCallbacks(ticker); post(ticker) }
    }

    private fun bind(l: Live?, expanded: Boolean, idleOn: Boolean): Triple<View?, Int, Int> = when {
        l == null -> if (idleOn) Triple(null, idleW, pillH) else Triple(null, 0, 0)
        l is Media && expanded -> { bindMedia(l); Triple(mediaCard, maxW, dp(184)) }
        l is Call && l.since == null -> { bindCall(l); Triple(callCard, maxW, dp(84)) }
        l is Flash && l.key == "notif" -> { bindNotif(l); Triple(notifCard, maxW, dp(76)) }
        else -> Triple(compact, gapW + 2 * bindCompact(l), pillH)
    }

    /** Fills the compact row and returns the width of one side (left and right are equal so the camera stays centred). */
    private fun bindCompact(l: Live): Int {
        var icon: Drawable? = null
        var left = ""
        var right = ""
        var accent = WHITE
        wave.visibility = GONE
        chrono.stop(); chrono.visibility = GONE
        when (l) {
            is Media -> {
                icon = l.art?.let { BitmapDrawable(resources, it) }
                wave.visibility = VISIBLE; wave.playing = l.playing; wave.color = tint(l.art)
            }
            is Timer -> { icon = l.icon; if (l.base != null) tick(l.base, l.countDown, ORANGE) else { right = l.label; accent = ORANGE } }
            is Call -> { icon = l.icon; tick(l.since ?: SystemClock.elapsedRealtime(), false, GREEN) }
            is Flash -> { icon = l.icon; left = listOf(l.glyph, l.title).filter { it.isNotEmpty() }.joinToString(" "); right = l.text; accent = l.accent }
        }
        leftIcon.setImageDrawable(icon)
        leftIcon.visibility = if (icon != null) VISIBLE else GONE
        leftText.text = left
        leftText.visibility = if (left.isEmpty()) GONE else VISIBLE
        rightText.text = right
        rightText.setTextColor(accent)
        rightText.visibility = if (right.isEmpty()) GONE else VISIBLE
        val lw = (if (icon != null) dp(28) else 0) + leftText.paint.measureText(left).toInt()
        val rw = when {
            wave.visibility == VISIBLE -> dp(20)
            chrono.visibility == VISIBLE -> chrono.paint.measureText(chrono.text.toString()).toInt() + dp(4)
            else -> rightText.paint.measureText(right).toInt()
        }
        return (max(lw, rw) + compact.paddingLeft + dp(4)).coerceIn(pillH / 2, max(pillH / 2, (maxW - gapW) / 2))
    }

    private fun tick(base: Long, countDown: Boolean, color: Int) {
        chrono.base = base
        chrono.isCountDown = countDown
        chrono.setTextColor(color)
        chrono.visibility = VISIBLE
        chrono.start()
    }

    private fun bindMedia(m: Media) {
        art.setImageBitmap(m.art)
        title.text = m.title
        artist.text = m.artist
        play.setImageResource(if (m.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        play.contentDescription = if (m.playing) "Pause" else "Play"
        seek.max = m.duration.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        seek.visibility = if (m.duration > 0) VISIBLE else INVISIBLE // live streams have no duration
    }

    private fun bindCall(c: Call) {
        callIcon.setImageDrawable(c.icon)
        callName.text = c.name
        callText.text = c.text
        callButtons.removeAllViews()
        c.actions.forEachIndexed { i, a ->
            val answer = c.actions.size > 1 && i == c.actions.lastIndex // CallStyle orders decline first, answer last
            callButtons.addView(TextView(context).apply {
                text = a.title
                textSize = 13f
                setTextColor(WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (answer) GREEN else RED) }
                setOnClickListener { host.send(a.actionIntent) }
            }, LinearLayout.LayoutParams(WRAP, dp(36)).apply { marginStart = dp(8) })
        }
    }

    private fun bindNotif(f: Flash) {
        notifIcon.setImageDrawable(f.icon)
        notifIcon.visibility = if (f.icon != null) VISIBLE else GONE
        notifTitle.text = f.title
        notifText.text = f.text
    }

    private fun describe(l: Live?): String? = when (l) {
        is Media -> "${l.title}, ${l.artist}"
        is Call -> "Call, ${l.name}"
        is Timer -> l.label
        is Flash -> listOf(l.title, l.text).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { l.glyph }
        null -> null
    }

    private fun swap(v: View?) {
        if (v === content) return
        content?.let { it.animate().cancel(); it.visibility = GONE }
        content = v
        v ?: return
        v.visibility = VISIBLE
        v.alpha = 0f
        v.animate().alpha(1f).setStartDelay(120).setDuration(180).start()
    }

    private fun morph(w: Int, h: Int, touchable: Boolean) {
        if (w == toW && h == toH) {
            if (anim?.isRunning != true) host.window(w, h, touchable)
            return
        }
        if (haptics && toW >= 0 && w.toLong() * h > toW.toLong() * toH) pill.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        toW = w; toH = h
        val fw = curW
        val fh = curH
        // window covers start, end and the spring's overshoot; shrinks to the target when the spring settles
        host.window(max(fw, w) + abs(w - fw) / 8, max(fh, h) + abs(h - fh) / 8, touchable)
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 550
            interpolator = SPRING
            addUpdateListener {
                val f = it.animatedValue as Float
                size(fw + ((w - fw) * f).roundToInt(), fh + ((h - fh) * f).roundToInt())
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(x: Animator) {
                    if (x === anim) { size(w, h); host.window(w, h, touchable) }
                }
            })
        }
        val old = anim
        anim = a // set before cancel: cancel fires onAnimationEnd, which must not apply the old target
        old?.cancel()
        a.start()
    }

    private fun size(w: Int, h: Int) {
        curW = max(w, 0); curH = max(h, 0)
        pill.layoutParams = (pill.layoutParams as LayoutParams).apply { width = curW; height = curH }
        bg.cornerRadius = min(curH / 2f, dp(40).toFloat())
        pill.invalidateOutline()
    }

    /** Average album-art colour, brightened, for the waveform. */
    private fun tint(b: Bitmap?): Int {
        b ?: return WHITE
        val c = try { Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0) } catch (e: RuntimeException) { return WHITE }
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        hsv[2] = max(hsv[2], 0.85f)
        return Color.HSVToColor(hsv)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun square(s: Int) = LinearLayout.LayoutParams(dp(s), dp(s))

    private fun label(size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun column(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, 0, 0)
        views.forEach { addView(it) }
    }

    private fun roundImage(radius: Int) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(0xFF2C2C2E.toInt()) }
        clipToOutline = true
    }

    private fun mediaButton(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(WHITE)
        background = null
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        contentDescription = desc
        setOnClickListener { onClick() }
    }
}

/** Four bars that bounce while music plays; flat when paused. Only animates while on screen. */
private class Waveform(ctx: Context) : View(ctx) {
    var playing = false
        set(v) { field = v; invalidate() }
    var color = WHITE
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    override fun onDraw(c: Canvas) {
        val n = 4
        val bar = width / (2f * n - 1)
        paint.strokeWidth = bar
        paint.color = color
        val t = SystemClock.uptimeMillis() / 1000f
        val room = height - bar
        for (i in 0 until n) {
            val amp = if (playing) 0.25f + 0.75f * abs(sin(t * (4f + i * 1.3f) + i * 1.7f)) else 0.15f
            val x = bar / 2 + i * 2 * bar
            val half = room * amp / 2
            c.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
        if (playing && isShown) postInvalidateOnAnimation()
    }
}

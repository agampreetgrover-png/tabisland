package com.tabisland

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.session.MediaController
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// secondary copy: translucent white, so on a tinted card it takes the card's hue instead of sitting on it as grey
private const val MUTED = 0xB3FFFFFF.toInt()
private const val TRACK = 0x33FFFFFF
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private val EASE_OUT = PathInterpolator(0.2f, 0.9f, 0.3f, 1f)

// Pixel's system face; Typeface.create falls back to Roboto where it doesn't exist.
private val BASE: Typeface = Typeface.create("google-sans", Typeface.NORMAL)
private fun font(weight: Int) = Typeface.create(BASE, weight, false)

fun Context.tinted(res: Int, color: Int): Drawable = getDrawable(res)!!.mutate().apply { setTint(color) }

/**
 * The island. Size is driven by two springs (bouncy when growing out of the camera, critically damped when
 * shrinking back), content materializes with scale + blur, and the window is sized by the service via [Host.window].
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class IslandView(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        /** Where taps should be caught: a [w]×[h] pill centred on the camera, or nothing when [on] is false. */
        fun touch(w: Int, h: Int, on: Boolean)
        /** How tall the drawing window must be. Only the height changes, so nothing ever shifts sideways. */
        fun canvas(h: Int)
        /** A drag on the music island was released past the halfway point: open or close the card. */
        fun expand(open: Boolean)
        fun tap()
        fun longPress()
        fun swipeUp()
        fun media(action: (MediaController.TransportControls) -> Unit)
        fun send(pi: PendingIntent?)
        /** A button on message card [f] was tapped. */
        fun action(f: Flash, a: Notification.Action)
        /** The finger lifted off the pill, after any tap/longPress/swipeUp it made: renders were held until now. */
        fun released()
    }

    var haptics = true
    /** A finger is down on the pill (or a button in it): the service holds renders so nothing changes under it. */
    var held = false
        private set
    private var pillH = dp(34)
    private var gapW = dp(34)
    private var idleW = dp(34)
    private var maxW = dp(360)

    private val bg = GradientDrawable().apply { setColor(Color.BLACK) }
    // no drop shadow: its margin would make a touchable dead zone around cards where taps can't reach the app below
    private val pill: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            track(e)
            val end = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
            if (end) held = false // the gesture's own tap or drag release must render normally
            return super.dispatchTouchEvent(e).also { if (end) host.released() }
        }
        // steal vertical drags from the card's buttons/seek bar so the whole card can be dragged closed, and an
        // upward flick that starts on a message card's buttons so it can still be swiped away
        override fun onInterceptTouchEvent(e: MotionEvent) = e.actionMasked == MotionEvent.ACTION_MOVE && (beginDrag(e) || stealFlick(e))
    }.apply { background = bg; clipToOutline = true }

    // ---- compact: [icon text] [camera gap] [text ring | waveform | chronometer] ----
    private val leftIcon = roundImage(dp(8))
    private val leftText = label(14f, 400)
    private val rightText = label(14f, 600).apply { fontFeatureSettings = "tnum" }
    private val ring = Ring(ctx)
    private val wave = Waveform(ctx)
    private val chrono = Chronometer(ctx).apply { textSize = 14f; typeface = font(600); fontFeatureSettings = "tnum"; maxLines = 1 }
    private var chronoLen = 0
    private var last: (() -> Unit)? = null // the last show(), re-run when the chronometer outgrows its measured width
    private var quiet = false // that re-run: the pill grows by a digit, which is no event to buzz for
    private val gap = Space(ctx)
    private val compactLeft = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(leftIcon, lp(22, 22).apply { marginEnd = dp(8) })
        addView(leftText)
    }
    private val compactRight = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        addView(rightText)
        addView(ring, lp(18, 18).apply { marginStart = dp(8) })
        addView(wave, lp(20, 14))
        addView(chrono)
    }
    private val compact = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(compactLeft, LinearLayout.LayoutParams(0, MATCH, 1f))
        addView(gap, LinearLayout.LayoutParams(gapW, 0))
        addView(compactRight, LinearLayout.LayoutParams(0, MATCH, 1f))
    }

    // ---- expanded media card: [art title/artist waveform], [time ━━━ time], [app ⏮ ⏯ ⏭] ----
    private var media: Media? = null
    private var seeking = false
    private var seekTo: Long? = null
    private var seekAt = 0L
    private var seekAccent = 0
    private val glow = glowView()
    private val scrim = scrimView()
    private val art = roundImage(dp(12))
    private val title = label(17f, 600)
    private val artist = label(15f, 400).apply { setTextColor(MUTED) }
    private val appBadge = roundImage(dp(12)) // circle, like Pixel's launcher icons
    private val cardWave = Waveform(ctx) // same corner as the pill's waveform, so the drag between them stays put
    private val seek = SeekBar(ctx).apply {
        thumb = null
        splitTrack = false
        setPadding(0, 0, 0, 0)
        contentDescription = "Seek"
    }
    private val elapsed = label(12f, 400).apply { setTextColor(MUTED); fontFeatureSettings = "tnum" }
    private val remaining = label(12f, 400).apply { setTextColor(MUTED); fontFeatureSettings = "tnum" }
    private val play = iconButton(R.drawable.ic_play, "Play", 9) {
        val playing = media?.playing == true
        host.media { if (playing) it.pause() else it.play() }
    }
    private val mediaBody = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(art, lp(52, 52))
            addView(column(title, artist), LinearLayout.LayoutParams(0, WRAP, 1f))
            // level with the title, top right, where the pill's waveform was
            addView(cardWave, lp(20, 14).apply { gravity = Gravity.TOP; topMargin = dp(10) })
        })
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(elapsed)
            // a "scrolling container" to AbsSeekBar: it waits for horizontal slop instead of claiming the touch on
            // down, so a vertical drag that starts on the bar can still close the card
            addView(object : FrameLayout(ctx) { override fun shouldDelayChildPressedState() = true }.apply {
                addView(seek, LayoutParams(MATCH, MATCH))
            }, LinearLayout.LayoutParams(0, dp(22), 1f).apply { marginStart = dp(10); marginEnd = dp(10) })
            addView(remaining)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        // the app sits where the reference has its star; an equal space on the right keeps the controls centred
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(appBadge, lp(24, 24))
            addView(Space(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            addView(iconButton(R.drawable.ic_prev, "Previous", 10) { host.media { it.skipToPrevious() } }, lp(64, 48))
            addView(play, lp(64, 48))
            addView(iconButton(R.drawable.ic_next, "Next", 10) { host.media { it.skipToNext() } }, lp(64, 48))
            addView(Space(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            addView(Space(ctx), lp(24, 24))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }
    private val mediaCard = FrameLayout(ctx).apply {
        addView(glow, LayoutParams(MATCH, MATCH))
        addView(scrim, LayoutParams(MATCH, MATCH))
        addView(mediaBody, LayoutParams(MATCH, MATCH))
    }

    // ---- incoming call card ----
    private val callIcon = roundImage(dp(24))
    private val callCaption = label(14f, 400).apply { setTextColor(MUTED) } // iOS: a quiet line above the name
    private val callName = label(21f, 600)
    private val callButtons = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
    private var callSig: List<Pair<String, PendingIntent?>>? = null
    private val callGlow = glowView() // the caller's photo, blurred, like album art behind the music card
    private val callRow = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        clipToPadding = false // the ripple spills into the padding
        // rings ripple out of the photo while it rings; negative margins keep the photo where it was
        addView(FrameLayout(ctx).apply {
            addView(Ripple(ctx).apply { inner = dp(24).toFloat() }, LayoutParams(MATCH, MATCH))
            addView(callIcon, LayoutParams(dp(48), dp(48), Gravity.CENTER))
        // negative margins on all sides: the rings overflow without making the row (and fit()) taller
        }, lp(72, 72).apply { marginStart = -dp(12); marginEnd = -dp(12); topMargin = -dp(10); bottomMargin = -dp(10) })
        addView(column(callCaption, callName), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(callButtons)
    }
    private val callCard = FrameLayout(ctx).apply {
        if (Build.VERSION.SDK_INT >= 35) contentSensitivity = View.CONTENT_SENSITIVITY_SENSITIVE // caller name and photo
        addView(callGlow, LayoutParams(MATCH, MATCH))
        addView(scrimView(), LayoutParams(MATCH, MATCH))
        addView(callRow, LayoutParams(MATCH, MATCH))
    }

    // ---- notification card: [photo sender | camera | app] level with the camera, the message across the width below ----
    private val notifIcon = roundImage(dp(16)) // circle: contact photos and Pixel's round app icons
    private val notifApp = label(13f, 400).apply { setTextColor(MUTED) }
    private val notifTitle = label(17f, 600)
    private val notifText = label(16f, 400).apply { maxLines = 3 }
    // a name too long for the space left of the camera gets its own full-width line instead of being cut
    private val notifTitleLong = label(17f, 600).apply { maxLines = 2; visibility = GONE }
    private var notifTint = 0
    private var notifFor: Flash? = null
    private val notifActions = LinearLayout(ctx).apply { visibility = GONE }
    private val notifGap = Space(ctx) // the camera's width, set in geometry()
    private val notifBand = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(notifIcon, lp(32, 32))
            addView(notifTitle, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(notifGap, LinearLayout.LayoutParams(gapW, 0))
        addView(LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; addView(notifApp) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    private val notifCard = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        // hidden from screen sharing while showing, like the system's own notifications
        if (Build.VERSION.SDK_INT >= 35) contentSensitivity = View.CONTENT_SENSITIVITY_SENSITIVE
        addView(notifBand)
        addView(notifTitleLong, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(notifText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        addView(notifActions, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    // ---- motion state ----
    private var content: View? = null
    private val pw = Spring(0f)
    private val ph = Spring(0f)
    private var bouncy = true
    private var touch = false
    private val blurs = HashMap<View, ValueAnimator>()
    private val clock = TimeAnimator().apply { setTimeListener { _, _, dt -> frame(dt) } }
    private val startClock = Runnable { if (!clock.isStarted) clock.start() }
    private var startAt = 0L // uptime the delayed startClock is due
    private var canvasH = 0

    private val ticker = object : Runnable {
        override fun run() {
            val m = media ?: return
            if (content !== mediaCard || !isAttachedToWindow) return
            showPosition(m)
            postDelayed(this, 500)
        }
    }

    private fun showPosition(m: Media) {
        val now = SystemClock.elapsedRealtime()
        var pos = m.positionNow(now)
        seekTo?.let { t ->
            val goal = t + if (m.playing) ((now - seekAt) * m.speed).toLong() else 0L
            // until the player reports the jump, its stale position would snap the bar back
            if (abs(pos - goal) > 1500 && now - seekAt < 2000) pos = goal else seekTo = null
        }
        if (seeking) pos = seek.progress.toLong()
        pos = pos.coerceIn(0L, max(m.duration, 0L))
        seek.progress = pos.toInt()
        elapsed.text = clockText(pos)
        remaining.text = if (m.duration > 0) "-" + clockText(m.duration - pos) else ""
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
    }).apply { setOnDoubleTapListener(null) } // otherwise a quick second tap is eaten as a double tap

    init {
        addView(pill, LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        for (v in listOf(compact, mediaCard, callCard, notifCard)) {
            v.visibility = GONE
            pill.addView(v, LayoutParams(0, 0, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        pill.setOnTouchListener { _, e ->
            // feedback on touch-down, released on up/cancel
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> pill.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).setInterpolator(EASE_OUT).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pill.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(EASE_OUT).start()
            }
            if (drag == null && e.actionMasked == MotionEvent.ACTION_MOVE) beginDrag(e)
            if (drag != null) { dragTo(e); true } else gestures.onTouchEvent(e)
        }
        pill.setOnClickListener { host.tap() } // TalkBack activation; touches go through the gesture detector
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) elapsed.text = clockText(p.toLong()) }
            override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                seeking = false
                val to = s.progress.toLong()
                seekTo = to; seekAt = SystemClock.elapsedRealtime()
                host.media { it.seekTo(to) }
            }
        })
        chrono.setOnChronometerTickListener {
            // 59:59 -> 1:00:00, or a countdown going negative: the pill must grow to fit. Posted: a show() from inside
            // the tick would restart the chronometer while its own tick is still queuing the next, doubling the ticks.
            if (!held && it.visibility == VISIBLE && it.text.length != chronoLen) {
                chronoLen = it.text.length
                post { quiet = true; try { last?.invoke() } finally { quiet = false } }
            }
        }
    }

    /** Sizes measured from the camera cutout by the service. */
    fun geometry(pillH: Int, gapW: Int, idleW: Int, maxW: Int, dx: Int) {
        this.pillH = pillH; this.gapW = gapW; this.idleW = idleW; this.maxW = maxW
        pill.translationX = dx.toFloat() // camera centre relative to the screen centre
        gap.layoutParams = gap.layoutParams.apply { width = gapW }
        val inset = max((pillH - dp(22)) / 2, dp(6)) // keeps the icon concentric with the pill's round end
        compact.setPadding(inset, 0, inset, 0)
        // concentric with the pill's round end: its radius minus the inset
        (leftIcon.background as GradientDrawable).cornerRadius = max(pillH / 2f - inset, dp(6).toFloat())
        // the art row rides up beside the camera: the art is clear of it, and the title (centred on the art) starts
        // just below the hole; pillH has 5dp of air under the hole
        mediaBody.setPadding(dp(16), max(dp(16), pillH - dp(10)), dp(16), dp(10))
        // the call row is too wide to split around the camera (the name would run under it), so it starts below,
        // taking 4 of those 5dp
        callRow.setPadding(dp(16), max(dp(20), pillH - dp(4)), dp(16), dp(14))
        // the sender sits level with the camera, left of it; a large font size may make the row taller
        notifBand.minimumHeight = pillH
        notifGap.layoutParams = notifGap.layoutParams.apply { width = gapW }
        notifCard.setPadding(dp(16), 0, dp(16), dp(16))
    }

    fun show(live: Live?, expanded: Boolean, idleOn: Boolean, hidden: Boolean) {
        last = { show(live, expanded, idleOn, hidden) }
        if (hidden) {
            removeCallbacks(startClock); startAt = 0
            clock.cancel()
            // a drag cut short by hiding would otherwise make every later show() return early
            drag?.to?.visibility = GONE
            drag = null
            velocity?.recycle(); velocity = null
            pill.visibility = GONE
            content?.visibility = GONE
            content = null
            pw.value = 0f; pw.target = 0f; pw.velocity = 0f
            ph.value = 0f; ph.target = 0f; ph.velocity = 0f
            size(0, 0)
            host.touch(0, 0, false)
            canvas(0)
            return
        }
        if (drag != null) return // the finger owns the island; the release re-renders
        pill.visibility = VISIBLE
        media = live as? Media
        val (v, tw, th) = bind(live, expanded, idleOn)
        if (v != null) sizeContent(v, tw, th)
        val fadingOut = v == null && content != null
        swap(v)
        pill.contentDescription = describe(live)
        touch = live != null
        retarget(tw, th, delayStart = fadingOut)
        wave.invalidate(); cardWave.invalidate()
        if (v === mediaCard) { removeCallbacks(ticker); post(ticker) }
    }

    private fun bind(l: Live?, expanded: Boolean, idleOn: Boolean): Triple<View?, Int, Int> = when {
        l == null -> if (idleOn) Triple(null, idleW, pillH) else Triple(null, 0, 0)
        l is Media && expanded -> { bindMedia(l); Triple(mediaCard, maxW, fit(mediaBody, dp(148))) }
        l is Call && l.ringing && expanded -> { bindCall(l); Triple(callCard, maxW, fit(callRow, dp(52))) }
        l is Flash && l.key == "notif" -> { bindNotif(l); Triple(notifCard, maxW, fit(notifCard, dp(100))) }
        else -> Triple(compact, gapW + 2 * bindCompact(l), pillH)
    }

    /** The design height below the camera band, or taller when a large font size needs it. */
    private fun fit(body: View, h: Int): Int {
        body.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return max(body.paddingTop + h, body.measuredHeight)
    }

    /** Fills the compact row and returns one side's width (both sides equal, so the camera stays centred). */
    private fun bindCompact(l: Live): Int {
        var icon: Drawable? = null
        var left = ""
        var right = ""
        var accent = WHITE
        var level: Int? = null
        wave.visibility = GONE
        chrono.stop(); chrono.visibility = GONE
        when (l) {
            is Media -> {
                icon = l.art?.let { b -> artIcon?.takeIf { it.bitmap === b } ?: BitmapDrawable(resources, b).also { artIcon = it } }
                wave.visibility = VISIBLE; wave.playing = l.playing; wave.color = tint(l.art)
            }
            is Timer -> { icon = l.icon; if (l.base != null) tick(l.base, l.countDown, ORANGE) else { right = l.label; accent = ORANGE } }
            is Call -> {
                icon = l.icon
                when {
                    l.ringing -> { right = "Incoming"; accent = GREEN }
                    l.since == null -> { right = "Calling"; accent = GREEN }
                    else -> tick(l.since, false, GREEN)
                }
            }
            is Flash -> { icon = l.icon; left = l.title; right = l.text; accent = l.accent; level = l.level }
            is Ongoing -> { icon = l.icon; left = l.title; right = l.text; accent = l.accent; level = l.level }
        }
        val ringWasShown = ring.visibility == VISIBLE
        // the unlock padlock is the whole show, so it gets a bigger slot and a tick as it opens
        val iconSize = if (icon is UnlockDrawable) 28 else 22
        if (leftIcon.layoutParams.width != dp(iconSize)) leftIcon.layoutParams = lp(iconSize, iconSize).apply { marginEnd = dp(8) }
        if (icon is UnlockDrawable) icon.onOpen = { if (haptics) pill.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
        leftIcon.setImageDrawable(icon)
        leftIcon.visibility = if (icon != null) VISIBLE else GONE
        // the dark placeholder tile is for album art only; glyphs and app icons sit directly on the black
        (leftIcon.background as GradientDrawable).setColor(if (l is Media) 0xFF2C2C2E.toInt() else Color.TRANSPARENT)
        leftText.text = left
        leftText.visibility = if (left.isEmpty()) GONE else VISIBLE
        rightText.text = right
        rightText.setTextColor(accent)
        rightText.visibility = if (right.isEmpty()) GONE else VISIBLE
        ring.visibility = if (level != null) VISIBLE else GONE
        if (level != null) ring.set(level, accent, fromEmpty = !ringWasShown)
        val lw = (if (icon != null) dp(iconSize + 8) else 0) + leftText.paint.measureText(left).toInt()
        val rw = when {
            wave.visibility == VISIBLE -> dp(20)
            chrono.visibility == VISIBLE -> { chronoLen = chrono.text.length; chrono.paint.measureText(chrono.text.toString()).toInt() + dp(4) }
            else -> rightText.paint.measureText(right).toInt() + (if (level != null) dp(26) else 0)
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
        val accent = tint(m.art)
        art.setImageBitmap(m.art)
        glow.setImageBitmap(m.art)
        title.text = m.title
        artist.text = m.artist
        appBadge.setImageDrawable(m.pkg?.let { app(it).first })
        cardWave.playing = m.playing
        cardWave.color = accent
        play.setImageResource(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        play.contentDescription = if (m.playing) "Pause" else "Play"
        if (accent != seekAccent) { seekAccent = accent; seek.progressDrawable = track(accent) }
        seek.max = m.duration.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        seek.visibility = if (m.duration > 0) VISIBLE else INVISIBLE // live streams have no duration
        showPosition(m) // a drag reveals the card before the ticker runs
    }

    private fun bindCall(c: Call) {
        if (callIcon.drawable !== c.icon) {
            callIcon.setImageDrawable(c.icon)
            callGlow.setImageDrawable(c.icon?.constantState?.newDrawable(resources)) // own copy: a drawable can't sit in two views
        }
        callCaption.text = c.text.ifEmpty { "Incoming call" }
        callName.text = c.name
        // same buttons already on screen: rebuilding would cancel a press in progress
        val sig = c.actions.map { it.title.toString() to it.actionIntent }
        if (sig == callSig && content === callCard) return
        callSig = sig
        callButtons.removeAllViews()
        val pair = c.actions.size == 2 // CallStyle: decline first, answer last
        c.actions.forEachIndexed { i, a ->
            val answer = c.actions.size > 1 && i == c.actions.lastIndex
            val color = if (answer) GREEN else RED
            val b: View = if (pair) {
                // iconButton gives the same press-in feedback as the media controls
                iconButton(if (answer) R.drawable.ic_call else R.drawable.ic_call_end, a.title.toString(), 13) { host.send(a.actionIntent) }.apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                    if (answer) rock(this)
                }
            } else {
                label(14f, 600).apply {
                    text = a.title
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), 0)
                    background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(color) }
                    setOnClickListener { host.send(a.actionIntent) }
                }
            }
            // 44dp: design.md's minimum touch target
            callButtons.addView(b, if (pair) lp(44, 44).apply { marginStart = dp(12) }
                else LinearLayout.LayoutParams(WRAP, dp(44)).apply { marginStart = dp(8) })
        }
        // many text buttons would squeeze the name to nothing: cap the row (keeping 96dp for the name) and let them share it
        val cap = maxW - dp(32 + 48 + 16 + 96)
        callButtons.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val squeeze = !pair && callButtons.measuredWidth > cap
        callButtons.layoutParams = LinearLayout.LayoutParams(if (squeeze) cap else WRAP, WRAP)
        if (squeeze) for (i in 0 until callButtons.childCount) callButtons.getChildAt(i).apply {
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f }
        }
    }

    /** The answer glyph rocks like a ringing handset: a short burst every 1.8 s, in step with the ripple. */
    private fun rock(v: View) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        v.post(object : Runnable {
            override fun run() {
                if (!v.isAttachedToWindow || !v.isShown) { v.rotation = 0f; return } // card gone: stop
                val t = SystemClock.uptimeMillis() % 1800 / 500f
                v.rotation = if (t < 1f) 14f * sin(t * 6 * PI).toFloat() * (1f - t) else 0f
                v.postOnAnimation(this)
            }
        })
    }

    private fun bindNotif(f: Flash) {
        notifIcon.setImageDrawable(f.icon)
        notifIcon.visibility = if (f.icon != null) VISIBLE else GONE
        notifApp.text = f.pkg?.let { app(it).second } ?: ""
        // room left of the camera: half the card minus padding and the camera, minus the 32dp photo and its 10dp gap
        val room = (maxW - dp(32) - gapW) / 2 - dp(42)
        val fits = notifTitle.paint.measureText(f.title) <= room
        notifTitle.text = f.title
        notifTitle.visibility = if (fits) VISIBLE else GONE
        notifTitleLong.text = f.title
        notifTitleLong.visibility = if (fits) GONE else VISIBLE
        notifText.text = f.text
        if (f === notifFor) return // the same popup re-rendered: buttons and colour are already in place
        notifFor = f
        notifActions.removeAllViews()
        f.actions.take(3).forEachIndexed { i, a ->
            notifActions.addView(label(14f, 600).apply {
                text = a.title
                gravity = Gravity.CENTER
                minHeight = dp(44) // design.md's minimum touch target
                setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0x26FFFFFF) }
                setOnClickListener { host.action(f, a) }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        notifActions.visibility = if (f.actions.isEmpty()) GONE else VISIBLE
        // the app's brand colour (Notification.color), else the icon's, else design.md's on-dark blue
        val tint = dominant(intArrayOf(f.accent)) ?: iconColor(f.icon) ?: BLUE
        if (tint == notifTint) return
        notifTint = tint
        val hsv = FloatArray(3).also { Color.colorToHSV(tint, it) }
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        fun shade(v: Float) = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], v))
        // black under the camera band, deepening into the app's colour (the reference's weather card); the bottom
        // stays dark enough (value ≤ 0.48) for white text on yellow or green brands
        notifCard.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.BLACK, shade(0.22f), shade(0.48f)))
    }

    /** [dominant] colour of a drawable, drawn small on its own copy (bounds belong to the view showing it). */
    private fun iconColor(d: Drawable?): Int? {
        val copy = d?.constantState?.newDrawable(resources) ?: return null
        val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        copy.setBounds(0, 0, 16, 16)
        copy.draw(Canvas(b))
        val px = IntArray(256).also { b.getPixels(it, 0, 16, 0, 0, 16, 16) }
        b.recycle()
        return dominant(px)
    }

    private fun describe(l: Live?): String? = when (l) {
        is Media -> "${l.title}, ${l.artist}"
        is Call -> "Call, ${l.name}"
        is Timer -> l.label
        is Flash -> listOf(l.title, l.text).filter { it.isNotEmpty() }.joinToString(", ")
        is Ongoing -> listOf(l.title, l.text).filter { it.isNotEmpty() }.joinToString(", ")
        null -> null
    }

    // ---- content: materialize (scale + blur + fade in), dematerialize on the way out ----

    private fun swap(v: View?) {
        if (v === content) return
        content?.let { o ->
            o.animate().cancel()
            o.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setStartDelay(0).setDuration(120)
                .setInterpolator(EASE_OUT).withEndAction { o.visibility = GONE; o.setRenderEffect(null) }.start()
            blur(o, 0f, 16f, 120, 0)
            if (o === compact) { // the two halves slip back into the camera
                compactLeft.animate().translationX(dp(14).toFloat()).setDuration(120).setInterpolator(EASE_OUT).start()
                compactRight.animate().translationX(-dp(14).toFloat()).setDuration(120).setInterpolator(EASE_OUT).start()
            }
        }
        content = v
        v ?: return
        v.animate().cancel()
        val dragged = v.visibility == VISIBLE && v.alpha > 0f // already half-revealed by a drag: continue from there
        v.visibility = VISIBLE
        if (dragged) {
            blurs.remove(v)?.cancel(); v.setRenderEffect(null) // may be mid-dematerialize: stop its blur too
            settleChildren(v)
        } else {
            v.alpha = 0f; v.scaleX = 0.9f; v.scaleY = 0.9f
            blur(v, 14f, 0f, 240, 30)
            emerge(v)
        }
        // content arrives with the pill, not after it: an empty black pill reads as lag
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(if (dragged) 0 else 30).setDuration(240).setInterpolator(EASE_OUT).start()
    }

    /**
     * Content comes out of the camera: in the pill, the two halves slide outward from the hole; in a card, its rows
     * arrive one after another, rising slightly, instead of all at once.
     */
    private fun emerge(v: View) {
        if (v === compact) {
            for ((half, from) in listOf(compactLeft to dp(14), compactRight to -dp(14))) {
                half.animate().cancel()
                half.translationX = from.toFloat()
                half.animate().translationX(0f).setStartDelay(30).setDuration(320).setInterpolator(EASE_OUT).start()
            }
            return
        }
        val rows = when (v) { mediaCard -> mediaBody; callCard -> callRow; else -> v as LinearLayout }
        for (i in 0 until rows.childCount) {
            val row = rows.getChildAt(i)
            row.animate().cancel()
            row.alpha = 0f
            row.translationY = dp(8).toFloat()
            row.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 40L).setDuration(260).setInterpolator(EASE_OUT).start()
        }
    }

    /** Puts staggered rows/halves at rest, e.g. when a drag reveals the content instead of [emerge]. */
    private fun settleChildren(v: View) {
        val rows = when (v) {
            compact -> listOf(compactLeft, compactRight)
            mediaCard -> (0 until mediaBody.childCount).map { mediaBody.getChildAt(it) }
            callCard -> (0 until callRow.childCount).map { callRow.getChildAt(it) }
            else -> (v as LinearLayout).let { g -> (0 until g.childCount).map { g.getChildAt(it) } }
        }
        for (r in rows) { r.animate().cancel(); r.alpha = 1f; r.translationX = 0f; r.translationY = 0f }
    }

    private fun sizeContent(v: View, w: Int, h: Int) {
        v.layoutParams = (v.layoutParams as LayoutParams).apply { width = w; height = h }
        v.pivotX = w / 2f
        v.pivotY = 0f
    }

    // ---- drag: music pill ⇄ card follows the finger 1:1, then hands the finger's velocity to the springs ----

    /** [fromW]..[toH] are the settled sizes (the drag's scale); [w0]/[h0] where the springs were when it began. */
    private class Drag(val fromW: Int, val fromH: Int, val toW: Int, val toH: Int, val from: View, val to: View, val w0: Float, val h0: Float)

    private var drag: Drag? = null
    private var downX = 0f
    private var downY = 0f
    private var velocity: VelocityTracker? = null
    private var downEvent: MotionEvent? = null // in the pill's coordinates, for stealFlick()
    private val slop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private val maxFling by lazy { ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat() }

    private fun track(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            held = true
            downX = e.rawX; downY = e.rawY
            downEvent?.recycle()
            downEvent = MotionEvent.obtain(e)
            velocity?.recycle()
            velocity = VelocityTracker.obtain()
        }
        // screen coordinates: the pill resizes under the finger, so view-relative positions would drift
        val raw = MotionEvent.obtain(e).apply { setLocation(e.rawX, e.rawY) }
        velocity?.addMovement(raw)
        raw.recycle()
    }

    /**
     * An upward move past the slop that began on a message card's button: take the touch from the button and replay
     * its down to the gesture detector, which never saw it, so the fling that follows dismisses the card.
     */
    private fun stealFlick(e: MotionEvent): Boolean {
        if (content !== notifCard) return false
        val dy = e.rawY - downY
        if (dy > -slop || abs(dy) < abs(e.rawX - downX)) return false
        downEvent?.let { gestures.onTouchEvent(it) }
        return true
    }

    /** Starts a drag once the finger has clearly moved vertically: down on the music pill, or up on the music card. */
    private fun beginDrag(e: MotionEvent): Boolean {
        if (drag != null) return true
        val dy = e.rawY - downY
        if (abs(dy) < slop || abs(dy) < abs(e.rawX - downX)) return false
        val m = media ?: return false
        val open = content === mediaCard
        if (open == (dy > 0)) return false
        bindMedia(m)
        val pillW = gapW + 2 * bindCompact(m)
        val cardH = fit(mediaBody, dp(148))
        sizeContent(compact, pillW, pillH)
        sizeContent(mediaCard, maxW, cardH)
        // from wherever the springs are: a drag that catches the island mid-animation mustn't jump
        val d = if (open) Drag(maxW, cardH, pillW, pillH, mediaCard, compact, pw.value, ph.value)
            else Drag(pillW, pillH, maxW, cardH, compact, mediaCard, pw.value, ph.value)
        for (v in listOf(d.from, d.to)) { // either may be mid-materialize; the finger drives alpha from here
            v.animate().cancel()
            blurs.remove(v)?.cancel()
            v.setRenderEffect(null)
            v.scaleX = 1f; v.scaleY = 1f
        }
        d.to.visibility = VISIBLE
        d.to.alpha = 0f
        settleChildren(d.to)
        removeCallbacks(startClock); startAt = 0
        clock.cancel()
        pill.animate().scaleX(1f).scaleY(1f).setDuration(120).start() // undo the touch-down press
        canvas(max(cardH, ph.value.roundToInt()) + dp(48)) // room for the rubber band past the card
        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
        gestures.onTouchEvent(cancel) // no long-press once dragging
        cancel.recycle()
        drag = d
        return true
    }

    private fun dragTo(e: MotionEvent) {
        val d = drag ?: return
        val range = (d.toH - d.fromH).toFloat()
        val raw = (e.rawY - downY) / range
        // rubber band past either end: resist instead of stopping dead
        // capped so the overshoot stays inside the dp(48) canvas margin and on screen
        val p = when { raw < 0f -> raw * 0.25f; raw > 1f -> 1f + (raw - 1f) * 0.25f; else -> raw }.coerceIn(-0.05f, 1.05f)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> follow(d, p)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release(d, p, e.actionMasked == MotionEvent.ACTION_UP)
        }
    }

    private fun follow(d: Drag, p: Float) {
        pw.value = d.w0 + (d.toW - d.w0) * p
        ph.value = d.h0 + (d.toH - d.h0) * p
        pw.velocity = 0f; ph.velocity = 0f
        size(pw.value.roundToInt(), ph.value.roundToInt())
        val q = p.coerceIn(0f, 1f)
        d.from.alpha = (1f - q * 2f).coerceIn(0f, 1f)
        d.to.alpha = ((q - 0.3f) / 0.7f).coerceIn(0f, 1f)
    }

    private fun release(d: Drag, p: Float, up: Boolean) {
        drag = null
        val vt = velocity
        vt?.computeCurrentVelocity(1000)
        val vy = (vt?.yVelocity ?: 0f).coerceIn(-maxFling, maxFling)
        val range = (d.toH - d.fromH).toFloat()
        // hand the finger's speed to the springs so there's no seam between dragging and animating
        ph.velocity = vy
        pw.velocity = vy * (d.toW - d.fromW) / range
        val projected = p + vy * 0.2f / range // where a flick is heading ~200 ms on
        if (up && projected > 0.5f) {
            host.expand(d.to === mediaCard) // re-renders synchronously; swap() continues the half-revealed content
            if (content === d.to) return
        }
        // snap back; the render picks up anything that changed mid-drag (a call, a popup, fullscreen)
        d.to.animate().alpha(0f).setDuration(120).withEndAction { if (content !== d.to) d.to.visibility = GONE }.start()
        d.from.animate().alpha(1f).setStartDelay(0).setDuration(160).start()
        host.expand(d.from === mediaCard)
    }

    private fun blur(v: View, from: Float, to: Float, ms: Long, delay: Long) {
        blurs.remove(v)?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { v.setRenderEffect(null); return }
        blurs[v] = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            startDelay = delay
            addUpdateListener {
                val r = it.animatedValue as Float
                v.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL))
            }
            start()
        }
    }

    // ---- size: two springs, velocity kept across interruptions ----

    private fun retarget(tw: Int, th: Int, delayStart: Boolean) {
        // most renders (media position updates, bursts of session callbacks) change nothing: no relayout, no clock
        if (!clock.isStarted && pw.value == tw.toFloat() && ph.value == th.toFloat() && pw.target == pw.value && ph.target == ph.value) {
            removeCallbacks(startClock); startAt = 0
            host.touch(tw, th, touch)
            canvas(th)
            return
        }
        val growing = tw.toLong() * th > (pw.value * ph.value).toLong()
        if (haptics && !quiet && growing && tw.toFloat() != pw.target) pill.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        bouncy = growing
        pw.target = tw.toFloat()
        ph.target = th.toFloat()
        // the drawing window never resizes sideways (resizing a centred window shifts old frames); the touch window
        // changes once per retarget, and shrinking it waits for settle() so a tap on the still-large pill lands
        if (growing) host.touch(tw, th, touch) else host.touch(max(tw, pw.value.roundToInt()), max(th, ph.value.roundToInt()), touch)
        canvas(max(canvasH, max(ph.value.roundToInt(), th) + dp(16))) // start, end and overshoot; trimmed in settle()
        removeCallbacks(startClock)
        if (!ValueAnimator.areAnimatorsEnabled()) { startAt = 0; clock.cancel(); pw.snap(); ph.snap(); settle(); return }
        // let content fade before shrinking into the camera; a follow-up render inside the 70 ms keeps the same deadline
        val now = SystemClock.uptimeMillis()
        if (delayStart && startAt <= now) startAt = now + 70
        if (startAt > now) postDelayed(startClock, startAt - now) else startClock.run()
    }

    private fun frame(dtMs: Long) {
        // follow the system "Animator duration scale" so springs slow down with everything else (and in slow-mo testing)
        val scale = ValueAnimator.getDurationScale().takeIf { it > 0f } ?: 1f
        var left = min(dtMs, 48L) / 1000f / scale
        // "melt": growing, the pill widens first and then drops; shrinking, it pulls up first and then narrows
        val wResponse = if (bouncy) 0.36f else 0.40f
        val hResponse = if (bouncy) 0.46f else 0.30f
        val damping = if (bouncy) 0.8f else 1f // ~1.5% overshoot: alive, not wobbly
        while (left > 0f) {
            val dt = min(left, 0.004f)
            pw.step(dt, wResponse, damping)
            ph.step(dt, hResponse, damping)
            left -= dt
        }
        if (pw.settled(0.5f) && ph.settled(0.5f)) {
            pw.snap(); ph.snap()
            clock.end()
            settle()
        } else {
            // a hard fling can overshoot past the headroom: grow the window (rarely) rather than clip the card
            if (ph.value > canvasH) canvas(ph.value.roundToInt() + dp(48))
            size(pw.value.roundToInt(), ph.value.roundToInt())
        }
    }

    private fun settle() {
        val w = pw.target.roundToInt()
        val h = ph.target.roundToInt()
        size(w, h)
        host.touch(w, h, touch)
        canvas(h)
    }

    private fun canvas(h: Int) { canvasH = h; host.canvas(h) }

    private fun size(w: Int, h: Int) {
        val cw = max(w, 0)
        val ch = max(h, 0)
        pill.layoutParams = (pill.layoutParams as LayoutParams).apply { width = cw; height = ch }
        // a true pill when compact, and the radius keeps growing with the height up to 40dp: cards stay soft, never boxy
        bg.cornerRadius = min(ch / 2f, max(dp(40), pillH / 2).toFloat())
        pill.invalidateOutline()
    }

    // ---- helpers ----

    private var artIcon: BitmapDrawable? = null
    private var tintSrc: Bitmap? = null
    private var tintVal = WHITE

    /** Average album-art colour, brightened, for waveform/caption/progress. */
    private fun tint(b: Bitmap?): Int {
        b ?: return WHITE
        if (b === tintSrc) return tintVal
        val c = try { Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0) } catch (e: RuntimeException) { return WHITE }
        val hsv = FloatArray(3).also { Color.colorToHSV(c, it) }
        hsv[2] = max(hsv[2], 0.9f)
        tintSrc = b
        tintVal = Color.HSVToColor(hsv)
        return tintVal
    }

    /** 4dp bar centred in the SeekBar's 22dp touch target (ProgressBar stretches the drawable to the view height). */
    private fun track(fill: Int): Drawable {
        val r = dp(2).toFloat()
        val back = GradientDrawable().apply { cornerRadius = r; setColor(TRACK) }
        val front = ClipDrawable(GradientDrawable().apply { cornerRadius = r; setColor(fill) }, Gravity.START, ClipDrawable.HORIZONTAL)
        return LayerDrawable(arrayOf(back, front)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            for (i in 0..1) {
                setLayerHeight(i, dp(4))
                setLayerGravity(i, Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL)
            }
        }
    }

    private fun glowView() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0.6f
        setRenderEffect(RenderEffect.createBlurEffect(dp(48).toFloat(), dp(48).toFloat(), Shader.TileMode.CLAMP))
    }

    private fun scrimView() = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33000000, 0xAA000000.toInt(), 0xE6000000.toInt()))
    }

    // shortcut: an app updated while the island runs keeps its old icon/name until the map fills and clears
    private val apps = HashMap<String, Pair<Drawable?, String?>>()

    /** Icon and label of a package, looked up once: renders come in bursts. */
    private fun app(pkg: String): Pair<Drawable?, String?> {
        if (apps.size > 32) apps.clear()
        return apps.getOrPut(pkg) {
            val pm = context.packageManager
            try { pm.getApplicationInfo(pkg, 0).let { pm.getApplicationIcon(it) to pm.getApplicationLabel(it).toString() } }
            catch (e: Exception) { null to null }
        }
    }

    private fun clockText(ms: Long): String {
        val s = max(ms, 0L) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(dp(w), dp(h))

    private fun label(size: Float, weight: Int) = TextView(context).apply {
        textSize = size
        typeface = font(weight)
        // design.md tracking is size-specific: airy tagline, tight 14–17sp, neutral fine print
        letterSpacing = when { size >= 20f -> 0.011f; size >= 17f -> -0.022f; size >= 14f -> -0.016f; else -> 0f }
        setTextColor(WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun column(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), 0, dp(4), 0)
        views.forEach { addView(it) }
    }

    private fun roundImage(radius: Int) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply { cornerRadius = radius.toFloat(); setColor(0xFF2C2C2E.toInt()) }
        clipToOutline = true
    }

    private fun iconButton(icon: Int, desc: String, pad: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(WHITE)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        contentDescription = desc
        isClickable = true
        setOnTouchListener { b, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> b.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.7f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> b.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200).setInterpolator(EASE_OUT).start()
            }
            false
        }
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
        val moving = ValueAnimator.areAnimatorsEnabled() // reduced motion: static bars, still taller than paused
        val t = if (moving) SystemClock.uptimeMillis() / 1000f else 0f
        val room = height - bar
        for (i in 0 until n) {
            val amp = if (playing) 0.25f + 0.75f * abs(sin(t * (4f + i * 1.3f) + i * 1.7f)) else 0.15f
            val x = bar / 2 + i * 2 * bar
            val half = room * amp / 2
            c.drawLine(x, height / 2f - half, x, height / 2f + half, paint)
        }
        if (moving && playing && isShown) postInvalidateOnAnimation()
    }
}

/** Green rings that ripple out from the caller's photo while it rings. Only animates while on screen. */
private class Ripple(ctx: Context) : View(ctx) {
    var inner = 0f // photo radius: rings start at its edge
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GREEN }

    override fun onDraw(c: Canvas) {
        if (!ValueAnimator.areAnimatorsEnabled()) return // reduced motion: no rings
        val cx = width / 2f
        val cy = height / 2f
        val t = SystemClock.uptimeMillis() % 1800 / 1800f
        for (k in 0..1) {
            val p = (t + k * 0.5f) % 1f
            val e = 1f - (1f - p) * (1f - p) // ease out: fast from the photo, slowing as it fades
            paint.strokeWidth = resources.displayMetrics.density * 2f * (1f - p)
            paint.alpha = ((1f - p) * 150).toInt()
            c.drawCircle(cx, cy, inner + (min(cx, cy) - inner) * e, paint)
        }
        if (isShown) postInvalidateOnAnimation()
    }
}

/** Ring gauge: fills from empty when it first appears, then glides between levels (e.g. download progress). */
private class Ring(ctx: Context) : View(ctx) {
    private var level = -1
    private var color = GREEN
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val box = RectF()

    fun set(level: Int, color: Int, fromEmpty: Boolean) {
        if (level == this.level && color == this.color && !fromEmpty) return
        this.level = level
        this.color = color
        anim?.cancel()
        if (fromEmpty) shown = 0f
        anim = ValueAnimator.ofFloat(shown, level / 100f).apply {
            duration = 700
            startDelay = 150
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val stroke = s * 0.16f
        paint.strokeWidth = stroke
        val ox = (width - s) / 2
        val oy = (height - s) / 2
        box.set(ox + stroke / 2, oy + stroke / 2, ox + s - stroke / 2, oy + s - stroke / 2)
        paint.color = color
        paint.alpha = 70
        c.drawArc(box, 0f, 360f, false, paint)
        paint.alpha = 255
        c.drawArc(box, -90f, 360f * shown, false, paint)
    }
}

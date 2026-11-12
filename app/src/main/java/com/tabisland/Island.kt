package com.tabisland

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.provider.Settings

const val WHITE = 0xFFFFFFFF.toInt()
const val GREEN = 0xFF30D158.toInt()
const val ORANGE = 0xFFFF9F0A.toInt()
const val RED = 0xFFFF453A.toInt()
const val BLUE = 0xFF2997FF.toInt() // design.md primary-on-dark: the one accent on dark surfaces
const val YELLOW = 0xFFFFD60A.toInt()
const val PAUSED_KEEP_MS = 5 * 60_000L

/** Something the pill can show. [pkg]/[open] drive long-press and tap. */
sealed interface Live {
    val pkg: String?
    val open: PendingIntent?
}

/**
 * Short popup: notifications and system events. [key] is its settings switch, [id] the notification key, [level] a 0–100 ring.
 * [actions] are the notification's buttons; [autoCancel] means tapping it should cancel the notification.
 */
data class Flash(
    val key: String,
    val title: String,
    val text: String,
    val icon: Drawable? = null,
    val accent: Int = WHITE,
    val level: Int? = null,
    override val pkg: String? = null,
    override val open: PendingIntent? = null,
    val id: String? = null,
    val until: Long,
    val actions: List<Notification.Action> = emptyList(),
    val autoCancel: Boolean = false,
) : Live

/** [since] is the chronometer base (elapsedRealtime) once answered; null while ringing or dialing. [ringing]: incoming, unanswered. */
class Call(
    val key: String,
    val name: String,
    val text: String,
    val icon: Drawable?,
    val actions: List<Notification.Action>,
    val since: Long?,
    override val pkg: String?,
    override val open: PendingIntent?,
    val ringing: Boolean = since == null,
) : Live

/** [base] is the chronometer base (elapsedRealtime); null means show [label] instead. */
class Timer(
    val key: String,
    val label: String,
    val icon: Drawable?,
    val base: Long?,
    val countDown: Boolean,
    override val pkg: String?,
    override val open: PendingIntent?,
) : Live

/**
 * A live activity that lasts while something is on: navigation, flashlight, a download. [pref] is its settings switch.
 * [rank] ≥ 2 shows above music (navigation, flashlight); below 2 only when nothing else is going on (progress).
 */
class Ongoing(
    val key: String,
    val pref: String,
    val title: String,
    val text: String,
    val icon: Drawable?,
    val accent: Int,
    val level: Int?,
    val rank: Int,
    override val pkg: String?,
    override val open: PendingIntent?,
) : Live

class Media(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val lastPlayed: Long,
    val duration: Long,
    val position: Long,
    val positionAt: Long,
    val speed: Float,
    override val pkg: String?,
    override val open: PendingIntent?,
) : Live {
    fun positionNow(elapsed: Long) = if (playing) position + ((elapsed - positionAt) * speed).toLong() else position
}

/** Shared state written by MediaListener / IslandService / MainActivity (one process, main thread). */
object Island {
    var call: Call? = null
        set(v) { field = v; onChange?.invoke() }
    var timer: Timer? = null
        set(v) { field = v; onChange?.invoke() }
    var media: Media? = null
        set(v) { field = v; onChange?.invoke() }
    var flash: Flash? = null
        private set(v) { field = v; onChange?.invoke() }
    private val queue = ArrayDeque<Pair<Flash, Long>>() // waiting flashes, each with how long it shows
    private var flashMs = 0L // how long the current flash shows: a ringing call puts it back in the queue with it
    private val ongoing = LinkedHashMap<String, Ongoing>()
    var controller: MediaController? = null
    var onChange: (() -> Unit)? = null
    /** Cancels a notification by key; set while MediaListener is connected. */
    var cancel: ((String) -> Unit)? = null
    /** Demo mode: the service treats every feature switch as on. */
    var demo = false
        set(v) { field = v; onChange?.invoke() }
    /** A calibration slider is being touched: the service shows the idle pill even with "idle" off. */
    var calibrating = false
        set(v) { field = v; onChange?.invoke() }

    /** System pop-ups go off only while the island both gets notifications ([listening]) and can show them ([showing]). */
    var listening = false
    var showing = false
    private var headsUpOff: Boolean? = null
    fun syncHeadsUp(ctx: Context) {
        val off = listening && showing
        if (off == headsUpOff) return
        // not granted: skip a settings IPC that would fail again on every render
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        // needs `adb shell pm grant com.tabisland android.permission.WRITE_SECURE_SETTINGS`; without it both still show
        try { Settings.Global.putInt(ctx.contentResolver, "heads_up_notifications_enabled", if (off) 0 else 1); headsUpOff = off }
        catch (e: SecurityException) {}
    }

    fun putOngoing(o: Ongoing) { ongoing[o.key] = o; onChange?.invoke() }
    fun removeOngoing(key: String) { if (ongoing.remove(key) != null) onChange?.invoke() }
    fun clearOngoing(keep: String) { if (ongoing.keys.retainAll { it == keep }) onChange?.invoke() }

    /** Shows [f] now, or queues it behind a live flash. An update of the same flash (key and id) replaces it instead. */
    fun post(f: Flash) {
        val now = System.currentTimeMillis()
        val cur = flash
        // volume steps or a chat's next message must not line up behind their own stale copy
        queue.removeAll { it.first.key == f.key && it.first.id == f.id }
        val live = cur != null && cur.until > now
        if (cur != null && cur.key == f.key && cur.id == f.id || !live && queue.isEmpty()) { flashMs = f.until - now; flash = f; return }
        queue.addLast(f to f.until - now)
        if (queue.size > 5) queue.removeFirst()
        // the live flash may be one whose switch is off: render anyway, so top() can move past it
        if (!live) next(now) else onChange?.invoke()
    }

    fun dismissFlash() = next(System.currentTimeMillis())
    /** Dismisses [f] only while it (or an update of it) is still up: a tap on a stale card mustn't take the next one. */
    fun dismissFlash(f: Flash) {
        if (flash?.let { it === f || it.id != null && it.key == f.key && it.id == f.id } == true) dismissFlash()
    }
    fun removeFlash(id: String) = drop { it.id == id }
    fun clearFlashes(key: String) = drop { it.key == key }

    private fun drop(match: (Flash) -> Boolean) {
        queue.removeAll { match(it.first) }
        if (flash?.let(match) == true) dismissFlash()
    }

    // a queued flash gets its full time from when it actually shows
    private fun next(now: Long) {
        val n = queue.removeFirstOrNull()
        flashMs = n?.second ?: 0L
        flash = n?.let { (f, ms) -> f.copy(until = now + ms) }
    }

    fun top(now: Long, on: (String) -> Boolean): Live? {
        if (call?.let { it.ringing && on("call") } == true) {
            // the ringing call covers every flash: park a message, unseen, at the head of the queue with its full
            // time instead of letting it run out under the call; system events (unlock, charging) are stale by then
            flash?.let { f -> if (f.key == "notif" && f.until > now) queue.addFirst(f to flashMs); flash = null }
        } else {
            // a flash whose switch is off is never seen: it mustn't hold back the ones queued behind it
            while (queue.isNotEmpty() && flash.let { it == null || it.until <= now || !on(it.key) }) next(now)
        }
        return pick(call, flash, timer, ongoing.values, media, now, on)
    }
}

/**
 * Priority: ringing call > flash > call in progress > timer > navigation/flashlight > music > progress > idle (null).
 * [on] answers the per-feature switches.
 */
fun pick(call: Call?, flash: Flash?, timer: Timer?, ongoing: Collection<Ongoing>, media: Media?, now: Long, on: (String) -> Boolean): Live? {
    val top = ongoing.filter { on(it.pref) }.maxByOrNull { it.rank }
    // a call in progress is only a pill: messages must still pop over it, the system banners are off while the island runs
    return call?.takeIf { on("call") && it.ringing }
        ?: flash?.takeIf { it.until > now && on(it.key) }
        ?: call?.takeIf { on("call") }
        ?: timer?.takeIf { on("timer") }
        ?: top?.takeIf { it.rank >= 2 }
        ?: media?.takeIf { on("media") && (it.playing || now - it.lastPlayed < PAUSED_KEEP_MS) }
        ?: top
}

/**
 * Colourful average of ARGB pixels (e.g. an app icon): each pixel weighs alpha × chroma, so white, grey, black and
 * transparent pixels don't wash the colour out. Null when nothing in it is colourful.
 */
fun dominant(px: IntArray): Int? {
    var r = 0f; var g = 0f; var b = 0f; var total = 0f
    for (p in px) {
        val pr = p shr 16 and 0xFF; val pg = p shr 8 and 0xFF; val pb = p and 0xFF
        val w = (p ushr 24) / 255f * (maxOf(pr, pg, pb) - minOf(pr, pg, pb)) / 255f
        r += pr * w; g += pg * w; b += pb * w; total += w
    }
    if (total < px.size * 0.05f) return null
    return (0xFF shl 24) or ((r / total).toInt() shl 16) or ((g / total).toInt() shl 8) or (b / total).toInt()
}

/** When [live] stops qualifying on its own, so the service knows when to re-check. */
fun expiry(live: Live?): Long? = when (live) {
    is Flash -> live.until
    is Media -> if (live.playing) null else live.lastPlayed + PAUSED_KEEP_MS
    else -> null
}

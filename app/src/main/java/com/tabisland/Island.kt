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
const val BLUE = 0xFF0A84FF.toInt()
const val YELLOW = 0xFFFFD60A.toInt()
const val PAUSED_KEEP_MS = 5 * 60_000L

/** Something the pill can show. [pkg]/[open] drive long-press and tap. */
sealed interface Live {
    val pkg: String?
    val open: PendingIntent?
}

/** Short popup: notifications and system events. [key] is its settings switch, [id] the notification key, [level] a 0–100 ring. */
class Flash(
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
) : Live

/** [since] is the chronometer base (elapsedRealtime) once answered; null while ringing. */
class Call(
    val key: String,
    val name: String,
    val text: String,
    val icon: Drawable?,
    val actions: List<Notification.Action>,
    val since: Long?,
    override val pkg: String?,
    override val open: PendingIntent?,
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
        set(v) { field = v; onChange?.invoke() }
    private val ongoing = LinkedHashMap<String, Ongoing>()
    var controller: MediaController? = null
    var onChange: (() -> Unit)? = null

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

    fun top(now: Long, on: (String) -> Boolean) = pick(call, flash, timer, ongoing.values, media, now, on)
}

/**
 * Priority: ringing call > flash > call in progress > timer > navigation/flashlight > music > progress > idle (null).
 * [on] answers the per-feature switches.
 */
fun pick(call: Call?, flash: Flash?, timer: Timer?, ongoing: Collection<Ongoing>, media: Media?, now: Long, on: (String) -> Boolean): Live? {
    val top = ongoing.filter { on(it.pref) }.maxByOrNull { it.rank }
    // a call in progress is only a pill: messages must still pop over it, the system banners are off while the island runs
    return call?.takeIf { on("call") && it.since == null }
        ?: flash?.takeIf { it.until > now && on(it.key) }
        ?: call?.takeIf { on("call") }
        ?: timer?.takeIf { on("timer") }
        ?: top?.takeIf { it.rank >= 2 }
        ?: media?.takeIf { on("media") && (it.playing || now - it.lastPlayed < PAUSED_KEEP_MS) }
        ?: top
}

/** When [live] stops qualifying on its own, so the service knows when to re-check. */
fun expiry(live: Live?): Long? = when (live) {
    is Flash -> live.until
    is Media -> if (live.playing) null else live.lastPlayed + PAUSED_KEEP_MS
    else -> null
}

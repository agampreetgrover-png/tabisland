package com.tabisland

import android.app.Notification
import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.session.MediaController

const val WHITE = 0xFFFFFFFF.toInt()
const val GREEN = 0xFF30D158.toInt()
const val ORANGE = 0xFFFF9F0A.toInt()
const val RED = 0xFFFF453A.toInt()
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
    var controller: MediaController? = null
    var onChange: (() -> Unit)? = null

    fun top(now: Long, on: (String) -> Boolean) = pick(call, flash, timer, media, now, on)
}

/** Priority: call > flash > timer > media > idle (null). [on] answers the per-feature switches. */
fun pick(call: Call?, flash: Flash?, timer: Timer?, media: Media?, now: Long, on: (String) -> Boolean): Live? =
    call?.takeIf { on("call") }
        ?: flash?.takeIf { it.until > now && on(it.key) }
        ?: timer?.takeIf { on("timer") }
        ?: media?.takeIf { on("media") && (it.playing || now - it.lastPlayed < PAUSED_KEEP_MS) }

/** When [live] stops qualifying on its own, so the service knows when to re-check. */
fun expiry(live: Live?): Long? = when (live) {
    is Flash -> live.until
    is Media -> if (live.playing) null else live.lastPlayed + PAUSED_KEEP_MS
    else -> null
}

package com.tabisland

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout

/** Feeds Island with music (media sessions), calls, timers and notification popups. Nothing leaves the device. */
class MediaListener : NotificationListenerService() {
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val alerted = HashSet<String>()
    private val me by lazy { ComponentName(this, MediaListener::class.java) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = attach(null)
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { attach(best(it)) }

    override fun onListenerConnected() {
        Island.listening = true; Island.syncHeadsUp(this) // the island shows notifications instead of the system banner
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        m.addOnActiveSessionsChangedListener(sessions, me)
        attach(best(m.getActiveSessions(me)))
        // pick up a call/timer already running; existing keys count as alerted so ONLY_ALERT_ONCE updates stay quiet
        activeNotifications?.forEach { alerted.add(it.key); classify(it, alert = false) }
    }

    override fun onListenerDisconnected() {
        Island.listening = false; Island.syncHeadsUp(this) // island no longer gets notifications, give the system banners back
        msm?.removeOnActiveSessionsChangedListener(sessions)
        attach(null)
        alerted.clear()
        probes.clear()
        Island.call = null
        Island.timer = null
        Island.clearOngoing(keep = "torch") // no removal callbacks will arrive for these; torch isn't ours
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val r = Ranking()
        // only popups that would make a sound: respects silent channels and Do Not Disturb
        val loud = rankingMap?.getRanking(sbn.key, r) == true &&
            r.importance >= NotificationManager.IMPORTANCE_DEFAULT && r.matchesInterruptionFilter()
        classify(sbn, loud)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        alerted.remove(sbn.key)
        probes.remove(sbn.key)
        val hadSlot = Island.call?.key == sbn.key || Island.timer?.key == sbn.key
        if (Island.call?.key == sbn.key) Island.call = null
        if (Island.timer?.key == sbn.key) Island.timer = null
        if (Island.flash?.id == sbn.key) Island.flash = null
        Island.removeOngoing(sbn.key)
        // one call/timer slot: another running call or stopwatch may have been hidden behind the one that ended
        if (hadSlot) activeNotifications?.forEach { if (it.key != sbn.key && it.isOngoing) classify(it, alert = false) }
    }

    /** 0–100 for a determinate progress bar (downloads, uploads, installs), else null. */
    private fun progressOf(n: Notification): Int? {
        val x = n.extras
        val max = x.getInt(Notification.EXTRA_PROGRESS_MAX)
        if (max <= 0 || x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) return null
        return (x.getInt(Notification.EXTRA_PROGRESS) * 100L / max).toInt().coerceIn(0, 100)
    }

    private fun classify(sbn: StatusBarNotification, alert: Boolean) {
        val n = sbn.notification
        val x = n.extras
        val pkg = sbn.packageName
        if (pkg == packageName) return
        if (x.containsKey(Notification.EXTRA_MEDIA_SESSION)) { // music comes from the session
            // sessions-changed doesn't fire when an already-active session starts playing; its notification update does
            msm?.let { attach(best(it.getActiveSessions(me))) }
            return
        }
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val chrono = if (sbn.isOngoing) chronometer(sbn.key, n) else null
        val nav = sbn.isOngoing && n.category == Notification.CATEGORY_NAVIGATION
        val progress = if (sbn.isOngoing) progressOf(n) else null // a finished, dismissible "100%" isn't live
        if (!nav && progress == null) Island.removeOngoing(sbn.key) // e.g. a finished download
        // an update that no longer qualifies (e.g. stopwatch paused, call ended, timer turned progress) must not leave the old one showing
        if (Island.call?.key == sbn.key) Island.call = null
        if (Island.timer?.key == sbn.key) Island.timer = null
        when {
            n.category == Notification.CATEGORY_CALL && sbn.isOngoing ->
                Island.call = Call(sbn.key, title, text, n.getLargeIcon()?.loadDrawable(this) ?: appIcon(pkg),
                    n.actions?.toList().orEmpty(), chrono?.first, pkg, n.contentIntent ?: n.fullScreenIntent)
            sbn.isOngoing && chrono != null ->
                Island.timer = Timer(sbn.key, title.ifEmpty { text }, appIcon(pkg), chrono.first, chrono.second, pkg, n.contentIntent)
            // Google Maps: title is the distance to the next turn, large icon the turn arrow
            nav -> Island.putOngoing(Ongoing(sbn.key, "nav", title, "", n.getLargeIcon()?.loadDrawable(this) ?: appIcon(pkg),
                WHITE, null, 3, pkg, n.contentIntent))
            progress != null -> Island.putOngoing(Ongoing(sbn.key, "progress", "", "$progress%", appIcon(pkg),
                BLUE, progress, 1, pkg, n.contentIntent))
            else -> {
                // same rule as Notification.suppressAlertingDueToGrouping (default ALL: summaries stay quiet, as before)
                val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
                val muted = if (summary) n.group == null || n.groupAlertBehavior != Notification.GROUP_ALERT_SUMMARY
                    else n.group != null && n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY
                if (alert && !sbn.isOngoing && !muted && (title.isNotEmpty() || text.isNotEmpty())) {
                    val first = alerted.add(sbn.key)
                    if (first || n.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0) {
                        Island.flash = Flash("notif", title, text, n.getLargeIcon()?.loadDrawable(this) ?: appIcon(pkg),
                            pkg = pkg, open = n.contentIntent, id = sbn.key, until = System.currentTimeMillis() + 4000)
                    }
                }
            }
        }
    }

    /**
     * (elapsedRealtime base, countDown) from a standard chronometer notification, or from a custom view's
     * Chronometer (Google Clock's timers and stopwatch use custom RemoteViews).
     * shortcut: a paused custom-view timer still looks running; detecting pause needs per-app parsing.
     */
    private fun chronometer(key: String, n: Notification): Pair<Long, Boolean>? {
        val x = n.extras
        if (x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
            return SystemClock.elapsedRealtime() + (n.`when` - System.currentTimeMillis()) to x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        }
        @Suppress("DEPRECATION")
        val rv = n.contentView ?: n.bigContentView ?: return null
        // inflating another app's views on the main thread is costly; apps re-posting fast get at most one per second
        // shortcut: a change within that second (e.g. a quick pause/resume) shows on the next post, upgrade if it matters
        val now = SystemClock.uptimeMillis()
        probes[key]?.takeIf { now - it.at < 1000 }?.let { return it.result }
        val root = try { rv.apply(this, FrameLayout(this)) } catch (e: Exception) { null }
        val result = root?.let { find(it) }?.let { it.base to it.isCountDown }
        probes[key] = Probe(now, result)
        return result
    }

    private class Probe(val at: Long, val result: Pair<Long, Boolean>?)
    private val probes = HashMap<String, Probe>()

    private fun find(v: View): Chronometer? =
        v as? Chronometer ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }

    private fun appIcon(pkg: String): Drawable? =
        try { packageManager.getApplicationIcon(pkg) } catch (e: PackageManager.NameNotFoundException) { null }

    // ---- media ----

    private fun best(list: List<MediaController>?) =
        list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()

    private fun attach(c: MediaController?) {
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val m = c?.metadata
        Island.controller = c
        if (c == null || m == null) { Island.media = null; return }
        val s = c.playbackState
        val playing = s?.state == PlaybackState.STATE_PLAYING
        val prev = Island.media
        val now = System.currentTimeMillis()
        Island.media = Media(
            title = m.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART),
            playing = playing,
            // paused sessions linger 5 min after playback stops; one found paused at startup stays hidden
            lastPlayed = if (playing || prev?.playing == true) now else prev?.lastPlayed ?: 0L,
            duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION),
            position = s?.position ?: 0L,
            positionAt = s?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = s?.playbackSpeed ?: 1f,
            pkg = c.packageName,
            open = c.sessionActivity,
        )
    }
}

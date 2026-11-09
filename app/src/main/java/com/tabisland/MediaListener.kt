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
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        m.addOnActiveSessionsChangedListener(sessions, me)
        attach(best(m.getActiveSessions(me)))
        activeNotifications?.forEach { classify(it, alert = false) } // pick up a call/timer already running
    }

    override fun onListenerDisconnected() {
        msm?.removeOnActiveSessionsChangedListener(sessions)
        attach(null)
        Island.call = null
        Island.timer = null
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
        if (Island.call?.key == sbn.key) Island.call = null
        if (Island.timer?.key == sbn.key) Island.timer = null
        if (Island.flash?.id == sbn.key) Island.flash = null
    }

    private fun classify(sbn: StatusBarNotification, alert: Boolean) {
        val n = sbn.notification
        val x = n.extras
        val pkg = sbn.packageName
        if (pkg == packageName || x.containsKey(Notification.EXTRA_MEDIA_SESSION)) return // music comes from the session
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val chrono = if (sbn.isOngoing) chronometer(n) else null
        when {
            n.category == Notification.CATEGORY_CALL && sbn.isOngoing ->
                Island.call = Call(sbn.key, title, text, n.getLargeIcon()?.loadDrawable(this) ?: appIcon(pkg),
                    n.actions?.toList().orEmpty(), chrono?.first, pkg, n.contentIntent ?: n.fullScreenIntent)
            sbn.isOngoing && chrono != null ->
                Island.timer = Timer(sbn.key, title.ifEmpty { text }, appIcon(pkg), chrono.first, chrono.second, pkg, n.contentIntent)
            alert && !sbn.isOngoing && n.flags and Notification.FLAG_GROUP_SUMMARY == 0 && (title.isNotEmpty() || text.isNotEmpty()) -> {
                val first = alerted.add(sbn.key)
                if (first || n.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0) {
                    Island.flash = Flash("notif", title, text, n.getLargeIcon()?.loadDrawable(this) ?: appIcon(pkg),
                        pkg = pkg, open = n.contentIntent, id = sbn.key, until = System.currentTimeMillis() + 4000)
                }
            }
        }
    }

    /**
     * (elapsedRealtime base, countDown) from a standard chronometer notification, or from a custom view's
     * Chronometer (Google Clock's timers and stopwatch use custom RemoteViews).
     * shortcut: a paused custom-view timer still looks running; detecting pause needs per-app parsing.
     */
    private fun chronometer(n: Notification): Pair<Long, Boolean>? {
        val x = n.extras
        if (x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
            return SystemClock.elapsedRealtime() + (n.`when` - System.currentTimeMillis()) to x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        }
        @Suppress("DEPRECATION")
        val rv = n.contentView ?: n.bigContentView ?: return null
        val root = try { rv.apply(this, FrameLayout(this)) } catch (e: Exception) { return null }
        return find(root)?.let { it.base to it.isCountDown }
    }

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

package com.tabisland

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils

/** Feeds Island with music (media sessions), calls, timers and notification popups. Nothing leaves the device. */
class MediaListener : NotificationListenerService() {
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val alerted = HashSet<String>()
    private val popped = HashMap<String, String>() // key -> title and text of its last flash
    private val me by lazy { ComponentName(this, MediaListener::class.java) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = attach(null)
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { attach(best(it)) }

    override fun onListenerConnected() {
        Island.listening = true; Island.syncHeadsUp(this) // the island shows notifications instead of the system banner
        Island.cancel = { cancelNotification(it) }
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        m.addOnActiveSessionsChangedListener(sessions, me)
        attach(best(m.getActiveSessions(me)))
        // pick up a call/timer already running; existing keys count as alerted so ONLY_ALERT_ONCE updates stay quiet
        activeNotifications?.forEach { alerted.add(it.key); classify(it, alert = false) }
    }

    override fun onListenerDisconnected() {
        Island.listening = false; Island.syncHeadsUp(this) // island no longer gets notifications, give the system banners back
        Island.cancel = null
        msm?.removeOnActiveSessionsChangedListener(sessions)
        attach(null)
        alerted.clear()
        popped.clear()
        Island.call = null
        Island.timer = null
        Island.clearOngoing(keep = "torch") // no removal callbacks will arrive for these; torch isn't ours
    }

    override fun onDestroy() {
        // a crash or unbind can skip onListenerDisconnected; never leave the system banners off
        Island.listening = false; Island.syncHeadsUp(this)
        Island.cancel = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val r = Ranking()
        // only popups that would make a sound: respects silent channels and Do Not Disturb; suspended apps stay quiet
        val loud = rankingMap?.getRanking(sbn.key, r) == true &&
            r.importance >= NotificationManager.IMPORTANCE_DEFAULT && r.matchesInterruptionFilter() && !r.isSuspended
        classify(sbn, loud)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        alerted.remove(sbn.key)
        popped.remove(sbn.key)
        val hadSlot = Island.call?.key == sbn.key || Island.timer?.key == sbn.key
        if (Island.call?.key == sbn.key) Island.call = null
        if (Island.timer?.key == sbn.key) Island.timer = null
        Island.removeFlash(sbn.key)
        Island.removeOngoing(sbn.key)
        if (hadSlot) rescan(sbn.key)
    }

    // one call/timer slot: another running call or stopwatch may have been hidden behind the one that ended
    private fun rescan(gone: String) { activeNotifications?.forEach { if (it.key != gone && it.isOngoing) classify(it, alert = false) } }

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
        val chrono = if (sbn.isOngoing) chronometer(n) else null
        // a paused MetricStyle timer or stopwatch keeps only the frozen time: a still pill instead of a running one
        val paused = if (sbn.isOngoing && chrono == null) metric(n)?.takeIf { it.containsKey("pausedDuration") }?.getLong("pausedDuration") else null
        val nav = sbn.isOngoing && n.category == Notification.CATEGORY_NAVIGATION
        val progress = if (sbn.isOngoing) progressOf(n) else null // a finished, dismissible "100%" isn't live
        if (!nav && progress == null) Island.removeOngoing(sbn.key) // e.g. a finished download
        // an update that no longer qualifies (e.g. stopwatch paused, call ended, timer turned progress) must not leave the old one showing
        val hadSlot = Island.call?.key == sbn.key || Island.timer?.key == sbn.key
        if (Island.call?.key == sbn.key) Island.call = null
        if (Island.timer?.key == sbn.key) Island.timer = null
        // alarms, reminders and full-screen alerts are ongoing, so the message path below never pops them
        val alarm = sbn.isOngoing && n.category != Notification.CATEGORY_CALL && (n.fullScreenIntent != null ||
            n.category == Notification.CATEGORY_ALARM || n.category == Notification.CATEGORY_REMINDER)
        // a running or paused timer or stopwatch is the timer pill, not a 60 s blank card; a full-screen alert with no
        // text still shows its app
        if (alert && alarm && (chrono == null && paused == null || n.fullScreenIntent != null) &&
            (title.isNotEmpty() || text.isNotEmpty() || n.fullScreenIntent != null) && alerted.add(sbn.key)) {
            pop(sbn, title.ifEmpty { if (text.isEmpty()) appLabel(pkg) else "" }, text, 60_000)
        }
        when {
            n.category == Notification.CATEGORY_CALL && sbn.isOngoing -> {
                val ringing = chrono == null && x.getInt(Notification.EXTRA_CALL_TYPE) != Notification.CallStyle.CALL_TYPE_ONGOING
                val actions = if (!ringing) null else listOfNotNull(
                    callAction(x, Notification.EXTRA_DECLINE_INTENT, "Decline") ?: callAction(x, Notification.EXTRA_HANG_UP_INTENT, "Hang up"),
                    callAction(x, Notification.EXTRA_ANSWER_INTENT, "Answer")).ifEmpty { null }
                // a dialing or held call must not push an incoming one out of the slot
                if (ringing || Island.call?.ringing != true) Island.call = Call(sbn.key, title, text,
                    n.getLargeIcon()?.loadDrawable(this) ?: appIcon(pkg), actions ?: n.actions?.toList().orEmpty(), chrono?.first,
                    pkg, n.contentIntent ?: n.fullScreenIntent, ringing)
            }
            sbn.isOngoing && (chrono != null || paused != null) -> Island.timer = Timer(sbn.key,
                paused?.let { "Paused " + DateUtils.formatElapsedTime(it / 1000) } ?: title.ifEmpty { text },
                appIcon(pkg), chrono?.first, chrono?.second ?: false, pkg, n.contentIntent)
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
                    // an unchanged re-post (e.g. a read receipt or re-sort) isn't news; the same words sent again are
                    if (first || n.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0 && popped[sbn.key] != signature(n, title, text))
                        pop(sbn, title, text, if (n.actions.isNullOrEmpty()) 4000 else 6000)
                }
            }
        }
        if (hadSlot && Island.call?.key != sbn.key && Island.timer?.key != sbn.key) rescan(sbn.key)
    }

    /**
     * What makes a re-post a new message, so "ok" sent twice pops twice: MessagingStyle's message count and the last
     * message's time ("time" in each message bundle), else the notification's own time.
     */
    @Suppress("DEPRECATION")
    private fun signature(n: Notification, title: String, text: String): String {
        val msgs = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val stamp = if (msgs.isNullOrEmpty()) "${n.`when`}" else "${msgs.size}/${(msgs.last() as? Bundle)?.getLong("time")}"
        return "$title\n$text\n$stamp"
    }

    private fun appLabel(pkg: String): String =
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
        catch (e: PackageManager.NameNotFoundException) { "" }

    private fun pop(sbn: StatusBarNotification, title: String, text: String, ms: Long) {
        val n = sbn.notification
        popped[sbn.key] = signature(n, title, text)
        if (!Island.showing) return // the system banner shows it; posting would show it a second time later
        Island.post(Flash("notif", title, text, n.getLargeIcon()?.loadDrawable(this) ?: appIcon(sbn.packageName),
            accent = n.color, pkg = sbn.packageName, open = n.contentIntent ?: n.fullScreenIntent, id = sbn.key,
            until = System.currentTimeMillis() + ms, actions = n.actions?.take(3).orEmpty(),
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0))
    }

    /** A CallStyle button from its PendingIntent extra; the dialer keeps these out of [Notification.actions]. */
    @Suppress("DEPRECATION")
    private fun callAction(x: Bundle, extra: String, title: String) =
        (x.getParcelable(extra) as? PendingIntent)?.let { Notification.Action.Builder(null as Icon?, title, it).build() }

    /** (elapsedRealtime base, countDown) from a chronometer notification or a running MetricStyle time. */
    private fun chronometer(n: Notification): Pair<Long, Boolean>? {
        val x = n.extras
        if (x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) {
            return SystemClock.elapsedRealtime() + (n.`when` - System.currentTimeMillis()) to x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        }
        val v = metric(n) ?: return null
        return if (v.containsKey("zeroElapsedRealtime")) v.getLong("zeroElapsedRealtime") to v.getBoolean("countDown") else null
    }

    /**
     * The first metric's value of a MetricStyle notification (Google Clock's timers and stopwatch on Android 16+): no
     * chronometer, no text, the time is in "android.metrics". Notification.Metric isn't in SDK 36, so read the bundle.
     */
    private fun metric(n: Notification): Bundle? =
        n.extras.getParcelableArrayList("android.metrics", Bundle::class.java)?.firstOrNull()?.getBundle("value")

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
        val prev = Island.media?.takeIf { it.pkg == c.packageName } // another app's play time isn't this session's
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

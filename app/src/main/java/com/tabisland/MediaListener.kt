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
import android.os.Parcelable
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import android.util.Log

/** Feeds Island with music (media sessions), calls, timers and notification popups. Nothing leaves the device. */
class MediaListener : NotificationListenerService() {
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    // kept from the callback: controller.metadata is an IPC that unparcels a new art bitmap on every playback tick
    private var meta: MediaMetadata? = null
    private val alerted = HashSet<String>()
    private val popped = HashMap<String, String>() // key -> title and text of its last flash
    private val me by lazy { ComponentName(this, MediaListener::class.java) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { meta = metadata; publish() }
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = attach(null)
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { attach(best(it)) }

    override fun onListenerConnected() {
        Island.listening = true; Island.syncHeadsUp(this) // the island shows notifications instead of the system banner
        Island.cancel = { safe { cancelNotification(it) } } // throws SecurityException once access is revoked
        val m = getSystemService(MediaSessionManager::class.java)
        msm = m
        m.addOnActiveSessionsChangedListener(sessions, me)
        attach(best(m.getActiveSessions(me)))
        // pick up a call/timer already running; existing keys count as alerted so ONLY_ALERT_ONCE updates stay quiet
        activeNotifications?.forEach { alerted.add(it.key); safe { classify(it, alert = false) } }
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
        // a crash or unbind can skip onListenerDisconnected: never leave the system banners off, or the session
        // listener and controller callback (which hold this service) registered; safe to run twice
        onListenerDisconnected()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val r = Ranking()
        // only popups that would make a sound: respects silent channels and Do Not Disturb; suspended apps stay quiet
        val loud = rankingMap?.getRanking(sbn.key, r) == true &&
            r.importance >= NotificationManager.IMPORTANCE_DEFAULT && r.matchesInterruptionFilter() && !r.isSuspended
        safe { classify(sbn, loud, loud && r.importance >= NotificationManager.IMPORTANCE_HIGH) }
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
    private fun rescan(gone: String) { safe { activeNotifications }?.forEach { if (it.key != gone && it.isOngoing) safe { classify(it, alert = false) } } }

    /** Another app's extras can hold a class we can't unparcel (BadParcelableException): skip it, never crash the listener. */
    private inline fun <T> safe(read: () -> T): T? =
        try { read() } catch (e: RuntimeException) { Log.w("TabIsland", "unreadable notification", e); null }

    /** 0–100 for a determinate progress bar (downloads, uploads, installs), else null. */
    private fun progressOf(n: Notification): Int? {
        val x = n.extras
        val max = x.getInt(Notification.EXTRA_PROGRESS_MAX)
        if (max <= 0 || x.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) return null
        return (x.getInt(Notification.EXTRA_PROGRESS) * 100L / max).toInt().coerceIn(0, 100)
    }

    /** [high]: a heads-up-level alert, which the system would pop even for an ongoing notification. */
    private fun classify(sbn: StatusBarNotification, alert: Boolean, high: Boolean = false) {
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
        // a delivery or ride shows its minutes, not its bar's percent: Android 16's Live Update chip text, else the
        // time in its words ("Arriving in 12 mins"); a download has neither and keeps the percent
        val critical = if (sbn.isOngoing) x.getCharSequence("android.shortCriticalText")?.toString()?.takeIf { it.isNotBlank() } else null
        val eta = critical ?: if (progress != null) eta(title, text) else null
        if (!nav && progress == null && critical == null) Island.removeOngoing(sbn.key) // e.g. a finished download
        // an update that no longer qualifies (e.g. stopwatch paused, call ended, timer turned progress) must not leave the old one showing
        val hadSlot = Island.call?.key == sbn.key || Island.timer?.key == sbn.key
        if (Island.call?.key == sbn.key) Island.call = null
        if (Island.timer?.key == sbn.key) Island.timer = null
        // alarms, reminders and full-screen alerts get a 60 s card: they're usually ongoing, which the message path
        // skips, and an alarm app's non-ongoing one mustn't vanish after 6 s while it rings
        val alarm = n.category != Notification.CATEGORY_CALL && (n.fullScreenIntent != null ||
            n.category == Notification.CATEGORY_ALARM || sbn.isOngoing && n.category == Notification.CATEGORY_REMINDER)
        // a running or paused timer or stopwatch is the timer pill, not a 60 s blank card; a full-screen alert with no
        // text still shows its app
        if (alert && alarm && (chrono == null && paused == null || n.fullScreenIntent != null) &&
            (title.isNotEmpty() || text.isNotEmpty() || n.fullScreenIntent != null) && alerted.add(sbn.key)) {
            pop(sbn, title.ifEmpty { if (text.isEmpty()) appLabel(pkg) else "" }, text, 60_000)
        }
        when {
            n.category == Notification.CATEGORY_CALL && sbn.isOngoing -> {
                val ringing = chrono == null && isRinging(x.getInt(Notification.EXTRA_CALL_TYPE), n.fullScreenIntent != null)
                val actions = if (!ringing) null else listOfNotNull(
                    callAction(x, Notification.EXTRA_DECLINE_INTENT, "Decline") ?: callAction(x, Notification.EXTRA_HANG_UP_INTENT, "Hang up"),
                    callAction(x, Notification.EXTRA_ANSWER_INTENT, "Answer")).ifEmpty { null }
                // a dialing or held call must not push an incoming one out of the slot; a demo's (no pkg) gives way to any real call
                if (ringing || Island.call?.ringing != true || Island.call?.pkg == null) Island.call = Call(sbn.key, title, text,
                    largeIcon(n) ?: appIcon(pkg), actions ?: n.actions?.toList().orEmpty(), chrono?.first,
                    pkg, n.contentIntent ?: n.fullScreenIntent, ringing)
            }
            // first come keeps the slot (it was freed above if this is its own update), so a screen recorder's
            // chronometer can't flip-flop with a Clock timer; a running one does take it from a paused one. The rescan
            // shows the next once the shown one ends
            sbn.isOngoing && (chrono != null || paused != null) -> if (Island.timer == null || chrono != null && Island.timer?.base == null)
                Island.timer = Timer(sbn.key, paused?.let { "Paused " + DateUtils.formatElapsedTime(it / 1000) } ?: title.ifEmpty { text },
                    appIcon(pkg), chrono?.first, chrono?.second ?: false, pkg, n.contentIntent)
            // Google Maps: title is the distance to the next turn, large icon the turn arrow
            nav -> Island.putOngoing(Ongoing(sbn.key, "nav", title, "", largeIcon(n) ?: appIcon(pkg),
                WHITE, null, 3, pkg, n.contentIntent))
            progress != null || critical != null -> Island.putOngoing(Ongoing(sbn.key, "progress", "", eta ?: "$progress%",
                appIcon(pkg), BLUE, progress, 1, pkg, n.contentIntent))
            else -> {
                val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
                // shortcut: a summary posted before its first child still pops too; apps post the child first
                val loudChild = summary && n.group != null && activeNotifications.orEmpty().any { c -> c.key != sbn.key &&
                    c.packageName == pkg && c.user == sbn.user && c.notification.group == n.group &&
                    c.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
                    !groupMuted(n.group, false, c.notification.groupAlertBehavior) }
                val muted = groupMuted(n.group, summary, n.groupAlertBehavior, loudChild)
                // seeds the active ones at connect, so their unchanged re-posts after a reconnect don't pop again
                if (!alert) popped.putIfAbsent(sbn.key, signature(n, title, text))
                // the system pop-ups are off: an ongoing heads-up alert (a ride arriving) must still pop; alarms did above
                if (alert && (!sbn.isOngoing || high && !alarm) && !muted && (title.isNotEmpty() || text.isNotEmpty())) {
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
        val msgs = safe { n.extras.getParcelableArray(Notification.EXTRA_MESSAGES) }
        // the last message someone else sent (the user's own have no sender): a reply or trimmed history appended to
        // the list re-posts it, but the card would only show the same old message again
        val last = msgs?.lastOrNull { (it as? Bundle)?.run { containsKey("sender") || containsKey("sender_person") } == true } as? Bundle
        val stamp = last?.getLong("time")?.toString() ?: "${n.`when`}"
        return "$title\n$text\n$stamp"
    }

    private fun appLabel(pkg: String): String =
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
        catch (e: PackageManager.NameNotFoundException) { "" }

    private fun pop(sbn: StatusBarNotification, title: String, text: String, ms: Long) {
        val n = sbn.notification
        popped[sbn.key] = signature(n, title, text)
        if (!Island.showing) return // the system banner shows it; posting would show it a second time later
        Island.post(Flash("notif", title, text, largeIcon(n) ?: appIcon(sbn.packageName),
            accent = n.color, pkg = sbn.packageName, open = n.contentIntent ?: n.fullScreenIntent, id = sbn.key,
            until = System.currentTimeMillis() + ms, actions = n.actions?.take(3).orEmpty(),
            autoCancel = n.flags and Notification.FLAG_AUTO_CANCEL != 0))
    }

    /** A CallStyle button from its PendingIntent extra; the dialer keeps these out of [Notification.actions]. */
    @Suppress("DEPRECATION")
    private fun callAction(x: Bundle, extra: String, title: String) =
        safe { x.getParcelable<Parcelable>(extra) as? PendingIntent }?.let { Notification.Action.Builder(null as Icon?, title, it).build() }

    /** (elapsedRealtime base, countDown) from a chronometer notification or a running MetricStyle time. */
    private fun chronometer(n: Notification): Pair<Long, Boolean>? {
        val x = n.extras
        // when = 0 (unset) would put the base 56 years back: a pill of huge hours
        if (x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && n.`when` > 0) {
            return SystemClock.elapsedRealtime() + (n.`when` - System.currentTimeMillis()) to x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        }
        val v = metric(n) ?: return null
        return if (v.containsKey("zeroElapsedRealtime")) v.getLong("zeroElapsedRealtime") to v.getBoolean("countDown") else null
    }

    /**
     * The first metric's value of a MetricStyle notification (Google Clock's timers and stopwatch on Android 16+): no
     * chronometer, no text, the time is in "android.metrics". Notification.Metric isn't in SDK 36, so read the bundle.
     */
    // the typed getParcelableArrayList is API 33: on Android 12 it's a NoSuchMethodError, which safe() doesn't catch
    @Suppress("DEPRECATION")
    private fun metric(n: Notification): Bundle? =
        safe { (n.extras.getParcelableArrayList<Parcelable>("android.metrics")?.firstOrNull() as? Bundle)?.getBundle("value") }

    /** The notification's own picture (contact photo, turn arrow); null when it can't be read or decodes to nothing. */
    private fun largeIcon(n: Notification): Drawable? =
        try { n.getLargeIcon()?.loadDrawable(this)?.takeIf { it.intrinsicWidth > 0 } } catch (e: Exception) { null }

    private fun appIcon(pkg: String): Drawable? =
        try { packageManager.getApplicationIcon(pkg) } catch (e: PackageManager.NameNotFoundException) { null }

    // ---- media ----

    private fun best(list: List<MediaController>?) =
        list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()

    private fun attach(c: MediaController?) {
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            meta = c?.metadata
            c?.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val m = meta
        Island.controller = c
        if (c == null || m == null) { Island.media = null; return }
        val s = c.playbackState
        val playing = s?.state == PlaybackState.STATE_PLAYING
        val prev = Island.media?.takeIf { it.pkg == c.packageName } // another app's play time isn't this session's
        val now = System.currentTimeMillis()
        Island.media = Media(
            title = m.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            // shortcut: *_ART_URI isn't loaded (main thread, no INTERNET, other apps' content:// needs a grant); load it off-thread if players without bitmaps matter
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = playing,
            // paused sessions linger 5 min after playback stops; one found paused at startup stays hidden
            lastPlayed = if (playing || prev?.playing == true) now else prev?.lastPlayed ?: 0L,
            duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION),
            position = s?.position ?: 0L,
            // 0 means the player never stamped its position: extrapolating from boot would run the bar to the end
            positionAt = s?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = s?.playbackSpeed ?: 1f,
            pkg = c.packageName,
            open = c.sessionActivity,
        )
    }
}

private val MINUTES = Regex("""(\d+(?:\s*[-–]\s*\d+)?)\s*(mins?|minutes?|hrs?|hours?)\b""", RegexOption.IGNORE_CASE)
private val CLOCK = Regex("""\b\d{1,2}:\d{2}(?:\s*[ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)

/** The arrival time in a delivery or ride's words: "Arriving in 10-15 mins" → "10–15 min", "by 7:45 pm" → "7:45 pm". */
internal fun eta(vararg texts: String): String? {
    texts.forEach { t ->
        MINUTES.find(t)?.let { m ->
            val unit = if (m.groupValues[2].startsWith("h", ignoreCase = true)) "hr" else "min"
            return m.groupValues[1].replace(Regex("""\s*[-–]\s*"""), "–") + " " + unit
        }
    }
    return texts.firstNotNullOfOrNull { CLOCK.find(it)?.value }
}

/** Incoming and unanswered: CallStyle says so, or (no CallStyle) a full-screen intent, which is how calls ring; dialing has neither. */
internal fun isRinging(callType: Int, fullScreen: Boolean) =
    callType == Notification.CallStyle.CALL_TYPE_INCOMING || callType == Notification.CallStyle.CALL_TYPE_UNKNOWN && fullScreen

/**
 * Notification.suppressAlertingDueToGrouping (a grouped summary set to CHILDREN or a grouped child set to SUMMARY stays
 * quiet), plus a summary whose group already has a child that pops on its own: one message, one card.
 */
internal fun groupMuted(group: String?, summary: Boolean, behavior: Int, loudChild: Boolean = false) = group != null &&
    (behavior == (if (summary) Notification.GROUP_ALERT_CHILDREN else Notification.GROUP_ALERT_SUMMARY) || summary && loudChild)

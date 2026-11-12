package com.tabisland

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

private const val TAG = "TabIsland"

// MainActivity's palette
private const val BASALT = 0xFF161618.toInt()
private const val SEAM = 0xFF2A2A2E.toInt()
private const val PUMICE = 0xFF9E9EA6.toInt()
private const val BONE = 0xFFF4F4F1.toInt()

/** Inline reply for a message card's action: the island's overlay window can't take keyboard focus. */
class ReplyActivity : Activity() {
    companion object {
        fun start(ctx: Context, a: Notification.Action, title: String) = ctx.startActivity(
            Intent(ctx, ReplyActivity::class.java).putExtra("action", a).putExtra("title", title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val a = intent.getParcelableExtra<Notification.Action>("action") ?: return finish()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val box = EditText(this).apply {
            hint = a.remoteInputs?.firstOrNull()?.label ?: "Message"
            textSize = 16f; setTextColor(BONE); setHintTextColor(PUMICE)
            background = GradientDrawable().apply { setColor(SEAM); cornerRadius = px(20).toFloat() }
            setPadding(px(16), px(12), px(16), px(12))
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            // no MULTI_LINE: it makes TextView add IME_FLAG_NO_ENTER_ACTION, so the keyboard shows Enter, not Send;
            // the box still wraps (it isn't singleLine) up to maxLines
            setRawInputType(EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_CAP_SENTENCES)
            // an Enter key event reaches the listener on both down and up: send once, on down
            setOnEditorActionListener { _, id, e ->
                if (id == EditorInfo.IME_ACTION_SEND || e?.action == KeyEvent.ACTION_DOWN) send(a, text)
                true
            }
        }
        val sendBtn = TextView(this).apply {
            text = "Send"; textSize = 15f; setTextColor(BASALT); typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(BONE); cornerRadius = px(20).toFloat() }
            setPadding(px(18), 0, px(18), 0)
            setOnClickListener { send(a, box.text) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(20), px(20), px(20))
            addView(TextView(context).apply {
                text = "Reply to ${intent.getStringExtra("title").orEmpty()}"
                textSize = 17f; setTextColor(BONE); typeface = Typeface.create(Typeface.DEFAULT, 600, false)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, px(14))
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.BOTTOM
                addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(46)).apply { marginStart = px(8) })
            })
        })
        window.setBackgroundDrawable(GradientDrawable().apply { setColor(BASALT); cornerRadius = px(28).toFloat() })
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        box.requestFocus()
    }

    private fun send(a: Notification.Action, text: CharSequence) {
        if (text.isBlank()) return
        val inputs = a.remoteInputs ?: return finish()
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(inputs, fillIn, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        // Android 14+ blocks an activity start from the sent intent unless the sender opts in (as IslandService.send)
        val opts = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 36) {
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
        } else if (Build.VERSION.SDK_INT >= 34) {
            @Suppress("DEPRECATION")
            opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
        try {
            a.actionIntent.send(this, 0, fillIn, null, null, null, opts.toBundle())
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "reply intent no longer valid", e)
        }
        finish()
    }
}

/** Shows over the keyguard, asks to dismiss it, then sends [pi] or starts [launch]. Never shows UI. */
class UnlockActivity : Activity() {
    companion object {
        fun start(ctx: Context, pi: PendingIntent?, launch: Intent?) = ctx.startActivity(
            Intent(ctx, UnlockActivity::class.java).putExtra("pi", pi).putExtra("launch", launch)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        @Suppress("DEPRECATION") val pi = intent.getParcelableExtra<PendingIntent>("pi")
        @Suppress("DEPRECATION") val launch = intent.getParcelableExtra<Intent>("launch")
        if (pi == null && launch == null) return finish()
        val km = getSystemService(KeyguardManager::class.java)
        // already unlocked: requestDismissKeyguard would only report an error and drop the tap
        if (!km.isKeyguardLocked) return go(pi, launch)
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = go(pi, launch)
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    private fun go(pi: PendingIntent?, launch: Intent?) {
        try {
            if (pi != null) {
                val opts = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 36) {
                    opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
                } else if (Build.VERSION.SDK_INT >= 34) {
                    @Suppress("DEPRECATION")
                    opts.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                pi.send(this, 0, null, null, null, null, opts.toBundle())
            } else if (launch != null) {
                // our task has an empty affinity and finishes now: the app needs its own task
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "pending intent no longer valid", e)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "launch target gone", e)
        }
        finish()
    }
}

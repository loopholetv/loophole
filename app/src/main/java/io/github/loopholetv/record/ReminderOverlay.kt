package io.github.loopholetv.record

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import io.github.loopholetv.MainActivity
import io.github.loopholetv.R
import io.github.loopholetv.data.db.ChannelRef
import io.github.loopholetv.ui.theme.Tokens
import java.time.ZoneId

internal const val REMINDER_WATCH = "Watch"
internal const val REMINDER_DISMISS = "Dismiss"

/** Whether Loophole may draw over other apps. */
fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

/** The intent that brings Loophole to the front on [ref]; an open Loophole receives it as a new intent. */
fun playChannelIntent(context: Context, ref: ChannelRef): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(EXTRA_PLAY_CHANNEL, playChannelExtra(ref))

/**
 * The reminder as a small window over whatever app is in front: plain views (it must not need a Compose
 * host), focusable so the remote reaches its two buttons. [onAnswer] reports Watch (true) or Dismiss and Back
 * (false). Adding the window can fail (permission withdrawn, no display): [show] then throws, and the caller
 * falls back to the notification.
 */
class ReminderOverlay(
    context: Context,
    private val onAnswer: (id: Long, watch: Boolean) -> Unit,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ReminderSurface {
    private val context = context.applicationContext
    private val windows = this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var current: Pair<Long, View>? = null

    override fun show(p: ReminderPrompt) {
        current?.let { remove(it.second) }
        current = null
        val view = build(p)
        windows.addView(view, params())
        current = p.id to view
        view.findViewWithTag<View>(REMINDER_WATCH)?.requestFocus()
    }

    override fun dismiss(id: Long) {
        val (shownId, view) = current ?: return
        if (shownId != id) return
        current = null
        remove(view)
    }

    private fun remove(view: View) {
        // Already gone (the window was lost) is fine.
        runCatching { windows.removeViewImmediate(view) }
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = dp(32)
        y = dp(32)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun build(p: ReminderPrompt): View {
        val (headline, detail) = reminderText(p, zone())
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                setColor(Tokens.SurfaceRaised.toArgb())
                cornerRadius = dp(12).toFloat()
            }
            minimumWidth = dp(420)
        }
        card.addView(text(headline, 20f, Tokens.OnBackground.toArgb()))
        card.addView(text(detail, 16f, Tokens.Muted.toArgb()))
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        // Back, on whichever button has focus, dismisses like the in-app card.
        val back = View.OnKeyListener { _, code, event ->
            val isBack = code == KeyEvent.KEYCODE_BACK
            if (isBack && event.action == KeyEvent.ACTION_UP) onAnswer(p.id, false)
            isBack
        }
        buttons.addView(button(REMINDER_WATCH, back) { onAnswer(p.id, true) })
        buttons.addView(button(REMINDER_DISMISS, back) { onAnswer(p.id, false) })
        card.addView(buttons)
        return card
    }

    private fun text(s: String, sp: Float, color: Int) = TextView(context).apply {
        text = s
        textSize = sp
        setTextColor(color)
        maxLines = 2
    }

    private fun button(label: String, keys: View.OnKeyListener, onClick: () -> Unit) = Button(context).apply {
        text = label
        tag = label
        isAllCaps = false
        setOnClickListener { onClick() }
        setOnKeyListener(keys)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(12) }
    }
}

/** The reminder as a notification on the Reminders channel; tapping it opens Loophole on the channel. */
class ReminderNotification(
    context: Context,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ReminderSurface {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun show(p: ReminderPrompt) {
        Notifications.ensureChannels(context)
        val (headline, detail) = reminderText(p, zone())
        val builder = NotificationCompat.Builder(context, Notifications.REMINDERS)
            .setSmallIcon(R.drawable.loophole_mark)
            .setContentTitle(headline)
            .setContentText(detail)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(POPUP_TIMEOUT_MS)
        p.ref?.let { ref ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, p.id.toInt(), playChannelIntent(context, ref),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        manager.notify(TAG, p.id.toInt(), builder.build())
    }

    override fun dismiss(id: Long) = manager.cancel(TAG, id.toInt())

    private companion object {
        const val TAG = "reminder"
    }
}

package io.github.romanvht.byedpi.services

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.ewenloy.tgws.TgWsProxyService
import io.github.romanvht.byedpi.receiver.QuietHoursReceiver
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.registerNotificationChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/** One alarm per boundary. No running service or polling during the quiet period. */
object QuietHours {
    const val ENABLED = "quiet_hours_enabled"
    const val START = "quiet_hours_start"
    const val END = "quiet_hours_end"
    const val RUNTIME_PREFS = "quiet_hours_runtime"
    private const val RESUME_MODE = "resume_mode"
    private const val RESUME_TG = "resume_tg"
    private const val NOTIFICATION_ID = 104
    private const val CHANNEL = "QuietHours"
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private fun runtime(context: Context) = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)

    fun window(context: Context): QuietHoursWindow {
        val prefs = context.getPreferences()
        return QuietHoursWindow(
            prefs.getInt(START, 0).coerceIn(0, 1439),
            prefs.getInt(END, 8 * 60).coerceIn(0, 1439),
        )
    }

    fun isActive(context: Context): Boolean {
        if (!context.getPreferences().getBoolean(ENABLED, false)) return false
        val now = Calendar.getInstance()
        return window(context).contains(now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE))
    }

    fun canSchedule(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun deferByeDpi(context: Context, mode: Mode): Boolean {
        if (!isActive(context)) return false
        runtime(context).edit(commit = true) { putString(RESUME_MODE, mode.name) }
        requestUpdate(context)
        return true
    }

    fun deferTelegram(context: Context): Boolean {
        if (!isActive(context)) return false
        runtime(context).edit(commit = true) { putBoolean(RESUME_TG, true) }
        requestUpdate(context)
        return true
    }

    fun cancelByeDpiResume(context: Context) {
        runtime(context).edit(commit = true) { remove(RESUME_MODE) }
    }

    fun cancelTelegramResume(context: Context) {
        runtime(context).edit(commit = true) { remove(RESUME_TG) }
    }

    fun hasPendingResume(context: Context): Boolean = runtime(context).let {
        it.contains(RESUME_MODE) || it.getBoolean(RESUME_TG, false)
    }

    fun requestUpdate(context: Context, cancelResume: Boolean = false) {
        val app = context.applicationContext
        scope.launch {
            try { update(app, cancelResume) } catch (e: Exception) { Log.e("QuietHours", "Schedule update failed", e) }
        }
    }

    // Starting an engine only needs to arm the next boundary. Reconciliation here
    // would retry a failed scheduled start indefinitely while its target is saved.
    fun reschedule(context: Context) = schedule(context)

    suspend fun update(context: Context, cancelResume: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            // Schedule first so even an interrupted shutdown keeps the morning alarm.
            schedule(context)
            val saved = runtime(context)
            if (cancelResume) {
                saved.edit(commit = true) { clear() }
                showPausedNotification(context)
                return@withLock
            }
            if (isActive(context)) {
                val runningMode = NativeEngine.running(context)?.first
                val runningTelegram = telegramStarted(context)
                // Persist both targets before waiting for either engine to shut down.
                saved.edit(commit = true) {
                    if (runningMode != null) putString(RESUME_MODE, runningMode.name)
                    if (runningTelegram) putBoolean(RESUME_TG, true)
                }
                if (runningTelegram) telegram(context).stop(scheduled = true)
                showPausedNotification(context)
                if (runningMode != null) {
                    ServiceManager.waitStop(scheduled = true, context = context)
                }
            } else {
                // Independent TG must not wait for a slow or failed VPN startup.
                if (saved.getBoolean(RESUME_TG, false)) {
                    try {
                        if (context.getPreferences().getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)) {
                            telegram(context).start(context.getPreferences())
                        }
                        cancelTelegramResume(context)
                    } catch (e: Exception) {
                        Log.e("QuietHours", "Telegram resume failed", e)
                    }
                }
                showPausedNotification(context)
                val mode = Mode.entries.firstOrNull { it.name == saved.getString(RESUME_MODE, null) }
                if (mode != null) ServiceManager.resumeScheduled(context, mode)
            }
            showPausedNotification(context)
        }
    }

    private fun showPausedNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!hasPendingResume(context)) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        registerNotificationChannel(context, CHANNEL, R.string.quiet_hours_title)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getBroadcast(context, 1,
            Intent(context, QuietHoursReceiver::class.java).setAction(QuietHoursReceiver.ACTION_CANCEL_RESUME),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val end = window(context).end
        val time = String.format(java.util.Locale.getDefault(), "%02d:%02d", end / 60, end % 60)
        val text = context.getString(if (isActive(context)) R.string.quiet_hours_paused_until else R.string.quiet_hours_resume_failed, time)
        try {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.quiet_hours_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .addAction(0, context.getString(R.string.quiet_hours_cancel_resume), cancel)
                .build())
        } catch (e: SecurityException) {
            Log.w("QuietHours", "Notifications are disabled", e)
        }
    }

    private fun telegram(context: Context) = EwenloyTgWsServiceExtension().apply {
        initialize(context, context.getPreferences())
    }

    @Suppress("DEPRECATION")
    private fun telegramStarted(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == TgWsProxyService::class.java.name && it.started
        }
    }

    private fun schedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(context, 0,
            Intent(context, QuietHoursReceiver::class.java).setAction(QuietHoursReceiver.ACTION_BOUNDARY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        val window = window(context)
        if (!context.getPreferences().getBoolean(ENABLED, false) || window.start == window.end) return
        if (!canSchedule(context)) return
        val at = window.nextBoundary(Calendar.getInstance())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarm.setExact(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            Log.w("QuietHours", "Exact alarm access was revoked", e)
        }
    }
}

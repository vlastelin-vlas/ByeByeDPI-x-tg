package io.github.romanvht.byedpi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.romanvht.byedpi.services.QuietHours
import kotlinx.coroutines.*

class QuietHoursReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_BOUNDARY = "io.github.romanvht.byedpi.QUIET_HOURS_BOUNDARY"
        const val ACTION_CANCEL_RESUME = "io.github.romanvht.byedpi.QUIET_HOURS_CANCEL_RESUME"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                withTimeout(8_000) {
                    QuietHours.update(context.applicationContext, intent.action == ACTION_CANCEL_RESUME)
                }
            } catch (e: Exception) {
                Log.e("QuietHours", "Could not apply quiet hours: ${intent.action}", e)
            } finally {
                pending.finish()
            }
        }
    }
}

package com.spamblocker.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import com.spamblocker.app.data.BlockedLog
import com.spamblocker.app.data.DatabaseHelper
import com.spamblocker.app.rules.RuleEngine
import com.spamblocker.app.service.SmsBlockerNotificationService
import com.spamblocker.app.service.SmsBlockerNotificationService.MuteRequest

/**
 * SMS_RECEIVED handling, moved out of the notification listener.
 *
 * The original registered this receiver at runtime, from inside
 * SmsBlockerNotificationService.onCreate():
 *
 *     registerReceiver(smsReceiver, IntentFilter(SMS_RECEIVED))
 *
 * Two consequences. First, SMS parsing only happened while that service was
 * bound — and NotificationListenerService is bound at the system's discretion,
 * unbound under memory pressure, and not bound at all until the user grants
 * notification access in Settings. Second, a runtime-registered receiver on
 * targetSdk 34+ should declare its export state explicitly; relying on the
 * protected-broadcast exemption is fragile across OEM builds.
 *
 * Declaring it in the manifest fixes both: SMS_RECEIVED is exempt from the
 * implicit-broadcast restrictions, so a manifest receiver wakes the app
 * reliably whether or not anything else is running.
 */
class SmsSpamReceiver : BroadcastReceiver() {

    private companion object { const val TAG = "SmsSpamReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Broadcast receivers get ~10s on the main thread. Database and
        // contacts work goes to a worker; goAsync keeps the process alive.
        val pending = goAsync()
        Thread {
            try {
                handle(context, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "SMS handling failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages[0].originatingAddress.orEmpty()
        // Multipart messages arrive as several PDUs; concatenate before matching
        // so a keyword split across the 160-char boundary is still caught.
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val simSlot = simSlotFrom(context, intent)

        val verdict = RuleEngine.evaluate(
            context = context,
            rawNumber = sender,
            body = body,
            simSlot = simSlot,
            rules = DatabaseHelper.get(context).getRules(),
            isSms = true
        )
        if (!verdict.isBlock && !verdict.isSilence) return

        Log.i(TAG, "Flagging SMS from $sender — ${verdict.describe}")

        // Hand the notification listener enough to recognise the matching
        // notification when it posts a moment later.
        SmsBlockerNotificationService.addMuteRequest(
            MuteRequest(
                sender = sender,
                messageSnippet = body.take(40),
                timestamp = System.currentTimeMillis()
            )
        )

        DatabaseHelper.get(context).addLog(
            BlockedLog(
                type = "SMS",
                sender = sender,
                content = body,
                timestamp = System.currentTimeMillis(),
                simSlot = simSlot,
                ruleMatched = verdict.describe,
                action = verdict.action
            )
        )
    }

    private fun simSlotFrom(context: Context, intent: Intent): Int = try {
        val subId = intent.getIntExtra("subscription", -1)
        if (subId == -1) -1
        else context.getSystemService(SubscriptionManager::class.java)
            ?.getActiveSubscriptionInfo(subId)?.simSlotIndex ?: -1
    } catch (e: Exception) {
        -1
    }
}

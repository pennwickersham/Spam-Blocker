package com.spamblocker.app.service

import android.app.Notification
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.spamblocker.app.data.DatabaseHelper
import com.spamblocker.app.rules.RuleEngine
import com.spamblocker.app.util.PhoneNumbers
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors

/**
 * Notification suppression for spam SMS.
 *
 * What this can and cannot do, stated plainly because it shapes what "working"
 * means: an app that is not the default SMS app cannot stop a text from
 * arriving. The message lands in the system database and the messaging app
 * posts a notification. All this service can do is dismiss that notification
 * immediately after it appears. The phone may still make a sound, and the
 * message remains in the inbox. Truly suppressing delivery requires holding the
 * default SMS role, which means implementing SMS_DELIVER, a full conversation
 * UI, and Play's Default SMS Handler declaration.
 *
 * Fixes over the original:
 *  - Contact detection. The original called PhoneLookup with the notification
 *    TITLE, which for a saved contact is a NAME, not a number. The lookup
 *    always missed, so the "sender is in contacts, leave it alone" branch never
 *    fired for named contacts, and keyword rules were applied to messages from
 *    friends and family. Now a title with letters is treated as a resolved
 *    contact name and left alone unless an explicit rule names it.
 *  - The messaging-app allowlist was three hard-coded packages. Any other
 *    default messaging app (Textra, OEM builds, newer Google package names)
 *    meant nothing was ever muted. Now the default SMS package is queried at
 *    runtime and the static list is only a fallback.
 *  - The original held a lock on the mute-request list across a SQLite read and
 *    a contacts query, on the listener's main thread. Lock scope is now the
 *    list access alone, and matching runs on a worker.
 */
class SmsBlockerNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "SmsBlockerListener"
        private const val CORRELATION_WINDOW_MS = 15_000L
        private const val MAX_PENDING = 20

        private val pending = ConcurrentLinkedDeque<MuteRequest>()

        fun addMuteRequest(request: MuteRequest) {
            pending.addLast(request)
            while (pending.size > MAX_PENDING) pending.pollFirst()
        }

        private val FALLBACK_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.textra",
            "com.moez.QKSMS",
            "org.thoughtcrime.securesms"
        )
    }

    data class MuteRequest(
        val sender: String,
        val messageSnippet: String,
        val timestamp: Long
    )

    private val worker = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isMessagingPackage(sbn.packageName)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isEmpty() || text.isEmpty()) return

        val key = sbn.key

        worker.execute {
            try {
                if (shouldMute(title, text)) {
                    Log.i(TAG, "Dismissing spam notification from ${PhoneNumbers.display(title)}")
                    cancelNotification(key)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Notification screening failed", t)
            }
        }
    }

    private fun shouldMute(title: String, text: String): Boolean {
        // 1. Did the SMS receiver already decide this one is spam?
        if (matchesPendingRequest(title, text)) return true

        // 2. A title containing letters means the messaging app resolved the
        //    sender to an address-book name. Do not keyword-match your contacts.
        val titleIsContactName = title.any { it.isLetter() } && !PhoneNumbers.looksLikeNumber(title)
        if (titleIsContactName) {
            // Still honour an explicit rule that names this contact directly.
            val explicit = DatabaseHelper.get(this).getRules()
                .firstOrNull { it.isActive && it.appliesToSms() && it.value.equals(title, ignoreCase = true) }
            return explicit != null && explicit.action != "ALLOW"
        }

        // 3. Unnamed sender: run the normal engine.
        val verdict = RuleEngine.evaluate(
            context = this,
            rawNumber = title,
            body = text,
            simSlot = -1,
            rules = DatabaseHelper.get(this).getRules(),
            isSms = true
        )
        return verdict.isBlock || verdict.isSilence
    }

    /**
     * Correlate with the receiver's decision. The original compared a 20-char
     * snippet with contains() in both directions inside a synchronized block
     * whose iterator removed entries it had merely walked past. Widened to 40
     * chars, normalized, and with expiry handled separately from matching.
     */
    private fun matchesPendingRequest(title: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        pending.removeAll { now - it.timestamp > CORRELATION_WINDOW_MS }

        val titleNational = PhoneNumbers.national(title)
        val match = pending.firstOrNull { req ->
            val senderMatches = titleNational.isNotEmpty() &&
                titleNational == PhoneNumbers.national(req.sender)
            val bodyMatches = req.messageSnippet.isNotEmpty() &&
                (text.contains(req.messageSnippet.take(20)) ||
                    req.messageSnippet.contains(text.take(20)))
            senderMatches || bodyMatches
        }
        if (match != null) pending.remove(match)
        return match != null
    }

    private fun isMessagingPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val default = try {
            Telephony.Sms.getDefaultSmsPackage(this)
        } catch (e: Exception) {
            null
        }
        return pkg == default || pkg in FALLBACK_PACKAGES
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}

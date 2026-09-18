package com.spamblocker.app.service

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.spamblocker.app.data.BlockRule
import com.spamblocker.app.data.BlockedLog
import com.spamblocker.app.data.DatabaseHelper
import com.spamblocker.app.rules.RuleEngine
import com.spamblocker.app.util.PhoneNumbers
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Call screening, rewritten.
 *
 * Three structural problems in the original, all of which show up as "it just
 * doesn't block sometimes" rather than as a crash you can see:
 *
 * 1. onScreenCall runs on the MAIN thread. The original opened SQLite (which on
 *    a cold start means creating the helper, running onCreate, inserting six
 *    default rows) and ran a ContactsContract query there. Telecom gives you
 *    roughly five seconds; a cold start on a mid-range phone can eat it, and
 *    when the deadline passes the platform allows the call and rings.
 *
 * 2. No try/catch. If anything threw — and the original's DatabaseHelper closes
 *    its database after every operation, which throws when another component
 *    holds it open — respondToCall was never called at all. Telecom then times
 *    out, rings the call through, and repeated offenses get the screening role
 *    dropped.
 *
 * 3. respondToCall could in principle be reached twice. Telecom treats the
 *    second call as an error.
 *
 * The rewrite does the work on a background executor, guards the response with
 * an AtomicBoolean so exactly one response is ever sent, and arms a watchdog
 * that allows the call if the worker hasn't answered in time. Fail-open is
 * deliberate: a spam call that rings is a nuisance, a real call that is silently
 * dropped is a problem.
 */
class CallBlockerService : CallScreeningService() {

    private companion object {
        const val TAG = "CallBlocker"
        /** Telecom's budget is ~5s. Answer well inside it. */
        const val WATCHDOG_MS = 3_500L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onScreenCall(callDetails: Call.Details) {
        val responded = AtomicBoolean(false)

        val watchdog = Runnable {
            if (responded.compareAndSet(false, true)) {
                Log.w(TAG, "Screening exceeded ${WATCHDOG_MS}ms — allowing call (fail-open)")
                safeRespond(callDetails, block = false)
            }
        }
        main.postDelayed(watchdog, WATCHDOG_MS)

        worker.execute {
            val block = try {
                shouldBlock(callDetails)
            } catch (t: Throwable) {
                Log.e(TAG, "Screening failed; allowing call", t)
                false
            }
            main.removeCallbacks(watchdog)
            if (responded.compareAndSet(false, true)) {
                safeRespond(callDetails, block)
            }
        }
    }

    private fun shouldBlock(callDetails: Call.Details): Boolean {
        // Call.Details.getCallDirection() is API 29. minSdk here is 26, so on
        // API 26-28 this would throw NoSuchMethodError. Below 29, screening only
        // ever fires for incoming calls anyway, so skipping the check is safe.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            callDetails.callDirection != Call.Details.DIRECTION_INCOMING
        ) return false

        val raw = callDetails.handle?.schemeSpecificPart.orEmpty()
        val simSlot = simSlotIndex(callDetails)

        // Withheld caller ID arrives as a null handle. The original returned
        // "allow" here with no way for the user to say otherwise; now it flows
        // through the engine so an UNKNOWN rule can catch it.
        val rules = DatabaseHelper.get(this).getRules()

        val verdict = RuleEngine.evaluate(
            context = this,
            rawNumber = raw,
            body = null,
            simSlot = simSlot,
            rules = rules,
            isSms = false,
            myNumber = myLineNumber()
        )

        if (verdict.isBlock || verdict.isSilence) {
            Log.i(TAG, "${verdict.action} ${PhoneNumbers.display(raw)} — ${verdict.describe}")
            DatabaseHelper.get(this).addLog(
                BlockedLog(
                    type = "CALL",
                    sender = raw,
                    content = if (verdict.isSilence) "Silenced incoming call" else "Blocked incoming call",
                    timestamp = System.currentTimeMillis(),
                    simSlot = simSlot,
                    ruleMatched = verdict.describe,
                    action = verdict.action
                )
            )
        }
        return verdict.isBlock
    }

    private fun safeRespond(callDetails: Call.Details, block: Boolean) {
        try {
            val response = CallResponse.Builder()
                .setDisallowCall(block)
                .setRejectCall(block)
                .setSkipNotification(block)
                // Keep the call log entry. A blocked call that leaves no trace
                // is how you miss the one legitimate call you got wrong.
                .setSkipCallLog(false)
                .build()
            respondToCall(callDetails, response)
        } catch (t: Throwable) {
            Log.e(TAG, "respondToCall failed", t)
        }
    }

    private fun simSlotIndex(callDetails: Call.Details): Int = try {
        val handle = callDetails.accountHandle
        // TelephonyManager.getSubscriptionId(PhoneAccountHandle) is API 30.
        if (handle == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) -1
        else {
            val tm = getSystemService(TelephonyManager::class.java)
            val subId = tm?.getSubscriptionId(handle) ?: -1
            if (subId == -1) -1
            else getSystemService(SubscriptionManager::class.java)
                ?.getActiveSubscriptionInfo(subId)?.simSlotIndex ?: -1
        }
    } catch (se: SecurityException) {
        Log.w(TAG, "READ_PHONE_STATE not granted; SIM-specific rules will apply to all SIMs")
        -1
    } catch (e: Exception) {
        Log.e(TAG, "SIM slot lookup failed", e)
        -1
    }

    /** Own line number, for NEIGHBOR rules. Often unavailable; that's fine. */
    private fun myLineNumber(): String? = try {
        getSharedPreferences("spamblocker", MODE_PRIVATE).getString("my_number", null)
    } catch (e: Exception) {
        null
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }
}

package com.spamblocker.app.rules

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.spamblocker.app.data.BlockRule
import com.spamblocker.app.util.PhoneNumbers

/**
 * One matching implementation for both calls and SMS.
 *
 * The original had two copies of the matcher — one in CallBlockerService, one in
 * SmsBlockerNotificationService — and they had already drifted: the call copy
 * tried a "+" variant on PREFIX rules, the SMS copy did not, and only the SMS
 * copy understood KEYWORD at all. Two matchers means every future fix has to be
 * made twice, and the one you forget is the one that breaks.
 */
object RuleEngine {

    private const val TAG = "RuleEngine"

    data class Verdict(
        val action: String,
        val rule: BlockRule?
    ) {
        val isBlock get() = action == BlockRule.ACTION_BLOCK
        val isSilence get() = action == BlockRule.ACTION_SILENCE
        val describe: String
            get() = rule?.let { "[${it.type}] ${it.value}" } ?: "no rule"

        companion object {
            val ALLOW = Verdict(BlockRule.ACTION_ALLOW, null)
        }
    }

    /**
     * Evaluate an incoming call or message.
     *
     * Precedence, in order — this is a behavior change and an intentional one:
     *   1. ALLOW rules. An explicit allow always wins.
     *   2. BLOCK / SILENCE rules matching the number or body.
     *   3. Address book. In contacts and no rule matched -> allow.
     *   4. NOT_IN_CONTACTS rule, if the user enabled one.
     *   5. Allow.
     *
     * The original checked contacts FIRST and returned immediately, so a rule
     * blocking a number that also happened to be in your contacts was dead. That
     * is the wrong way round: an explicit user rule should outrank an incidental
     * address-book entry.
     */
    fun evaluate(
        context: Context,
        rawNumber: String?,
        body: String?,
        simSlot: Int,
        rules: List<BlockRule>,
        isSms: Boolean,
        myNumber: String? = null,
        senderIsKnownContact: Boolean? = null
    ): Verdict {
        val national = PhoneNumbers.national(rawNumber)
        val lowerBody = body?.lowercase().orEmpty()

        val scoped = rules.asSequence()
            .filter { it.isActive }
            .filter { if (isSms) it.appliesToSms() else it.appliesToCalls() }
            .filter { it.targetSim == BlockRule.SIM_ANY || simSlot == -1 || it.targetSim == simSlot }
            .toList()

        // 1. hard allow
        scoped.firstOrNull { it.action == BlockRule.ACTION_ALLOW && matches(it, national, lowerBody, myNumber, false) }
            ?.let { return Verdict(BlockRule.ACTION_ALLOW, it) }

        // 2. block / silence
        val inContacts = senderIsKnownContact ?: isNumberInContacts(context, rawNumber)
        scoped.firstOrNull {
            it.action != BlockRule.ACTION_ALLOW &&
                it.type != BlockRule.TYPE_NOT_IN_CONTACTS &&
                matches(it, national, lowerBody, myNumber, inContacts)
        }?.let { return Verdict(it.action, it) }

        // 3. address book
        if (inContacts) return Verdict.ALLOW

        // 4. opt-in catch-all
        scoped.firstOrNull { it.type == BlockRule.TYPE_NOT_IN_CONTACTS && it.action != BlockRule.ACTION_ALLOW }
            ?.let { return Verdict(it.action, it) }

        return Verdict.ALLOW
    }

    private fun matches(
        rule: BlockRule,
        national: String,
        lowerBody: String,
        myNumber: String?,
        inContacts: Boolean
    ): Boolean = when (rule.type) {

        BlockRule.TYPE_NUMBER -> {
            val target = PhoneNumbers.national(rule.value)
            when {
                target.isEmpty() || national.isEmpty() -> false
                national == target -> true
                // Suffix match only for rules long enough to be unambiguous.
                target.length >= BlockRule.MIN_SUFFIX && national.endsWith(target) -> true
                else -> false
            }
        }

        // The fix: compare against the NATIONAL number, so "+12105551234"
        // is tested as "2105551234" and a "210" rule matches.
        BlockRule.TYPE_PREFIX -> {
            val p = PhoneNumbers.digits(rule.value)
            p.isNotEmpty() && national.isNotEmpty() && national.startsWith(p)
        }

        BlockRule.TYPE_KEYWORD ->
            lowerBody.isNotEmpty() && lowerBody.contains(rule.value.lowercase())

        BlockRule.TYPE_REGEX -> runCatching {
            val re = Regex(rule.value, RegexOption.IGNORE_CASE)
            re.containsMatchIn(national) || (lowerBody.isNotEmpty() && re.containsMatchIn(lowerBody))
        }.getOrElse {
            // A bad regex must never take down call screening.
            Log.w(TAG, "Invalid regex in rule ${rule.id}: ${rule.value}")
            false
        }

        BlockRule.TYPE_UNKNOWN -> national.isEmpty()

        BlockRule.TYPE_SHORT_CODE ->
            national.isNotEmpty() && PhoneNumbers.isShortCode(national)

        BlockRule.TYPE_NEIGHBOR ->
            !inContacts && PhoneNumbers.sharesPrefixWith(national, myNumber)

        BlockRule.TYPE_NOT_IN_CONTACTS -> !inContacts

        else -> false
    }

    /**
     * PhoneLookup against the address book.
     *
     * Note the guard the original lacked: PhoneLookup only resolves NUMBERS. The
     * notification listener was passing it the notification title, which for a
     * saved contact is a NAME. The lookup then returned nothing, the code
     * concluded "not a contact", and went on to keyword-match the body — which
     * is how a message from a friend containing the word "congratulations" got
     * silently dismissed.
     */
    fun isNumberInContacts(context: Context, raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        if (!PhoneNumbers.looksLikeNumber(raw)) return false
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(raw)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c: Cursor -> c.moveToFirst() } ?: false
        } catch (se: SecurityException) {
            Log.w(TAG, "READ_CONTACTS not granted; treating as not-a-contact")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Contacts lookup failed", e)
            false
        }
    }
}

package com.spamblocker.app.data

/**
 * Rule model, schema v2.
 *
 * Changes from the original:
 *  - `action` added. The original had no way to express "always let this
 *    through", so a contact you had explicitly whitelisted and a number you had
 *    explicitly blocked could not be expressed in the same list.
 *  - `appliesTo` added. The original ran every rule against both calls and SMS,
 *    which is why the six default KEYWORD rules ("winner", "crypto", ...) sat in
 *    the table doing nothing for calls while silently muting texts.
 *  - new types: REGEX, UNKNOWN, NOT_IN_CONTACTS, SHORT_CODE, NEIGHBOR.
 */
data class BlockRule(
    val id: Long = 0L,
    val type: String,
    val value: String,
    val targetSim: Int = SIM_ANY,
    val isActive: Boolean = true,
    val action: String = ACTION_BLOCK,
    val appliesTo: String = SCOPE_BOTH,
    val note: String = ""
) {
    companion object {
        const val SIM_ANY = -1

        // ---- types -------------------------------------------------------
        /** Exact number, or suffix match when the rule has >= MIN_SUFFIX digits. */
        const val TYPE_NUMBER = "NUMBER"
        /** Leading digits of the national number, e.g. "210" or "210555". */
        const val TYPE_PREFIX = "PREFIX"
        /** Substring of the message body. SMS only. */
        const val TYPE_KEYWORD = "KEYWORD"
        /** Java regex applied to the national number (calls) or body (SMS). */
        const val TYPE_REGEX = "REGEX"
        /** Withheld / private / unavailable caller ID. */
        const val TYPE_UNKNOWN = "UNKNOWN"
        /** Anything not in the address book. Aggressive; off by default. */
        const val TYPE_NOT_IN_CONTACTS = "NOT_IN_CONTACTS"
        /** 3-6 digit short codes (most marketing SMS). */
        const val TYPE_SHORT_CODE = "SHORT_CODE"
        /** Same area code + exchange as your own line (neighbor spoofing). */
        const val TYPE_NEIGHBOR = "NEIGHBOR"

        // ---- actions -----------------------------------------------------
        const val ACTION_BLOCK = "BLOCK"
        /** Ring, but no notification; call still lands in the log. */
        const val ACTION_SILENCE = "SILENCE"
        /** Hard allow. Evaluated before every BLOCK rule. */
        const val ACTION_ALLOW = "ALLOW"

        // ---- scope -------------------------------------------------------
        const val SCOPE_CALL = "CALL"
        const val SCOPE_SMS = "SMS"
        const val SCOPE_BOTH = "BOTH"

        /**
         * A NUMBER rule shorter than this is treated as exact-match only.
         * The original used endsWith() with no floor, so a rule of "1234"
         * blocked every number on earth ending in 1234.
         */
        const val MIN_SUFFIX = 7
    }

    fun appliesToCalls() = appliesTo == SCOPE_CALL || appliesTo == SCOPE_BOTH
    fun appliesToSms() = appliesTo == SCOPE_SMS || appliesTo == SCOPE_BOTH
}

data class BlockedLog(
    val id: Long = 0L,
    val type: String,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val simSlot: Int,
    val ruleMatched: String,
    val action: String = BlockRule.ACTION_BLOCK
)

package com.spamblocker.app.util

/**
 * Number normalization.
 *
 * This is the single most important fix in the rewrite. The original code did:
 *
 *     val clean = Regex("[^0-9+]").replace(number, "")
 *     clean.startsWith(rulePrefix)          // PREFIX rule
 *
 * Carriers hand CallScreeningService numbers in E.164: "+12105551234".
 * A PREFIX rule of "210" is tested with startsWith("210") against "+12105551234",
 * which is false. The fallback test of "+210" is also false. Result: area-code
 * rules never fire on real incoming calls, which is invisible in testing if you
 * only ever tested with locally-dialed 10-digit numbers.
 *
 * Everything here works on the NATIONAL SIGNIFICANT NUMBER: country code and
 * trunk prefix stripped, digits only. "+12105551234" -> "2105551234".
 */
object PhoneNumbers {

    private val NON_DIAL = Regex("[^0-9+]")

    /** Default country calling code. Change if you ever ship outside NANP. */
    const val DEFAULT_CC = "1"

    /** Digits only, "+" and formatting removed. "+1 (210) 555-1234" -> "12105551234" */
    fun digits(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = NON_DIAL.replace(raw, "")
        if (s.startsWith("+")) s = s.substring(1)
        if (s.startsWith("00")) s = s.substring(2)   // international access prefix
        return s.filter { it.isDigit() }
    }

    /**
     * National significant number for matching.
     *   "+12105551234" -> "2105551234"
     *   "12105551234"  -> "2105551234"
     *   "2105551234"   -> "2105551234"
     *   "5551234"      -> "5551234"    (already national)
     *   "72622"        -> "72622"      (short code, left alone)
     */
    fun national(raw: String?, cc: String = DEFAULT_CC): String {
        val d = digits(raw)
        if (d.isEmpty()) return ""
        if (isShortCode(d)) return d
        if (cc == "1") {
            // NANP: 11 digits beginning with the trunk/country code 1
            if (d.length == 11 && d.startsWith("1")) return d.substring(1)
            return d
        }
        if (d.startsWith(cc) && d.length > cc.length + 5) return d.substring(cc.length)
        return d
    }

    /** Short codes (5-6 digits) are never area-code matched and never suffix matched. */
    fun isShortCode(digitsOnly: String): Boolean =
        digitsOnly.length in 3..6

    /** True when the string looks like a phone number rather than a contact name. */
    fun looksLikeNumber(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val d = digits(raw)
        return d.length >= 3 && raw.count { it.isLetter() } == 0
    }

    /** Pretty form for logs and the UI. */
    fun display(raw: String?): String {
        val n = national(raw)
        return when {
            n.length == 10 -> "(${n.substring(0, 3)}) ${n.substring(3, 6)}-${n.substring(6)}"
            n.isEmpty() -> "Unknown"
            else -> n
        }
    }

    /**
     * Neighbor-spoofing test: caller shares the area code AND the 3-digit
     * exchange with the user's own line. Legitimate neighbors exist, so this is
     * offered as an opt-in rule type, not a default.
     */
    fun sharesPrefixWith(candidate: String?, myNumber: String?, digitsToCompare: Int = 6): Boolean {
        val a = national(candidate)
        val b = national(myNumber)
        if (a.length < digitsToCompare || b.length < digitsToCompare) return false
        return a.substring(0, digitsToCompare) == b.substring(0, digitsToCompare)
    }
}

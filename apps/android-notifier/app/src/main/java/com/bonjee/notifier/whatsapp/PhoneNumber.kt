package com.bonjee.notifier.whatsapp

/**
 * One canonical phone-number format for the whole WhatsApp layer.
 *
 * Everything is stored and compared as E.164 (`+2348031234567`) so the same
 * person cannot appear twice because one message arrived as `0803 123 4567`
 * and another as `+234 803 123 4567`.
 */
object PhoneNumber {

    const val DEFAULT_COUNTRY_CODE = "234"

    /**
     * Normalises a number to E.164, or returns "" when there is not enough to
     * work with. Never throws: notification titles are arbitrary text.
     */
    fun normalize(raw: String?, defaultCountryCode: String = DEFAULT_COUNTRY_CODE): String {
        if (raw == null) return ""
        val text = raw.trim()
        if (text.isEmpty()) return ""

        val cc = defaultCountryCode.filter { it.isDigit() }
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) return ""

        val international = when {
            // "+234 803..." or "+1 (555)..."
            text.startsWith("+") -> digits
            // "00234 803..." dialling prefix
            text.startsWith("00") -> digits.drop(2)
            // already carries the country code without a plus
            cc.isNotEmpty() && digits.startsWith(cc) && digits.length >= cc.length + 7 -> digits
            // local trunk zero: "0803 123 4567"
            digits.startsWith("0") && cc.isNotEmpty() -> cc + digits.drop(1)
            // local number typed without the trunk zero: "803 123 4567"
            cc.isNotEmpty() && digits.length in 7..10 -> cc + digits
            else -> digits
        }

        if (international.length < 8) return ""
        return "+$international"
    }

    fun same(a: String?, b: String?, defaultCountryCode: String = DEFAULT_COUNTRY_CODE): Boolean {
        val left = normalize(a, defaultCountryCode)
        val right = normalize(b, defaultCountryCode)
        return left.isNotEmpty() && left == right
    }

    /** Readable form for screens: +234 803 123 4567 */
    fun pretty(e164: String): String {
        val digits = e164.filter { it.isDigit() }
        if (digits.length < 8) return e164
        return "+" + digits
    }

    /** True when a notification title is itself a phone number. */
    fun looksLikeNumber(raw: String?): Boolean {
        if (raw == null) return false
        val compact = raw.filter { it.isDigit() || it == '+' }
        return compact.length >= 7 && compact.length >= raw.trim().length - 4
    }
}

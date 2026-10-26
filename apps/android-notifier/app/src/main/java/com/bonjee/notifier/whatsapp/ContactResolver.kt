package com.bonjee.notifier.whatsapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Bridges WhatsApp notification text to a real phone number.
 *
 * WhatsApp puts the contact's display name in the notification title; for an
 * unsaved number it puts the number itself. This resolver covers both, and
 * degrades to "no number" rather than guessing when the name is ambiguous.
 * Without READ_CONTACTS it simply cannot resolve names - the message is still
 * shown, but placing a WhatsApp reply needs a number.
 */
object ContactResolver {

    data class Match(val name: String, val number: String)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Display name for a number we already know, or null when it is not saved. */
    fun nameFor(context: Context, rawNumber: String?): String? {
        val number = PhoneNumber.normalize(rawNumber)
        if (number.isEmpty() || !hasPermission(context)) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.trim().orEmpty().ifEmpty { null } else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Looks a name up in the phone book. Only an unambiguous single number is
     * returned: picking the wrong John would send a stranger a message.
     */
    fun numberFor(context: Context, name: String?): Match? {
        val wanted = name?.trim().orEmpty()
        if (wanted.isEmpty() || wanted.length < 2) return null
        if (!hasPermission(context)) return null
        return try {
            val uri = android.net.Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                android.net.Uri.encode(wanted),
            )
            val numbers = LinkedHashSet<String>()
            var display = wanted
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext() && numbers.size < 4) {
                    val n = PhoneNumber.normalize(c.getString(1))
                    if (n.isEmpty()) continue
                    if (c.getString(0).isNotBlank()) display = c.getString(0)
                    numbers.add(n)
                }
            }
            if (numbers.size == 1) Match(display, numbers.first()) else null
        } catch (t: Throwable) {
            null
        }
    }

    /** Suggestion list for the rule editor's "add from contacts" picker. */
    fun search(context: Context, query: String, limit: Int = 50): List<Match> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<Match>()
        val seen = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val uri = if (query.isBlank()) {
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        } else {
            android.net.Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                android.net.Uri.encode(query.trim()),
            )
        }
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = PhoneNumber.normalize(c.getString(1))
                    if (number.isEmpty() || !seen.add(number)) continue
                    out.add(Match(name.ifEmpty { PhoneNumber.pretty(number) }, number))
                }
            }
        } catch (t: Throwable) {
            // Provider unavailable (work profile, revoked permission).
        }
        return out
    }
}

package com.bonjee.notifier.whatsapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bonjee.notifier.Prefs
import com.bonjee.notifier.R
import com.bonjee.notifier.ui.MessageInboxActivity
import com.bonjee.notifier.ui.ReplyActivity

/**
 * The high-priority notification for an important WhatsApp message.
 *
 * This is a local notification built from the notification listener event, so
 * no sensitive text travels through a push provider. If the user prefers a
 * private lock screen, the body is hidden there while staying visible in the
 * app. Reply actions only *open* the reply screen - they never send.
 */
object ImportantNotifier {

    const val CHANNEL_IMPORTANT = "jarvis_important_messages"
    const val CHANNEL_MESSAGES = "jarvis_whatsapp_messages"

    @Volatile
    private var channelsReady = false

    fun ensureChannels(context: Context) {
        if (channelsReady) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val important = NotificationChannel(
                CHANNEL_IMPORTANT,
                "Important WhatsApp messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Messages from contacts you marked as Important."
                enableVibration(true)
                // Deliberately no setBypassDnd: Android only allows that for
                // notification-policy access, and we do not claim to override it.
            }
            val normal = NotificationChannel(
                CHANNEL_MESSAGES,
                "Other WhatsApp messages",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Messages from contacts outside your Important list."
            }
            manager.createNotificationChannel(important)
            manager.createNotificationChannel(normal)
        }
        channelsReady = true
    }

    fun canPost(context: Context): Boolean {
        val allowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return allowed && manager?.areNotificationsEnabled() == true
    }

    /**
     * Posts (or updates) the notification for [record].
     *
     * @param heldReason when speech was suppressed, shown as a quiet footnote so
     *   the user knows the phone followed their rules rather than failing.
     */
    fun show(
        context: Context,
        record: MessageRecord,
        prefs: Prefs,
        heldReason: String? = null,
    ): Boolean {
        if (!canPost(context)) return false
        ensureChannels(context)

        val important = record.isImportant && record.category == ContactCategory.IMPORTANT
        val channel = if (important) CHANNEL_IMPORTANT else CHANNEL_MESSAGES
        val showText = prefs.showMessageContent
        val sender = record.displaySender
        val body = if (showText) record.text else "New WhatsApp message"

        val open = Intent(context, ReplyActivity::class.java).apply {
            putExtra(ReplyActivity.EXTRA_MESSAGE_ID, record.messageId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId(record.messageId),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val selfReply = Intent(context, ReplyActivity::class.java).apply {
            putExtra(ReplyActivity.EXTRA_MESSAGE_ID, record.messageId)
            putExtra(ReplyActivity.EXTRA_SELF_ONLY, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val selfPending = PendingIntent.getActivity(
            context,
            notificationId(record.messageId) + 1,
            selfReply,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val inbox = Intent(context, MessageInboxActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val inboxPending = PendingIntent.getActivity(
            context,
            notificationId(record.messageId) + 2,
            inbox,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (important) "★ $sender" else sender)
            .setContentText(if (showText) body.take(200) else body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (showText) body else body))
            .setWhen(record.receivedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (important) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("New message from $sender")
                    .setContentText("Open JARVIS to read it")
                    .build(),
            )
            .addAction(0, "REPLY", selfPending)
            .addAction(0, "JARVIS REPLY", contentIntent)
            .setSubText(if (record.messageType == "text") null else record.messageType.uppercase())
            .setGroup("jarvis_whatsapp")

        if (!heldReason.isNullOrBlank()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$body\n\n$heldReason")
                    .setSummaryText("Held by your audio rules"),
            )
        }

        // A small footer action opens the conversation list, which is also where
        // the user can change the contact's category.
        builder.addAction(0, "ALL MESSAGES", inboxPending)

        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId(record.messageId), builder.build())
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    fun cancel(context: Context, messageId: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId(messageId))
        } catch (t: Throwable) {
            // Nothing to do.
        }
    }

    /** Opens the WhatsApp chat for a message; never sends anything by itself. */
    fun openWhatsApp(context: Context, record: MessageRecord): Boolean {
        val digits = record.sender.filter { it.isDigit() }
        if (digits.length < 8) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun notificationId(messageId: String): Int = 7000 + (messageId.hashCode() and 0x0FFF)
}

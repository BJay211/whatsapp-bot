package com.bonjee.notifier.whatsapp

/**
 * Whether an important message may be spoken, and how much of it.
 *
 * Android does not let an app override Do Not Disturb or the ringer, so the
 * honest model is: DND/silent suppress by default, with an explicit earphone
 * exception that only applies while a private output device is connected.
 */
data class AudioInput(
    val important: Boolean,
    val announcementsEnabled: Boolean,
    val ringerSilent: Boolean,
    val dndActive: Boolean,
    val earphoneConnected: Boolean,
    val earphoneException: Boolean,
    val quietHours: Boolean,
    val callInProgress: Boolean,
    /** User preference: read the message body, not only the sender. */
    val readBody: Boolean,
    /** User preference: allow body text when playing over the speaker. */
    val allowBodyOnSpeaker: Boolean,
)

data class AudioDecision(
    val speak: Boolean,
    val includeBody: Boolean,
    val reason: String,
)

object AudioPolicy {

    /**
     * Order matters: privacy and interruptions are checked before convenience.
     * The result never claims DND was bypassed - when DND blocks us, the reason
     * says so and the notification still appears silently.
     */
    fun decide(input: AudioInput): AudioDecision {
        if (!input.important) return AudioDecision(false, false, "not an important contact")
        if (!input.announcementsEnabled) return AudioDecision(false, false, "announcements are off")
        if (input.callInProgress) return AudioDecision(false, false, "a call is in progress")
        if (input.quietHours) return AudioDecision(false, false, "quiet hours")

        val privateAudio = input.earphoneConnected
        val earphonePass = input.earphoneException && privateAudio

        if (input.dndActive && !earphonePass) {
            return AudioDecision(false, false, "Do Not Disturb is on (Android blocks announcements)")
        }
        if (input.ringerSilent && !earphonePass) {
            return AudioDecision(false, false, "the phone is on silent")
        }

        // Body text is only read aloud through a private route, unless the user
        // explicitly allowed speaker playback of message content.
        val body = input.readBody && (privateAudio || input.allowBodyOnSpeaker)
        val reason = when {
            earphonePass && (input.dndActive || input.ringerSilent) -> "earphone exception"
            privateAudio -> "earphones connected"
            else -> "speaker"
        }
        return AudioDecision(true, body, reason)
    }

    /**
     * What the screen shows when speech is suppressed, so the user understands
     * the phone was not broken - it was following their own rules.
     */
    fun suppressionHint(decision: AudioDecision): String? =
        if (decision.speak) null else "Announcement held: ${decision.reason}."
}

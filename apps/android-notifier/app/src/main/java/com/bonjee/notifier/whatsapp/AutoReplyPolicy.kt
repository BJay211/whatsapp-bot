package com.bonjee.notifier.whatsapp

/**
 * What the app may do with an inbound message, before any network call.
 *
 * The app never decides on its own to auto-send. A decision of [AskBackend]
 * only means "ask the workflow whether it will auto-reply"; the workflow still
 * performs its own authorisation and reports what it actually sent.
 */
sealed class AutoReplyDecision {
    abstract val reason: String

    /** Do nothing automatically. The message is still shown to the user. */
    data class Ignore(override val reason: String) : AutoReplyDecision()

    /** Send one approved template through the backend. Never a free-form edit. */
    data class SendTemplate(val text: String, override val reason: String) : AutoReplyDecision()

    /** Let the workflow generate/send under its own policy. */
    data class AskBackend(override val reason: String) : AutoReplyDecision()
}

/**
 * Everything the decision depends on. All of it is explicit input, so the
 * policy has no hidden state and can be unit-checked exhaustively.
 */
data class AutoReplyInput(
    val contact: ContactRule,
    val globalEnabled: Boolean,
    /**
     * True only when the backend has explicitly authorised auto-reply for this
     * contact (returned by `contact.settings.update` / the inbound event).
     * The app must not treat its own local toggle as authorisation.
     */
    val serverAuthorized: Boolean,
    val isGroup: Boolean = false,
    val isFromMe: Boolean = false,
    val text: String = "",
    val blockedWords: List<String> = emptyList(),
)

object AutoReplyPolicy {

    private val risky = listOf(
        "otp", "one time password", "verification code", "pin", "password",
        "bank", "account number", "cvv", "transfer", "loan", "crypto",
        "send money", "gift card", "click this link", "urgent payment",
    )

    fun decide(input: AutoReplyInput): AutoReplyDecision {
        val contact = input.contact

        // 1. Global kill switch always wins - one tap stops every auto-reply.
        if (!input.globalEnabled) return AutoReplyDecision.Ignore("auto-reply is switched off for the whole app")

        // 2. The explicit per-contact switch.
        if (!contact.autoReplyEnabled || contact.autoReplyMode == AutoReplyMode.OFF) {
            return AutoReplyDecision.Ignore("auto-reply is off for ${contact.displayName}")
        }

        // 3. Only the Auto-Reply bucket may answer. Important is about attention,
        //    not permission, and Other numbers must never be answered unattended.
        if (contact.category != ContactCategory.AUTO_REPLY) {
            return AutoReplyDecision.Ignore("${contact.displayName} is not in the Auto-Reply list")
        }

        // 4. Server-side authorisation is required. A local toggle alone is not enough.
        if (!input.serverAuthorized) {
            return AutoReplyDecision.Ignore("the workflow has not authorised auto-reply for ${contact.displayName}")
        }

        // 5. Never auto-answer our own messages or group traffic.
        if (input.isFromMe) return AutoReplyDecision.Ignore("message was sent by you")
        if (input.isGroup) return AutoReplyDecision.Ignore("group messages are never auto-replied")

        // 6. Never auto-answer anything that looks like a credential or money request.
        val lower = input.text.lowercase()
        val hit = (risky + input.blockedWords.map { it.lowercase() }).firstOrNull {
            it.isNotBlank() && lower.contains(it)
        }
        if (hit != null) {
            return AutoReplyDecision.Ignore("message mentions \"$hit\" - needs you to answer")
        }

        return when (contact.autoReplyMode) {
            AutoReplyMode.TEMPLATE -> {
                val template = contact.template.trim()
                if (template.isEmpty()) {
                    AutoReplyDecision.Ignore("no approved template is set for ${contact.displayName}")
                } else {
                    AutoReplyDecision.SendTemplate(
                        template,
                        "approved template for ${contact.displayName}",
                    )
                }
            }
            AutoReplyMode.AI -> AutoReplyDecision.AskBackend(
                "AI auto-reply authorised by the workflow for ${contact.displayName}",
            )
            AutoReplyMode.OFF -> AutoReplyDecision.Ignore("auto-reply mode is off")
        }
    }

    /** One audit line per decision, stored next to the message. */
    fun describe(decision: AutoReplyDecision): String = when (decision) {
        is AutoReplyDecision.Ignore -> "ignored: ${decision.reason}"
        is AutoReplyDecision.SendTemplate -> "template sent: ${decision.reason}"
        is AutoReplyDecision.AskBackend -> "backend asked: ${decision.reason}"
    }
}

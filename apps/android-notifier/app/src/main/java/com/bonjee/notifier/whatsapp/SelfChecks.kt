package com.bonjee.notifier.whatsapp

import com.bonjee.notifier.device.CommandChain

/**
 * Executable specification for the WhatsApp rules.
 *
 * There is no JUnit in this project's offline dependency cache and no device
 * attached, so the important invariants are checked by plain Kotlin that runs
 * both in the app (Actions screen) and headlessly on a desktop JVM:
 *
 *   kotlinc whatsapp -include-runtime -d checks.jar && java -jar checks.jar
 *
 * Every check is a pure function call - no Android, no network, no clock.
 */
object SelfChecks {

    data class Check(val name: String, val ok: Boolean, val detail: String = "")

    private const val NG = "234"

    fun run(): List<Check> = buildList {
        addAll(normalisation())
        addAll(contacts())
        addAll(autoReply())
        addAll(states())
        addAll(audio())
        addAll(conversation())
        addAll(chains())
    }

    /** Runs [run] and prints a report; returns true when everything passed. */
    fun report(results: List<Check> = run()): Boolean {
        val passed = results.count { it.ok }
        results.forEach { r ->
            println((if (r.ok) "PASS  " else "FAIL  ") + r.name + if (r.detail.isBlank()) "" else "  [${r.detail}]")
        }
        println("$passed/${results.size} checks passed")
        return passed == results.size
    }

    // ---------------------------------------------------------------- numbers

    private fun normalisation(): List<Check> {
        val out = ArrayList<Check>()
        fun add(name: String, ok: Boolean, detail: String = "") = out.add(Check(name, ok, detail))

        val expected = "+2348031234567"
        val variants = listOf(
            "+234 803 123 4567",
            "+2348031234567",
            "0803 123 4567",
            "8031234567",
            "2348031234567",
            "00234 803 123 4567",
            "0803-123-4567",
        )
        for (v in variants) {
            val got = PhoneNumber.normalize(v, NG)
            add("normalise \"$v\" -> E.164", got == expected, got)
        }
        val us = PhoneNumber.normalize("+1 (555) 010-9999", NG)
        add("keep a foreign +1 number", us == "+15550109999", us)
        add("empty input is rejected", PhoneNumber.normalize("", NG).isEmpty())
        add("text is rejected", PhoneNumber.normalize("mum", NG).isEmpty())
        add("too-short number is rejected", PhoneNumber.normalize("12345", NG).isEmpty())
        add("same() ignores formatting", PhoneNumber.same("0803 123 4567", "+2348031234567", NG))
        add("looksLikeNumber(\"+2348031234567\")", PhoneNumber.looksLikeNumber("+2348031234567"))
        add("looksLikeNumber(\"Mum\") is false", !PhoneNumber.looksLikeNumber("Mum"))
        return out
    }

    // --------------------------------------------------------------- contacts

    private fun contacts(): List<Check> {
        val out = ArrayList<Check>()
        fun add(name: String, ok: Boolean, detail: String = "") = out.add(Check(name, ok, detail))

        var rules = ContactRules()
        rules = rules.upsert(ContactRule("0803 123 4567", name = "Ada", category = ContactCategory.IMPORTANT), NG)
        rules = rules.upsert(ContactRule("+2348031234567", name = "", category = ContactCategory.IMPORTANT), NG)
        add("no duplicate contact across formats", rules.items.size == 1, "size=${rules.items.size}")
        add("blank name does not erase the known name", rules.items[0].name == "Ada")
        add("stored number is E.164", rules.items[0].number == "+2348031234567")

        rules = rules.setCategory("08031234567", ContactCategory.AUTO_REPLY, at = 5L, defaultCountryCode = NG)
        add("promote Important -> Auto-Reply", rules.byCategory(ContactCategory.AUTO_REPLY).size == 1)

        val promoted = rules.setCategory("0703 999 0000", ContactCategory.IMPORTANT, name = "New", at = 7L, defaultCountryCode = NG)
        add("unknown number can be promoted", promoted.find("07039990000", NG)?.category == ContactCategory.IMPORTANT)

        val unknown = ContactRules().ruleFor("0803 000 1111", NG)
        add("unknown sender defaults to Other", unknown?.category == ContactCategory.OTHER)
        add("unknown sender cannot auto-reply", unknown?.autoReplyEnabled == false)

        val badImportant = ContactRule(
            "+2348031234567",
            category = ContactCategory.IMPORTANT,
            autoReplyEnabled = true,
            autoReplyMode = AutoReplyMode.AI,
            priority = true,
        ).sanitized(NG)
        add("Important never implies auto-reply", badImportant?.autoReplyEnabled == false)
        add("Important keeps priority", badImportant?.priority == true)

        val otherAuto = ContactRule("+2348031234567", autoReplyEnabled = true, autoReplyMode = AutoReplyMode.AI).sanitized(NG)
        add("Other contact cannot keep auto-reply on", otherAuto?.autoReplyEnabled == false)

        val promotedAuto = ContactRules()
            .toggleAutoReply("0803 123 4567", enabled = true, mode = AutoReplyMode.TEMPLATE, at = 9L, defaultCountryCode = NG)
        add("enabling auto-reply moves contact to Auto-Reply", promotedAuto.find("+2348031234567", NG)?.category == ContactCategory.AUTO_REPLY)
        val offAgain = promotedAuto.toggleAutoReply("08031234567", enabled = false, mode = AutoReplyMode.AI, at = 10L, defaultCountryCode = NG)
        add("disabling clears the mode", offAgain.find("+2348031234567", NG)?.autoReplyMode == AutoReplyMode.OFF)

        val local = ContactRules().upsert(ContactRule("+2348031234567", name = "Local", category = ContactCategory.IMPORTANT, updatedAt = 100L), NG)
        val merged = local.mergeRemote(listOf(ContactRule("+2348031234567", name = "Remote", category = ContactCategory.OTHER, updatedAt = 50L)), NG)
        add("stale remote sync cannot undo a local edit", merged.find("+2348031234567", NG)?.category == ContactCategory.IMPORTANT)

        add("remove() drops the matching number", rules.remove("08031234567", NG).find("+2348031234567", NG) == null)
        add("remove() of an unknown number is a no-op", rules.remove("+15550001111", NG).items.size == rules.items.size)
        add("sortedForDisplay puts Important first", run {
            val s = ContactRules(
                listOf(
                    ContactRule("+2348000000001", name = "Zed", category = ContactCategory.OTHER),
                    ContactRule("+2348000000002", name = "Ada", category = ContactCategory.IMPORTANT),
                ),
            ).sortedForDisplay()
            s.first().category == ContactCategory.IMPORTANT
        })
        add("counts() totals every category", ContactRules().counts().keys == ContactCategory.entries.toSet())
        return out
    }

    // -------------------------------------------------------------- auto reply

    private fun autoReply(): List<Check> {
        val out = ArrayList<Check>()
        fun add(name: String, ok: Boolean, detail: String = "") = out.add(Check(name, ok, detail))

        fun contact(
            category: ContactCategory = ContactCategory.AUTO_REPLY,
            enabled: Boolean = true,
            mode: AutoReplyMode = AutoReplyMode.AI,
            template: String = "",
        ) = ContactRule(
            number = "+2348031234567",
            name = "Ada",
            category = category,
            autoReplyEnabled = enabled,
            autoReplyMode = mode,
            template = template,
        )

        fun decide(
            c: ContactRule,
            global: Boolean = true,
            auth: Boolean = true,
            group: Boolean = false,
            mine: Boolean = false,
            text: String = "hello",
        ) = AutoReplyPolicy.decide(
            AutoReplyInput(c, global, auth, isGroup = group, isFromMe = mine, text = text),
        )

        add("kill switch blocks everything", decide(contact(), global = false) is AutoReplyDecision.Ignore)
        add("per-contact off blocks", decide(contact(enabled = false)) is AutoReplyDecision.Ignore)
        add("mode OFF blocks", decide(contact(mode = AutoReplyMode.OFF)) is AutoReplyDecision.Ignore)
        add("Important contact is not auto-replied", decide(contact(category = ContactCategory.IMPORTANT, enabled = true)) is AutoReplyDecision.Ignore)
        add("Other contact is not auto-replied", decide(contact(category = ContactCategory.OTHER, enabled = true)) is AutoReplyDecision.Ignore)
        add("missing server authorisation blocks", decide(contact(), auth = false) is AutoReplyDecision.Ignore)
        add("group message is never auto-replied", decide(contact(), group = true) is AutoReplyDecision.Ignore)
        add("own message is never auto-replied", decide(contact(), mine = true) is AutoReplyDecision.Ignore)
        add("OTP/bank language escalates to the user", decide(contact(), text = "send me the otp now") is AutoReplyDecision.Ignore)
        add("custom blocked word escalates", run {
            val d = AutoReplyPolicy.decide(
                AutoReplyInput(contact(), true, true, text = "about the invoice", blockedWords = listOf("invoice")),
            )
            d is AutoReplyDecision.Ignore
        })
        add("AI mode asks the backend", decide(contact()) is AutoReplyDecision.AskBackend)
        add("template mode needs a template", decide(contact(mode = AutoReplyMode.TEMPLATE)) is AutoReplyDecision.Ignore)
        add("template mode sends the exact template", run {
            val d = decide(contact(mode = AutoReplyMode.TEMPLATE, template = "I'll call you back."))
            d is AutoReplyDecision.SendTemplate && d.text == "I'll call you back."
        })
        add("decision has an audit line", AutoReplyPolicy.describe(decide(contact())).startsWith("backend asked:"))
        return out
    }

    // ----------------------------------------------------------------- states

    private fun states(): List<Check> {
        val out = ArrayList<Check>()
        fun add(name: String, ok: Boolean, detail: String = "") = out.add(Check(name, ok, detail))

        add("received cannot jump to sent", !ReplyFlow.canTransition(MessageState.RECEIVED, MessageState.SENT))
        add("received -> pending_approval", ReplyFlow.canTransition(MessageState.RECEIVED, MessageState.PENDING_APPROVAL))
        add("pending -> approved", ReplyFlow.canTransition(MessageState.PENDING_APPROVAL, MessageState.APPROVED))
        add("approved -> sending", ReplyFlow.canTransition(MessageState.APPROVED, MessageState.SENDING))
        add("approved cannot jump to sent", !ReplyFlow.canTransition(MessageState.APPROVED, MessageState.SENT))
        add("sending -> sent", ReplyFlow.canTransition(MessageState.SENDING, MessageState.SENT))
        add("sending -> failed", ReplyFlow.canTransition(MessageState.SENDING, MessageState.FAILED))
        add("approved draft can be superseded", ReplyFlow.canTransition(MessageState.APPROVED, MessageState.PENDING_APPROVAL))
        add("failed must be re-approved before retry", !ReplyFlow.canTransition(MessageState.FAILED, MessageState.SENDING))
        add("a status query can resolve failed -> sent", ReplyFlow.canTransition(MessageState.FAILED, MessageState.SENT))
        add("failed -> pending_approval", ReplyFlow.canTransition(MessageState.FAILED, MessageState.PENDING_APPROVAL))
        add("sent is terminal", ReplyFlow.isTerminal(MessageState.SENT))
        add("cancelled is terminal", ReplyFlow.isTerminal(MessageState.CANCELLED))
        add("sending needs a status query", ReplyFlow.requiresStatusQuery(MessageState.SENDING))

        add("backend 'delivered' resolves to sent", ReplyFlow.resolveRemoteStatus("delivered") == MessageState.SENT)
        add("backend 'queued' resolves to sending", ReplyFlow.resolveRemoteStatus("queued") == MessageState.SENDING)
        add("unknown status resolves to null", ReplyFlow.resolveRemoteStatus("banana") == null)

        val id = RequestIds.send("msg-1")
        add("send request id is stable for replays", id == RequestIds.send("msg-1") && id == "send-msg-1")
        add("retry request id differs", RequestIds.send("msg-1", 1) != id)
        add("fresh request id is unique", RequestIds.new() != RequestIds.new())

        val received = MessageRecord("m1", sender = "+2348031234567", text = "hi", receivedAt = 1L)
        add("record starts as received", received.state == MessageState.RECEIVED)
        add("illegal transition returns null", received.transition(MessageState.SENT) == null)
        val pending = received.transition(MessageState.PENDING_APPROVAL, draft = "Hello", at = 2L)
        add("legal transition keeps the draft", pending?.draft == "Hello" && pending.state == MessageState.PENDING_APPROVAL)
        add("draft alone is not sent", pending?.isConfirmedSent == false)

        var box = MessageBox()
        box = box.upsert(received)
        box = box.upsert(MessageRecord("m1", sender = "+2348031234567", text = "hi", receivedAt = 1L, state = MessageState.PENDING_APPROVAL, draft = "Hello"))
        add("duplicate message id is stored once", box.items.size == 1)
        add("latest state wins for a duplicate", box.find("m1")?.state == MessageState.PENDING_APPROVAL)

        box = box.upsert(MessageRecord("m2", sender = "+2348031234567", text = "second", receivedAt = 5L))
        add("recent() is newest first", box.recent().first().messageId == "m2")
        add("conversation groups formatted numbers", box.forSender("0803 123 4567", NG).size == 2)
        add("pending approval list is correct", box.pendingApproval().map { it.messageId } == listOf("m1"))

        val sending = box.upsert(
            MessageRecord("m3", sender = "+2348031234567", state = MessageState.SENDING, receivedAt = 9L),
        )
        add("needsStatusCheck finds in-flight sends", sending.needsStatusCheck().single().messageId == "m3")
        val confirmed = sending.applyRemoteStatus("m3", "sent")
        add("status query resolves in-flight send", confirmed?.find("m3")?.state == MessageState.SENT)
        val bogus = sending.applyRemoteStatus("m3", "???")
        add("unknown status leaves the record untouched", bogus == null)
        add("status for an unknown message is ignored", sending.applyRemoteStatus("nope", "sent") == null)
        return out
    }

    // ------------------------------------------------------------------ audio

    private fun audio(): List<Check> {
        val out = ArrayList<Check>()
        fun add(name: String, ok: Boolean, detail: String = "") = out.add(Check(name, ok, detail))

        fun input(
            important: Boolean = true,
            enabled: Boolean = true,
            silent: Boolean = false,
            dnd: Boolean = false,
            headset: Boolean = false,
            exception: Boolean = true,
            quiet: Boolean = false,
            call: Boolean = false,
            readBody: Boolean = true,
            speakerBody: Boolean = false,
        ) = AudioInput(important, enabled, silent, dnd, headset, exception, quiet, call, readBody, speakerBody)

        add("normal case speaks", AudioPolicy.decide(input()).speak)
        add("non-important never speaks", !AudioPolicy.decide(input(important = false)).speak)
        add("announcements off never speaks", !AudioPolicy.decide(input(enabled = false)).speak)
        add("DND suppresses by default", !AudioPolicy.decide(input(dnd = true)).speak)
        add("silent mode suppresses by default", !AudioPolicy.decide(input(silent = true)).speak)
        add("DND + earphones + exception speaks", AudioPolicy.decide(input(dnd = true, headset = true)).speak)
        add("DND + exception but no earphones stays quiet", !AudioPolicy.decide(input(dnd = true, headset = false)).speak)
        add("exception off + earphones stays quiet under DND", !AudioPolicy.decide(input(dnd = true, headset = true, exception = false)).speak)
        add("quiet hours suppress", !AudioPolicy.decide(input(quiet = true)).speak)
        add("active call suppresses", !AudioPolicy.decide(input(call = true)).speak)
        add("body is read on earphones", AudioPolicy.decide(input(headset = true)).includeBody)
        add("body is hidden on speaker by default", !AudioPolicy.decide(input()).includeBody)
        add("body allowed on speaker when opted in", AudioPolicy.decide(input(speakerBody = true)).includeBody)
        add("sender-only mode never includes body", !AudioPolicy.decide(input(headset = true, readBody = false)).includeBody)
        add("suppression explains itself", AudioPolicy.suppressionHint(AudioPolicy.decide(input(dnd = true)))?.contains("Do Not Disturb") == true)
        add("speaking has no suppression hint", AudioPolicy.suppressionHint(AudioPolicy.decide(input())) == null)
        return out
    }

    // ------------------------------------------------------------ conversation

    private fun conversation(): List<Check> {
        val out = ArrayList<Check>()
        fun add(name: String, ok: Boolean, detail: String = "") = out.add(Check(name, ok, detail))

        val a = MessageRecord("a", conversationId = "c1", sender = "+2348031234567", senderName = "", receivedAt = 1L)
        val b = MessageRecord("b", conversationId = "c1", sender = "+2348031234567", senderName = "Ada", receivedAt = 2L)
        add("sender falls back to a pretty number", a.displaySender == "+2348031234567")
        add("known name is used when present", b.displaySender == "Ada")
        add("category survives a merge", MessageBox().upsert(b.copy(category = ContactCategory.IMPORTANT)).find("b")?.isImportant == true)
        add("counts cover every state", MessageBox().counts().keys == MessageState.entries.toSet())
        add("remove drops one message", MessageBox().upsert(a).remove("a").items.isEmpty())
        add("blank sender cannot match a conversation", MessageBox().upsert(a).forSender("", NG).isEmpty())
        return out
    }

    // ------------------------------------------------------------ command chains

    private fun chains(): List<Check> {
        val out = ArrayList<Check>()
        fun add(name: String, ok: Boolean, detail: String = "") = out.add(Check(name, ok, detail))

        val twoThen = CommandChain.split("open whatsapp then message John")
        add("then splits two steps", twoThen == listOf("open whatsapp", "message John"), twoThen.toString())

        val twoAnd = CommandChain.split("open whatsapp and message John")
        add("open-app-and splits two steps", twoAnd == listOf("open whatsapp", "message John"), twoAnd.toString())

        val three = CommandChain.split("open whatsapp and search for John on whatsapp then open the chat")
        add(
            "search then open chat is three steps",
            three == listOf("open whatsapp", "search for John on whatsapp", "open the chat"),
            three.toString(),
        )

        add("plain and inside a message is not a chain", CommandChain.split("message John saying I will come and see you").size == 1)
        add("then inside a message body is not a chain", CommandChain.split("message John saying I will call you then come home").size == 1)
        add("next song is one command", CommandChain.split("play next song").size == 1)
        add("bare next with an action splits", CommandChain.split("open whatsapp next message John").size == 2)
        add("no separators means one step", CommandChain.split("torch on").size == 1)
        add("empty input has no steps", CommandChain.plan("   ").steps.isEmpty())

        val long = CommandChain.plan((1..7).joinToString(" then ") { "torch ${if (it % 2 == 0) "off" else "on"}" })
        add("long chains are capped", long.steps.size == CommandChain.MAX_STEPS && long.truncated)

        val wa = CommandChain.Context(app = "whatsapp", target = "John")
        add("message after whatsapp routes to whatsapp", CommandChain.rewrite("message John", wa) == "whatsapp John")
        add("text after whatsapp routes to whatsapp", CommandChain.rewrite("text him", wa) == "whatsapp John")
        add("call pronoun after whatsapp becomes a whatsapp call", CommandChain.rewrite("call her", wa) == "whatsapp call John")
        add("open the chat reuses the contact", CommandChain.rewrite("open the chat", wa) == "whatsapp John")
        add("search after whatsapp stays on whatsapp", CommandChain.rewrite("search for John", wa) == "search John on whatsapp")
        add(
            "message body survives pronoun replacement",
            CommandChain.rewrite("message him saying I will call him", wa) == "whatsapp John saying I will call him",
            CommandChain.rewrite("message him saying I will call him", wa),
        )
        add("no context leaves message alone", CommandChain.rewrite("message John", CommandChain.Context()) == "message John")

        add("target comes from a whatsapp message", CommandChain.targetOf("whatsapp John saying hi") == "John")
        add("target comes from an sms", CommandChain.targetOf("message Ada saying hello") == "Ada")
        add("target comes from a whatsapp search", CommandChain.targetOf("search for Ada on whatsapp") == "Ada")
        add("open app has no person target", CommandChain.targetOf("open whatsapp").isEmpty())
        add("generic contact is not a person", CommandChain.targetOf("search for a contact on whatsapp").isEmpty())
        add("open whatsapp is recognised", CommandChain.appOf("open whatsapp") == "whatsapp")
        add("open the chat is not an app", CommandChain.appOf("open the chat").isEmpty())

        var context = CommandChain.nextContext("open whatsapp", CommandChain.Context())
        add("context remembers the app", context.app == "whatsapp")
        context = CommandChain.nextContext("search for John on whatsapp", context)
        add("context remembers the contact", context.app == "whatsapp" && context.target == "John")
        val chat = CommandChain.rewrite("open the chat", context)
        add("context completes the third step", chat == "whatsapp John", chat)

        add("please and full stops are cleaned", CommandChain.clean("please open whatsapp.") == "open whatsapp")
        add(
            "message punctuation is preserved",
            CommandChain.clean("message John saying see you at 5.") == "message John saying see you at 5.",
        )
        return out
    }
}

/**
 * Desktop entry point used by the headless run. Not called from the app: the
 * Actions screen invokes [SelfChecks.report] directly.
 */
fun main() {
    val ok = SelfChecks.report()
    kotlin.system.exitProcess(if (ok) 0 else 1)
}

package com.bonjee.notifier.device

/**
 * The list of spoken/typed commands the app can perform itself.
 *
 * Single source of truth: the assistant uses [summary] when asked "what can you
 * do", and the Device Commands screen renders [sections] so the phone's own help
 * and the assistant's answer can never drift apart.
 */
object CommandCatalog {

    data class Section(val title: String, val examples: List<String>)

    val sections: List<Section> = listOf(
        Section(
            "OPEN ANY APP",
            listOf(
                "open WhatsApp",
                "launch Gmail",
                "open calculator",
                "open contacts",
                "take a photo",
            ),
        ),
        Section(
            "CALLS, TEXTS, EMAIL",
            listOf(
                "call mum",
                "call 0803 123 4567",
                "text John saying I am on my way",
                "whatsapp 0803 123 4567 saying I will call you",
                "email ada@example.com about the invoice",
            ),
        ),
        Section(
            "SCREEN AND SOUND",
            listOf(
                "brightness up",
                "brightness 60 percent",
                "auto brightness",
                "volume up",
                "volume 40",
                "mute / unmute",
                "vibrate mode",
                "sound on",
            ),
        ),
        Section(
            "QUICK CONTROLS",
            listOf(
                "torch on",
                "torch off",
                "pause",
                "next song",
                "previous song",
                "set alarm for 6:30 am",
                "alarm in 20 minutes",
                "timer 10 minutes",
                "remind me to call mum at 5 pm",
            ),
        ),
        Section(
            "SEARCH AND NAVIGATE",
            listOf(
                "search for jollof rice recipe",
                "youtube Burna Boy latest",
                "play Rihanna on youtube",
                "navigate to Ikeja City Mall",
                "directions to my office",
            ),
        ),
        Section(
            "SYSTEM PANELS",
            listOf(
                "wifi",
                "bluetooth",
                "mobile data",
                "location",
                "hotspot",
                "battery saver",
                "do not disturb",
                "notification access",
                "display settings",
                "settings",
            ),
        ),
        Section(
            "PHONE INFORMATION",
            listOf(
                "battery",
                "time",
                "date",
                "storage",
                "device info",
                "read my notifications",
            ),
        ),
        Section(
            "PERMISSIONS",
            listOf(
                "allow system settings",
                "allow contacts",
                "allow phone",
                "allow camera",
                "allow microphone",
            ),
        ),
    )

    /** Short spoken answer for "what can you do". */
    fun summary(): String = buildString {
        appendLine("I can run this phone myself, and still answer from your n8n archive.")
        appendLine()
        appendLine("Apps - \"open WhatsApp\", \"launch Gmail\", \"open calculator\".")
        appendLine("Calls - \"call mum\", \"text John saying I'm coming\", \"email ada about the invoice\".")
        appendLine("Screen - \"brightness up\", \"brightness 60 percent\", \"auto brightness\".")
        appendLine("Sound - \"volume up\", \"volume 40\", \"mute\", \"vibrate mode\", \"sound on\".")
        appendLine("Quick - \"torch on\", \"pause\", \"next song\", \"set alarm for 6:30\", \"timer 10 minutes\".")
        appendLine("Search - \"search for ...\", \"youtube ...\", \"navigate to ...\".")
        appendLine("Panels - \"wifi\", \"bluetooth\", \"location\", \"battery saver\".")
        appendLine("Info - \"battery\", \"time\", \"date\", \"storage\", \"read my notifications\".")
        append("Anything else goes to your archive exactly as before.")
    }

    /** Full text for the Device Commands screen. */
    fun fullText(): String = buildString {
        for (section in sections) {
            appendLine(section.title)
            section.examples.forEach { appendLine("  • $it") }
            appendLine()
        }
        appendLine("NOTES")
        appendLine("  • Android forbids apps from switching Wi-Fi, Bluetooth, mobile data")
        appendLine("    or Do Not Disturb directly, so those commands open the system panel.")
        appendLine("  • Brightness of the whole phone needs \"Modify system settings\".")
        appendLine("    Say \"allow system settings\" once and it stays on.")
        appendLine("  • Calls need contacts access to look people up, and phone")
        appendLine("    permission to place the call without opening the dialer.")
        append("  • Anything not listed here is sent to your n8n workflow as usual.")
    }
}

package com.bonjee.notifier.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Creates one [VoiceInteractionSession] per invocation.
 *
 * Android binds this service, asks for a fresh session and the session is the
 * only place that is allowed to put UI on screen while the assistant is being
 * summoned.
 */
class BonjeeVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        BonjeeVoiceInteractionSession(this)
}

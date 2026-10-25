package com.bonjee.notifier.assistant

import android.service.voice.VoiceInteractionService

/**
 * The component Android binds when this app is chosen as the default digital
 * assistant (Settings > Apps > Default apps > Digital assistant app).
 *
 * It deliberately stays almost empty: the framework keeps this service alive
 * for the whole time the app holds the assistant role, so doing network calls,
 * holding the microphone or running long jobs here would burn battery and leak
 * resources. The actual conversation lives in [com.bonjee.notifier.AskActivity],
 * which is started per invocation by [BonjeeVoiceInteractionSession].
 */
class BonjeeVoiceInteractionService : VoiceInteractionService() {

    /**
     * Called once the service is connected and the system considers this app the
     * active assistant. Nothing needs warming up, so this only exists to document
     * the lifecycle contract.
     */
    override fun onReady() {
        super.onReady()
    }
}

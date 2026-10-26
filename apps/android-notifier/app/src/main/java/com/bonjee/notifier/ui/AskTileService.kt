package com.bonjee.notifier.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: "Ask JARVIS".
 *
 * This is the closest a normal app can get to Gemini's one-tap entry point -
 * pull the shade down, tap once, and the assistant opens already listening.
 * It sends the same ACTION_ASSIST intent our VoiceInteractionService uses, so
 * the behaviour (voice card, overlay over the lock screen, device commands) is
 * identical to the system gesture.
 */
class AskTileService : TileService() {

    private fun assistantIntent(): Intent =
        Intent(this, JarvisHomeActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    override fun onClick() {
        super.onClick()
        val intent = assistantIntent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

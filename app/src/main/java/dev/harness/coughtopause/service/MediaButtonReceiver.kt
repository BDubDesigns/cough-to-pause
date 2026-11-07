package dev.harness.coughtopause.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import dev.harness.coughtopause.platform.HarnessState

/**
 * Manifest-declared media button receiver.
 *
 * The active MediaSession normally consumes headset buttons, but Android delivers the button to
 * this receiver when the session is not active yet (fresh process, service killed, screen off).
 * That is exactly the fallback the harness needs: a headset click must pause/resume narration
 * even when the UI was never opened.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            -> {
                HarnessState.log("media button delivered to receiver (${event.keyCode})")
                NarrationService.mediaButton(context)
                runCatching { abortBroadcast() }
            }
        }
    }
}

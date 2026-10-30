package com.hark.velaplayer

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps audio going with a media notification when background audio is enabled.
 * v2.21: the session itself belongs to [PlayerHolder] (created with the player, so Bluetooth / headset buttons work
 * with background audio off too). This service only adopts it while it runs and never releases it.
 */
class PlaybackService : MediaSessionService() {
    companion object {
        @Volatile var instance: PlaybackService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlayerHolder.session

    /** The player is closing: stop showing its notification (the holder releases the session itself). */
    fun dropSession(s: MediaSession) {
        runCatching { if (s in sessions) removeSession(s) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        PlayerHolder.session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        // Leave the shared session alive (the player screen may still be open and needs its buttons).
        PlayerHolder.session?.let { s -> runCatching { if (s in sessions) removeSession(s) } }
        super.onDestroy()
    }
}

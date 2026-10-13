package com.hark.velaplayer

import android.app.Application

class VelaApp : Application() {
    private var lastNight = -1

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // System dark mode flipped: widgets of an app in "System" mode re-render (Android 11 and older need this;
        // 12+ already switches via night colour pairs).
        val night = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (night != lastNight) { lastNight = night; WidgetUpdater.requestAll(this) }
    }

    override fun onCreate() {
        super.onCreate()
        lastNight = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        Prefs.applyTheme(this)
        // A fresh process means no player is running: undo a Do Not Disturb we turned on before a crash,
        // and close any focus session that was cut off (it keeps only the time already saved).
        Dnd.restore(this)
        // v2.3: a vault the user set to "Use without password" counts as open; everything else starts locked.
        VaultAuth.sync(this)
        // v2.14: home-screen widgets follow the theme, light/dark, playback and library changes.
        WidgetUpdater.install(this)
        // v2.18: decode/blur the background photo off the main thread before the first screen needs it.
        Backdrop.prewarm(this)
        val startedAt = System.currentTimeMillis()
        Thread { try { FocusStore.get(this).closeAbandoned(startedAt) } catch (_: Exception) { } }.start()
    }
}

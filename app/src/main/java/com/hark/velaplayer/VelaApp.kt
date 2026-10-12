package com.hark.velaplayer

import android.app.Application

class VelaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
        // A fresh process means no player is running: undo a Do Not Disturb we turned on before a crash,
        // and close any focus session that was cut off (it keeps only the time already saved).
        Dnd.restore(this)
        val startedAt = System.currentTimeMillis()
        Thread { try { FocusStore.get(this).closeAbandoned(startedAt) } catch (_: Exception) { } }.start()
    }
}

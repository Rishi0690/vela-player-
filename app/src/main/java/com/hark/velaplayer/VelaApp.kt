package com.hark.velaplayer

import android.app.Application

class VelaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
        // A fresh process means no player is running: undo a Do Not Disturb we turned on before a crash,
        // and close any focus session that was cut off (it keeps only the time already saved).
        Dnd.restore(this)
        // v2.3: a vault the user set to "Use without password" counts as open; everything else starts locked.
        VaultAuth.sync(this)
        val startedAt = System.currentTimeMillis()
        Thread { try { FocusStore.get(this).closeAbandoned(startedAt) } catch (_: Exception) { } }.start()
    }
}

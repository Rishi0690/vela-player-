package com.hark.velaplayer

import android.app.Application

class VelaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
    }
}

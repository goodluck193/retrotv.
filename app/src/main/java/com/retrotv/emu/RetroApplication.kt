package com.retrotv.emu

import android.app.Application
import android.content.ComponentCallbacks2

class RetroApplication : Application() {
    override fun onCreate() { super.onCreate(); CoverArt.configure(this) }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) CoverArt.clearMemory()
    }
    override fun onLowMemory() { super.onLowMemory(); CoverArt.clearMemory() }
}

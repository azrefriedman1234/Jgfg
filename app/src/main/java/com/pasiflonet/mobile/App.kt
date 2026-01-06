package com.pasiflonet.mobile

import android.app.Application
import com.pasiflonet.mobile.util.CrashLogger

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}

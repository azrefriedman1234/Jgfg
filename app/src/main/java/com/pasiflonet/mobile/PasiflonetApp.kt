package com.pasiflonet.mobile

import android.app.Application
import androidx.work.Configuration
import com.pasiflonet.mobile.td.TdLibManager

class PasiflonetApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        TdLibManager.init(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}

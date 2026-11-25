package com.vantora.app

import android.app.Application
import com.vantora.app.core.ServiceLocator

class VantoraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}

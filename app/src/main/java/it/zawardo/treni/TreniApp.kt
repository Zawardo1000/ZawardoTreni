package it.zawardo.treni

import android.app.Application

class TreniApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}

package aculix.parkedhere.app

import android.app.Application
import timber.log.Timber

class ParkedHere : Application() {

    override fun onCreate() {
        super.onCreate()
        initTimber()
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
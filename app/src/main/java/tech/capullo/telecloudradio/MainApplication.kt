package tech.capullo.telecloudradio

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("tdjni")
    }
}

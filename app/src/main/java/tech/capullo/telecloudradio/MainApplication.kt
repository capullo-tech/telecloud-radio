package tech.capullo.telecloudradio

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import tech.capullo.telecloudradio.data.store.StoreLoader
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {
    // Injected in super.onCreate(), which starts the reads of the settings and player state
    // files; they run while the native library loads and the first activity is created.
    @Inject lateinit var storeLoader: StoreLoader

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("tdjni")
    }
}

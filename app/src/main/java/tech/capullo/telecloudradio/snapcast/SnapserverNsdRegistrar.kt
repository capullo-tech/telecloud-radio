package tech.capullo.telecloudradio.snapcast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/** Advertises our snapserver via mDNS/NSD so other devices can discover it. */
class SnapserverNsdRegistrar(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val listeners = mutableListOf<NsdManager.RegistrationListener>()

    fun start() {
        if (listeners.isNotEmpty()) return
        register(SnapserverDiscoveryManager.SERVICE_TYPE, SnapserverDiscoveryManager.SERVICE_PORT)
        register(SnapserverDiscoveryManager.STREAM_SERVICE_TYPE, SnapserverDiscoveryManager.STREAM_SERVICE_PORT)
        Log.d(TAG, "Snapserver NSD registered as '${SnapserverDiscoveryManager.SERVICE_NAME_PREFIX}${Build.MODEL}'")
    }

    fun stop() {
        listeners.forEach {
            try {
                nsdManager.unregisterService(it)
            } catch (_: Exception) {}
        }
        listeners.clear()
        Log.d(TAG, "Snapserver NSD unregistered")
    }

    private fun register(serviceType: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "${SnapserverDiscoveryManager.SERVICE_NAME_PREFIX}${Build.MODEL}"
            this.serviceType = serviceType
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "Registration failed for $serviceType: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        listeners.add(listener)
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    companion object {
        private val TAG = SnapserverNsdRegistrar::class.java.simpleName
    }
}

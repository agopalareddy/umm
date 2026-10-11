package io.github.agopalareddy.umm.sync.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class NsdDiscovery(private val context: Context) : Discovery {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override fun advertise(port: Int, groupHash: String, deviceId: String): AutoCloseable {
        val manager = nsdManager ?: return AutoCloseable {}
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "umm-$deviceId"
            serviceType = SERVICE_TYPE
            this.port = port
            setAttribute("g", groupHash)
            setAttribute("d", deviceId)
        }

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }

        return AutoCloseable {
            runCatching { manager.unregisterService(registrationListener) }
        }
    }

    override fun browse(groupHash: String): Flow<LanPeer> = callbackFlow {
        val manager = nsdManager ?: return@callbackFlow
        val multicastLock = wifiManager?.createMulticastLock("umm-sync")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val g = resolved.attributes["g"]?.decodeToString()
                        val d = resolved.attributes["d"]?.decodeToString()
                        val host = resolved.host
                        val port = resolved.port
                        if (g == groupHash && !d.isNullOrEmpty() && host != null && port > 0) {
                            trySend(LanPeer(deviceId = d, host = host, port = port))
                        }
                    }
                }
                runCatching {
                    manager.resolveService(service, resolveListener)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }

        awaitClose {
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_umm-sync._tcp"
    }
}

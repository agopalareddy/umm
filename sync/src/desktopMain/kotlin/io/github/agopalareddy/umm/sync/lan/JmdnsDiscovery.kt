package io.github.agopalareddy.umm.sync.lan

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

class JmdnsDiscovery : Discovery, AutoCloseable {

    private fun getNonLoopbackIPv4Addresses(): List<Inet4Address> {
        val addresses = mutableListOf<Inet4Address>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
        if (interfaces != null) {
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addresses.add(addr)
                    }
                }
            }
        }
        return addresses
    }

    private fun getJmDnsInstances(): List<JmDNS> {
        val addrs = getNonLoopbackIPv4Addresses()
        return if (addrs.isNotEmpty()) {
            addrs.mapNotNull { addr -> runCatching { JmDNS.create(addr) }.getOrNull() }
        } else {
            listOfNotNull(runCatching { JmDNS.create() }.getOrNull())
        }
    }

    override fun advertise(port: Int, groupHash: String, deviceId: String): AutoCloseable {
        val instances = getJmDnsInstances()
        val serviceType = "_umm-sync._tcp.local."
        val serviceName = "umm-$deviceId"
        val props = mapOf("g" to groupHash, "d" to deviceId)
        val info = ServiceInfo.create(serviceType, serviceName, port, 0, 0, props)

        instances.forEach { jmdns ->
            runCatching { jmdns.registerService(info) }
        }

        return AutoCloseable {
            instances.forEach { jmdns ->
                runCatching { jmdns.unregisterService(info) }
                runCatching { jmdns.close() }
            }
        }
    }

    override fun browse(groupHash: String): Flow<LanPeer> = callbackFlow {
        val instances = getJmDnsInstances()
        val serviceType = "_umm-sync._tcp.local."
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                event.dns.requestServiceInfo(event.type, event.name, 1000)
            }

            override fun serviceRemoved(event: ServiceEvent) {
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val g = info.getPropertyString("g")
                val d = info.getPropertyString("d")
                val port = info.port
                val hosts = info.inet4Addresses ?: info.inetAddresses
                if (g == groupHash && !d.isNullOrEmpty() && hosts.isNotEmpty() && port > 0) {
                    trySend(LanPeer(deviceId = d, host = hosts.first(), port = port))
                }
            }
        }

        instances.forEach { jmdns ->
            runCatching { jmdns.addServiceListener(serviceType, listener) }
        }

        awaitClose {
            instances.forEach { jmdns ->
                runCatching { jmdns.removeServiceListener(serviceType, listener) }
                runCatching { jmdns.close() }
            }
        }
    }

    override fun close() {
    }
}

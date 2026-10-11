package io.github.agopalareddy.umm.sync

import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.group.Membership
import io.github.agopalareddy.umm.sync.lan.LanTransport
import io.github.agopalareddy.umm.sync.pair.Pairing
import io.github.agopalareddy.umm.sync.relay.RelayTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

sealed interface SyncStatus {
    data object NotPaired : SyncStatus
    data class Idle(val lastSync: Long? = null, val via: String? = null) : SyncStatus
    data object Syncing : SyncStatus
    data class Error(val message: String, val lastSync: Long? = null) : SyncStatus
}

data class DeviceInfo(
    val member: Member,
    val isSelf: Boolean,
    val lastSeen: Long? = null,
    val via: String? = null,
)

class SyncEngine(
    val group: GroupStore,
    val lan: LanTransport?,
    val relay: RelayTransport,
    val pairing: Pairing,
    val membership: Membership,
    val log: OpLog,
    val scope: CoroutineScope,
    val now: () -> Long = { System.currentTimeMillis() },
) {
    private val syncMutex = Mutex()
    private var lastSyncTime: Long? = null
    private var lastSyncVia: String? = null
    private var changeDebounceJob: Job? = null
    private val lastSeenMap = ConcurrentHashMap<String, Pair<Long, String>>()

    private val _status = MutableStateFlow<SyncStatus>(
        if (group.state.value == null) SyncStatus.NotPaired else SyncStatus.Idle()
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private var pruneJob: Job? = null
    private var groupWatchJob: Job? = null
    private var lanWatchJob: Job? = null

    init {
        log.onRecorded = { onLocalChange() }
        updateDevices(group.state.value)
    }

    fun start() {
        pruneJob = scope.launch {
            try {
                log.prune(now())
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            }
        }

        val state = group.state.value
        val lanOn = state?.lanEnabled ?: true
        val relayOn = state?.relayEnabled ?: true

        if (lanOn) {
            scope.launch { lan?.start() }
        }
        if (relayOn) {
            relay.start()
        }

        groupWatchJob = scope.launch {
            group.state.collect { st ->
                if (st == null) {
                    _status.value = SyncStatus.NotPaired
                    _devices.value = emptyList()
                } else {
                    if (_status.value is SyncStatus.NotPaired) {
                        _status.value = SyncStatus.Idle(lastSyncTime, lastSyncVia)
                    }
                    updateDevices(st)
                }
            }
        }

        lan?.let { l ->
            lanWatchJob = scope.launch {
                l.peers.collect {
                    updateDevices(group.state.value)
                }
            }
        }
    }

    fun stop() {
        changeDebounceJob?.cancel()
        changeDebounceJob = null
        pruneJob?.cancel()
        pruneJob = null
        groupWatchJob?.cancel()
        groupWatchJob = null
        lanWatchJob?.cancel()
        lanWatchJob = null
        lan?.stop()
        relay.stop()
    }

    suspend fun syncNow() = syncMutex.withLock {
        val state = group.state.value
        if (state == null) {
            _status.value = SyncStatus.NotPaired
            return@withLock
        }

        _status.value = SyncStatus.Syncing
        try {
            var via: String? = null
            if (state.lanEnabled && lan != null) {
                val peers = lan.peers.value
                for (peer in peers) {
                    val result = lan.syncWith(peer)
                    if (result != null) {
                        val t = now()
                        lastSeenMap[peer.deviceId] = Pair(t, "lan")
                        via = "lan"
                    }
                }
            }
            if (state.relayEnabled) {
                relay.publishPending()
                via = if (via == null) "relay" else "$via+relay"
            }
            val t = now()
            lastSyncTime = t
            lastSyncVia = via
            _status.value = SyncStatus.Idle(lastSync = t, via = via)
            updateDevices(state)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            _status.value = SyncStatus.Error(
                message = e.message ?: "Sync failed",
                lastSync = lastSyncTime,
            )
        }
    }

    fun onLocalChange() {
        changeDebounceJob?.cancel()
        changeDebounceJob = scope.launch {
            delay(2000L)
            syncNow()
        }
    }

    suspend fun setTransports(lan: Boolean, relay: Boolean) {
        val state = group.state.value ?: return
        val wasLan = state.lanEnabled
        val wasRelay = state.relayEnabled
        group.save(state.copy(lanEnabled = lan, relayEnabled = relay))
        if (wasLan && !lan) {
            this.lan?.stop()
        } else if (!wasLan && lan) {
            this.lan?.start()
        }
        if (wasRelay && !relay) {
            this.relay.stop()
        } else if (!wasRelay && relay) {
            this.relay.start()
        }
    }

    suspend fun setRelays(urls: List<String>) {
        val state = group.state.value ?: return
        group.save(state.copy(relays = urls))
    }

    private fun updateDevices(state: GroupState?) {
        if (state == null) {
            _devices.value = emptyList()
            return
        }
        val myPubKeyHex = group.device.publicKey.joinToString("") { "%02x".format(it) }
        _devices.value = state.members.map { member ->
            val isSelf = member.publicKey.equals(myPubKeyHex, ignoreCase = true)
            val seen = lastSeenMap[member.deviceId]
                ?: if (lan?.peers?.value?.any { it.deviceId == member.deviceId } == true) Pair(now(), "lan") else null
            DeviceInfo(
                member = member,
                isSelf = isSelf,
                lastSeen = seen?.first,
                via = seen?.second,
            )
        }
    }
}

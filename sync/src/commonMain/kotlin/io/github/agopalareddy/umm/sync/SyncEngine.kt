package io.github.agopalareddy.umm.sync

import io.github.agopalareddy.umm.core.sync.Member
import io.github.agopalareddy.umm.core.sync.OpLog
import io.github.agopalareddy.umm.sync.group.GroupState
import io.github.agopalareddy.umm.sync.group.GroupStore
import io.github.agopalareddy.umm.sync.group.Membership
import io.github.agopalareddy.umm.sync.lan.LanTransport
import io.github.agopalareddy.umm.sync.pair.Pairing
import io.github.agopalareddy.umm.sync.relay.RelaySyncResult
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

data class LanPeerSyncResult(
    val deviceId: String,
    val name: String,
    val reached: Boolean,
    val sessionResult: SessionResult? = null,
    val error: String? = null,
)

data class SyncResult(
    val lanPeers: List<LanPeerSyncResult> = emptyList(),
    val relays: List<RelaySyncResult> = emptyList(),
)

sealed interface SyncStatus {
    data object NotPaired : SyncStatus
    data class Idle(
        val lastSync: Long? = null,
        val via: String? = null,
        val result: SyncResult? = null,
    ) : SyncStatus
    data object Syncing : SyncStatus
    data class Error(
        val message: String,
        val lastSync: Long? = null,
        val result: SyncResult? = null,
    ) : SyncStatus
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
    private val syncLog: SyncLog = SyncLog.None,
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
                    // Once there is someone to sync with: what this device had before pairing goes out as changes.
                    if (st.members.size > 1) {
                        try {
                            val recorded = log.recordExistingRows(st.groupId)
                            if (recorded > 0) syncLog.log("recorded $recorded rows from before pairing as changes")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            syncLog.log("could not record the rows from before pairing: ${e.message}")
                        }
                    }
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

    suspend fun syncNow(): SyncResult = syncMutex.withLock {
        val state = group.state.value
        if (state == null) {
            _status.value = SyncStatus.NotPaired
            return@withLock SyncResult()
        }

        _status.value = SyncStatus.Syncing
        val lanResults = mutableListOf<LanPeerSyncResult>()
        var relayResults = emptyList<RelaySyncResult>()

        val myPubkey = group.device.publicKey.joinToString("") { "%02x".format(it) }
        val otherMembers = state.members.filter { !it.publicKey.equals(myPubkey, ignoreCase = true) }

        try {
            if (state.lanEnabled && lan != null) {
                val peers = lan.peers.value
                for (peer in peers) {
                    val member = state.members.find { it.deviceId == peer.deviceId }
                    val devName = member?.name ?: peer.deviceId
                    try {
                        var result = lan.syncWith(peer)
                        // A session that moved anything can leave rows for the next one (old rows held back
                        // behind a new change), so run one more; a settled pair exchanges nothing in it.
                        if (result != null && (result.sent > 0 || result.received > 0)) {
                            val second = lan.syncWith(peer)
                            if (second != null) {
                                result = SessionResult(result.sent + second.sent, result.received + second.received)
                            }
                        }
                        if (result != null) {
                            val t = now()
                            lastSeenMap[peer.deviceId] = Pair(t, "lan")
                            lanResults.add(
                                LanPeerSyncResult(
                                    deviceId = peer.deviceId,
                                    name = devName,
                                    reached = true,
                                    sessionResult = result,
                                )
                            )
                        } else {
                            lanResults.add(
                                LanPeerSyncResult(
                                    deviceId = peer.deviceId,
                                    name = devName,
                                    reached = false,
                                    error = "Couldn't reach $devName",
                                )
                            )
                        }
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        lanResults.add(
                            LanPeerSyncResult(
                                deviceId = peer.deviceId,
                                name = devName,
                                reached = false,
                                error = e.message ?: "Couldn't reach $devName",
                            )
                        )
                    }
                }
            }
            if (state.relayEnabled) {
                val published = relay.publishPending()
                val caughtUp = relay.catchUp()
                relayResults = published.map { it.copy(caughtUp = caughtUp[it.url] == true) }
            }

            val syncResult = SyncResult(lanPeers = lanResults, relays = relayResults)

            val lanSucceeded = lanResults.any { it.reached }
            val relaySucceeded = state.relayEnabled && relayResults.any { it.connected && it.error == null }

            val via = when {
                lanSucceeded && relaySucceeded -> "lan+relay"
                lanSucceeded -> "lan"
                relaySucceeded -> "relay"
                else -> null
            }

            if (via != null) {
                val t = now()
                lastSyncTime = t
                lastSyncVia = via
                _status.value = SyncStatus.Idle(lastSync = t, via = via, result = syncResult)
                updateDevices(state)
            } else if (otherMembers.isEmpty()) {
                _status.value = SyncStatus.Idle(lastSync = lastSyncTime, via = lastSyncVia, result = syncResult)
                updateDevices(state)
            } else {
                val failedPeer = lanResults.firstOrNull { !it.reached }
                val errorMsg = if (failedPeer != null) {
                    "Couldn't reach ${failedPeer.name}"
                } else {
                    "No device or relay reachable"
                }
                _status.value = SyncStatus.Error(
                    message = errorMsg,
                    lastSync = lastSyncTime,
                    result = syncResult,
                )
                updateDevices(state)
            }
            syncLog.log(describe(syncResult, via ?: if (otherMembers.isEmpty()) "alone in the group" else "nothing reached"))
            return@withLock syncResult
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            val syncResult = SyncResult(lanPeers = lanResults, relays = relayResults)
            _status.value = SyncStatus.Error(
                message = e.message ?: "Sync failed",
                lastSync = lastSyncTime,
                result = syncResult,
            )
            syncLog.log(describe(syncResult, "failed: ${e::class.simpleName}: ${e.message}"))
            return@withLock syncResult
        }
    }

    private fun describe(result: SyncResult, outcome: String): String {
        val lanPart = result.lanPeers.joinToString(", ") { peer ->
            val session = peer.sessionResult
            if (peer.reached && session != null) {
                "${peer.name} sent=${session.sent} received=${session.received}"
            } else {
                "${peer.name} unreachable (${peer.error})"
            }
        }.ifEmpty { "no device found" }
        val relayPart = result.relays.joinToString(", ") { relay ->
            val state = when {
                relay.error != null -> "error (${relay.error})"
                !relay.connected -> "not connected"
                relay.caughtUp -> "ok"
                else -> "connected, no answer to catch-up"
            }
            "${relayHost(relay.url)} published=${relay.opsPublished} $state"
        }.ifEmpty { "off or none" }
        return "sync: $outcome | wifi: $lanPart | relays: $relayPart"
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

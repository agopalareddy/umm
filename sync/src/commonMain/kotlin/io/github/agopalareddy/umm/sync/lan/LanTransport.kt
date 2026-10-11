package io.github.agopalareddy.umm.sync.lan

import io.github.agopalareddy.umm.core.data.UmmDatabase
import io.github.agopalareddy.umm.core.sync.SyncPeerEntity
import io.github.agopalareddy.umm.sync.SessionResult
import io.github.agopalareddy.umm.sync.SyncSession
import io.github.agopalareddy.umm.sync.Wire
import io.github.agopalareddy.umm.sync.group.GroupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

private class SocketWire(private val socket: Socket) : Wire {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val sendMutex = Mutex()
    private val recvMutex = Mutex()

    override suspend fun send(message: ByteArray) = withContext(Dispatchers.IO) {
        sendMutex.withLock {
            FrameCodec.write(output, message)
        }
    }

    override suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        recvMutex.withLock {
            FrameCodec.read(input)
        }
    }
}

open class LanTransport(
    val session: SyncSession,
    val discovery: Discovery,
    val group: GroupStore,
    val selfId: String,
    val scope: CoroutineScope,
    val db: UmmDatabase? = null,
) {
    private var serverSocket: ServerSocket? = null
    private var advertiseHandle: AutoCloseable? = null
    private var browseJob: Job? = null
    private var acceptJob: Job? = null
    private var groupWatchJob: Job? = null

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    open val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val peerMutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(deviceId: String): Mutex = peerMutexes.getOrPut(deviceId) { Mutex() }

    init {
        session.onPeerSynced = { peerId ->
            updateSyncPeer(peerId)
        }
    }

    open suspend fun start(): Int = withContext(Dispatchers.IO) {
        val server = ServerSocket(0)
        serverSocket = server
        val port = server.localPort

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !server.isClosed) {
                try {
                    val socket = server.accept()
                    launch(Dispatchers.IO) {
                        try {
                            val wire = SocketWire(socket)
                            session.run(wire, initiator = false)
                        } catch (_: Exception) {
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                } catch (_: Exception) {
                    if (server.isClosed) break
                }
            }
        }

        groupWatchJob = scope.launch {
            group.state.collect { st ->
                advertiseHandle?.close()
                advertiseHandle = null
                browseJob?.cancel()
                browseJob = null
                _peers.value = emptyList()

                if (st != null && st.lanEnabled && !server.isClosed) {
                    val hash = groupHash(st.groupId)
                    advertiseHandle = discovery.advertise(port, hash, selfId)
                    browseJob = launch {
                        discovery.browse(hash).collect { peer ->
                            if (peer.deviceId != selfId) {
                                _peers.update { current ->
                                    val filtered = current.filter { it.deviceId != peer.deviceId }
                                    filtered + peer
                                }
                            }
                        }
                    }
                }
            }
        }

        port
    }

    open suspend fun syncWith(peer: LanPeer): SessionResult = withContext(Dispatchers.IO) {
        val mutex = mutexFor(peer.deviceId)
        mutex.withLock {
            val socket = Socket(peer.host, peer.port)
            try {
                val wire = SocketWire(socket)
                val result = session.run(wire, initiator = true)
                updateSyncPeer(peer.deviceId)
                result
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    open fun stop() {
        groupWatchJob?.cancel()
        groupWatchJob = null
        browseJob?.cancel()
        browseJob = null
        advertiseHandle?.close()
        advertiseHandle = null
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _peers.value = emptyList()
    }

    private suspend fun updateSyncPeer(peerDeviceId: String) {
        val database = db ?: return
        val dao = database.syncDao()
        val existing = dao.getPeer(peerDeviceId)
        dao.putPeer(
            SyncPeerEntity(
                deviceId = peerDeviceId,
                marks = existing?.marks ?: "{}",
                lastSeen = System.currentTimeMillis(),
                transport = "lan",
            ),
        )
    }
}

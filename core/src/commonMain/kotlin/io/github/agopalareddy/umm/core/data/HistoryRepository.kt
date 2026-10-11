package io.github.agopalareddy.umm.core.data

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.sync.ChangeRecorder
import io.github.agopalareddy.umm.core.sync.DataKind
import io.github.agopalareddy.umm.core.sync.SyncMeta
import io.github.agopalareddy.umm.core.sync.SyncOp
import io.github.agopalareddy.umm.core.sync.toSynced
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


/** Recent dictations. Audio is kept only while a dictation still needs a retry. */
class HistoryRepository(
    private val db: UmmDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val recorder: ChangeRecorder = ChangeRecorder.None,
) {

    private val dao = db.historyDao()
    private val meta = SyncMeta(db)

    fun observeRecent(origin: String? = null): Flow<List<HistoryItem>> =
        (if (origin == null) dao.observeRecent(MAX_ITEMS) else dao.observeRecent(MAX_ITEMS, origin))
            .map { list -> list.map { it.toItem() } }

    suspend fun get(id: Long): HistoryItem? = dao.get(id)?.toItem()

    suspend fun all(): List<HistoryItem> = dao.all().map { it.toItem() }

    suspend fun createPending(
        packageName: String,
        appLabel: String? = null,
        level: CleanupLevel,
        script: ScriptPreference,
        language: String,
        audioPath: String,
    ): Long {
        val origin = meta.deviceId()
        val toDeleteAudio = mutableListOf<String>()
        val id = db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val insertedId = dao.insert(
                    HistoryEntity(
                        originDevice = origin,
                        originId = 0,
                        appLabel = appLabel,
                        createdAt = clock(),
                        packageName = packageName,
                        level = level,
                        script = script,
                        language = language,
                        status = HistoryStatus.PENDING,
                        audioPath = audioPath,
                    ),
                )
                dao.setOriginId(insertedId, insertedId)
                val beyond = dao.beyond(origin, MAX_ITEMS)
                if (beyond.isNotEmpty()) {
                    beyond.forEach { it.audioPath?.let(toDeleteAudio::add) }
                    dao.delete(beyond.map { it.id })
                }
                insertedId
            }
        }
        toDeleteAudio.forEach { File(it).delete() }
        return id
    }

    suspend fun markDone(id: Long, raw: String, clean: String?) = edit(id) {
        it.copy(status = HistoryStatus.DONE, rawText = raw, cleanText = clean, audioPath = null, error = null)
    }

    suspend fun markCleanupFailed(id: Long, raw: String, error: String) = edit(id) {
        it.copy(status = HistoryStatus.CLEANUP_FAILED, rawText = raw, audioPath = null, error = error)
    }

    suspend fun markFailed(id: Long, error: String) = edit(id) {
        it.copy(status = HistoryStatus.FAILED, error = error)
    }

    suspend fun updateClean(id: Long, level: CleanupLevel, clean: String) = edit(id) {
        it.copy(level = level, cleanText = clean, status = HistoryStatus.DONE, error = null)
    }

    suspend fun delete(id: Long) {
        var audioToDelete: String? = null
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val item = dao.get(id) ?: return@immediateTransaction
                dao.delete(listOf(item.id))
                recorder.record(SyncOp.HistoryDelete(item.originDevice, item.originId))
                audioToDelete = item.audioPath
            }
        }
        audioToDelete?.let { File(it).delete() }
    }

    suspend fun clearAll() {
        val audioToDelete = mutableListOf<String>()
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val all = dao.all()
                all.forEach { it.audioPath?.let(audioToDelete::add) }
                dao.deleteAll()
                recorder.record(SyncOp.ClearBefore(DataKind.HISTORY, clock(), null))
            }
        }
        audioToDelete.forEach { File(it).delete() }
    }

    suspend fun purgeExpiredAudio(maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
        val audioToDelete = mutableListOf<String>()
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val expired = dao.withAudioOlderThan(clock() - maxAgeMs)
                expired.forEach { entity ->
                    entity.audioPath?.let(audioToDelete::add)
                    dao.update(entity.copy(audioPath = null))
                }
            }
        }
        audioToDelete.forEach { File(it).delete() }
    }

    private suspend fun edit(id: Long, change: (HistoryEntity) -> HistoryEntity) {
        var audioToDelete: String? = null
        db.useWriterConnection { transactor ->
            transactor.immediateTransaction {
                val current = dao.get(id) ?: return@immediateTransaction
                val updated = change(current)
                dao.update(updated)
                if (updated.status != HistoryStatus.PENDING) {
                    recorder.record(SyncOp.HistoryUpsert(updated.toSynced()))
                }
                if (current.audioPath != null && updated.audioPath == null) {
                    audioToDelete = current.audioPath
                }
            }
        }
        audioToDelete?.let { File(it).delete() }
    }

    private companion object {
        const val MAX_ITEMS = 50
    }
}

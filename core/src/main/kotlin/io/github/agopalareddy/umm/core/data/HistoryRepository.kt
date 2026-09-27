package io.github.agopalareddy.umm.core.data

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Recent dictations. Audio is kept only while a dictation still needs a retry. */
class HistoryRepository(db: UmmDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.historyDao()

    fun observeRecent(): Flow<List<HistoryItem>> = dao.observeRecent(MAX_ITEMS).map { list -> list.map { it.toItem() } }

    suspend fun get(id: Long): HistoryItem? = dao.get(id)?.toItem()

    suspend fun createPending(
        packageName: String,
        level: CleanupLevel,
        script: ScriptPreference,
        language: String,
        audioPath: String,
    ): Long {
        val id = dao.insert(
            HistoryEntity(
                createdAt = clock(),
                packageName = packageName,
                level = level,
                script = script,
                language = language,
                status = HistoryStatus.PENDING,
                audioPath = audioPath,
            ),
        )
        removeWithAudio(dao.beyond(MAX_ITEMS))
        return id
    }

    suspend fun markDone(id: Long, raw: String, clean: String?) = edit(id) {
        deleteAudio(it)
        it.copy(status = HistoryStatus.DONE, rawText = raw, cleanText = clean, audioPath = null, error = null)
    }

    suspend fun markCleanupFailed(id: Long, raw: String, error: String) = edit(id) {
        deleteAudio(it)
        it.copy(status = HistoryStatus.CLEANUP_FAILED, rawText = raw, audioPath = null, error = error)
    }

    suspend fun markFailed(id: Long, error: String) = edit(id) {
        it.copy(status = HistoryStatus.FAILED, error = error)
    }

    suspend fun updateClean(id: Long, level: CleanupLevel, clean: String) = edit(id) {
        it.copy(level = level, cleanText = clean, status = HistoryStatus.DONE, error = null)
    }

    suspend fun delete(id: Long) {
        dao.get(id)?.let { removeWithAudio(listOf(it)) }
    }

    suspend fun clearAll() {
        dao.all().forEach(::deleteAudio)
        dao.deleteAll()
    }

    suspend fun purgeExpiredAudio(maxAgeMs: Long = 7L * 24 * 3600 * 1000) {
        dao.withAudioOlderThan(clock() - maxAgeMs).forEach { entity ->
            deleteAudio(entity)
            dao.update(entity.copy(audioPath = null))
        }
    }

    private suspend fun edit(id: Long, change: (HistoryEntity) -> HistoryEntity) {
        val current = dao.get(id) ?: return
        dao.update(change(current))
    }

    private suspend fun removeWithAudio(entities: List<HistoryEntity>) {
        if (entities.isEmpty()) return
        entities.forEach(::deleteAudio)
        dao.delete(entities.map { it.id })
    }

    private fun deleteAudio(entity: HistoryEntity) {
        entity.audioPath?.let { File(it).delete() }
    }

    private companion object {
        const val MAX_ITEMS = 50
    }
}

package io.github.agopalareddy.umm.settings.dashboard

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.data.UmmDatabase
import io.github.agopalareddy.umm.core.stats.StatsEntry
import io.github.agopalareddy.umm.core.stats.StatsRepository
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/** Debug-only: fills the stats table with about 600 made-up dictations over the last 90 days, so the dashboard has something to draw. */
object SampleData {
    private const val ROWS = 600
    private const val DAYS = 90

    /** Far above any real history id, so seeded rows never replace a real dictation's row. */
    private const val FIRST_ID = 1_000_000_000L

    private val apps = listOf(
        "com.whatsapp" to 30,
        "com.google.android.gm" to 20,
        "com.android.chrome" to 15,
        "com.slack" to 12,
        "com.google.android.apps.messaging" to 10,
        "com.google.android.keep" to 8,
        "com.microsoft.office.outlook" to 5,
    )
    private val levels = listOf(CleanupLevel.RAW to 10, CleanupLevel.LIGHT to 45, CleanupLevel.FORMATTED to 30, CleanupLevel.POLISHED to 15)
    private val sttModels = listOf("openai/gpt-4o-transcribe", "google/gemini-2.5-flash", "openai/whisper-large-v3")
    private val cleanupModels = listOf("google/gemini-2.5-flash-lite", "anthropic/claude-haiku-4.5", "openai/gpt-5-mini")

    // Mornings, lunch and evenings are busiest.
    private val hours = mapOf(
        7 to 3, 8 to 8, 9 to 10, 10 to 9, 11 to 7, 12 to 8, 13 to 6, 14 to 5,
        15 to 5, 16 to 6, 17 to 7, 18 to 9, 19 to 8, 20 to 7, 21 to 4, 22 to 2,
    )

    /** Writes through its own ungated repository, so it works while recording is off. Same [nowMs] and zone give the same rows. */
    suspend fun seed(db: UmmDatabase, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()) {
        val repo = StatsRepository(db)
        val random = Random(42)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        // A few quiet days, so the streak and the calendar aren't a solid block.
        val activeDays = (0 until DAYS).filter { it == 0 || random.nextInt(100) >= 15 }
        repeat(ROWS) { i ->
            val daysAgo = activeDays[random.nextInt(activeDays.size)]
            val at = today.minusDays(daysAgo.toLong()).atTime(weighted(random, hours), random.nextInt(60), random.nextInt(60))
                .atZone(zone).toInstant().toEpochMilli().coerceAtMost(nowMs)
            val level = weighted(random, levels)
            val audioMs = 1_500L + (random.nextDouble().let { it * it } * 58_000).toLong()
            val words = ((audioMs / 1000.0) * (2.0 + random.nextDouble() * 1.2)).toInt().coerceAtLeast(1)
            val failed = random.nextInt(100) < 3
            val stt = sttModels[weighted(random, listOf(0 to 55, 1 to 35, 2 to 10))].takeIf { random.nextInt(100) >= 4 }
            val cleanup = cleanupModels[random.nextInt(cleanupModels.size)].takeIf { level != CleanupLevel.RAW && random.nextInt(100) >= 4 }
            val fillers = if (failed) 0 else random.nextInt(0, words / 8 + 1)
            val cost = 0.0004 + audioMs / 60_000.0 * 0.004 + if (cleanup != null) words * 0.00001 else 0.0
            repo.record(
                StatsEntry(
                    historyId = FIRST_ID + i,
                    createdAt = at,
                    packageName = weighted(random, apps),
                    level = level,
                    rawWords = if (failed) 0 else words,
                    cleanWords = if (failed) 0 else (words - fillers).coerceAtLeast(1),
                    fillerWords = fillers,
                    audioMs = audioMs,
                    latencyMs = if (failed) null else 700L + audioMs / 8 + random.nextInt(900),
                    costUsd = if (random.nextInt(100) < 3) null else cost,
                    sttModel = if (failed) null else stt,
                    cleanupModel = if (failed) null else cleanup,
                    succeeded = !failed,
                ),
            )
        }
    }

    /** Deletes only the seeded rows, leaving real dictations alone. */
    suspend fun remove(stats: StatsRepository) = stats.deleteFromHistoryId(FIRST_ID)

    private fun <T> weighted(random: Random, options: Collection<Pair<T, Int>>): T {
        var pick = random.nextInt(options.sumOf { it.second })
        for ((value, weight) in options) {
            if (pick < weight) return value
            pick -= weight
        }
        error("unreachable")
    }

    private fun <T> weighted(random: Random, options: Map<T, Int>): T = weighted(random, options.toList())
}

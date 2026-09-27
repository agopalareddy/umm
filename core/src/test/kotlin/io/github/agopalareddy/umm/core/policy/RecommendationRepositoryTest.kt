package io.github.agopalareddy.umm.core.policy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecommendationRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private var now = 10 * DAY

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun json(tag: String) =
        """{"schema":1,"stt":{"primary":"$tag/stt","fallback":"f"},"cleanup":{"primary":"$tag/chat","fallback":"g"}}"""

    private fun TestScope.fixture(): Pair<RecommendationRepository, SettingsRepository> {
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") },
        )
        val repo = RecommendationRepository(OkHttpClient(), server.url("/recommended.json"), settings, { json("bundled") }) { now }
        return repo to settings
    }

    @Test fun freshCacheSkipsNetwork() = runTest {
        val (repo, settings) = fixture()
        settings.writeCache(RecommendationRepository.CACHE, json("cached"), now - DAY / 2)
        assertEquals("cached/stt", repo.current().stt.primary)
        assertEquals(0, server.requestCount)
    }

    @Test fun staleCacheRefetchesAndCaches() = runTest {
        val (repo, settings) = fixture()
        settings.writeCache(RecommendationRepository.CACHE, json("cached"), now - 2 * DAY)
        server.enqueue(MockResponse.Builder().body(json("remote")).build())
        assertEquals("remote/stt", repo.current().stt.primary)
        assertEquals(json("remote") to now, settings.readCache(RecommendationRepository.CACHE))
    }

    @Test fun staleCacheUsedWhenFetchFails() = runTest {
        val (repo, settings) = fixture()
        settings.writeCache(RecommendationRepository.CACHE, json("cached"), now - 2 * DAY)
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals("cached/stt", repo.current().stt.primary)
    }

    @Test fun bundledUsedWithoutCacheOrNetwork() = runTest {
        val (repo, _) = fixture()
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals("bundled/stt", repo.current().stt.primary)
    }

    @Test fun malformedRemoteTreatedAsFailure() = runTest {
        val (repo, settings) = fixture()
        server.enqueue(MockResponse.Builder().body("{not json").build())
        assertEquals("bundled/stt", repo.current().stt.primary)
        assertEquals(null, settings.readCache(RecommendationRepository.CACHE))
    }

    @Test fun failedFetchNotRetriedWithin24h() = runTest {
        val (repo, _) = fixture()
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals("bundled/stt", repo.current().stt.primary)
        now += DAY / 2
        assertEquals("bundled/stt", repo.current().stt.primary)
        assertEquals(1, server.requestCount)
        now += DAY
        server.enqueue(MockResponse.Builder().body(json("remote")).build())
        assertEquals("remote/stt", repo.current().stt.primary)
    }

    private companion object {
        const val DAY = 24L * 3600 * 1000
    }
}

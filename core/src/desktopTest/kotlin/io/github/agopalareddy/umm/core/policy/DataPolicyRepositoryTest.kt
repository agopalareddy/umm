package io.github.agopalareddy.umm.core.policy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.pipeline.FakeApi
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataPolicyRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 100L * DAY
    private val api = FakeApi().apply {
        zdrList = setOf("stt/f", "chat/p")
    }
    private val rec = Recommendation(1, ModelPair("stt/p", "stt/f"), ModelPair("chat/p", "chat/f"))

    private fun TestScope.repo() = DataPolicyRepository(
        api,
        SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }),
    ) { now }

    @Test fun probesTheFirstModelWithoutZdr() = runTest {
        api.probeResult = true
        val repo = repo()
        repo.refresh(rec, force = false)
        assertEquals(listOf("stt/p"), api.probedModels)
        assertEquals(DataPolicy(enforced = true, zdrModels = setOf("stt/f", "chat/p")), repo.current())
    }

    @Test fun probesAtMostDaily() = runTest {
        api.probeResult = false
        val repo = repo()
        repo.refresh(rec, force = false)
        now += DAY / 2
        repo.refresh(rec, force = false)
        assertEquals(1, api.probedModels.size)
        repo.refresh(rec, force = true) // e.g. a new key
        assertEquals(2, api.probedModels.size)
        assertFalse(repo.current().enforced)
    }

    @Test fun inconclusiveProbeKeepsTheLastAnswer() = runTest {
        val repo = repo()
        api.probeResult = true
        repo.refresh(rec, force = true)
        api.probeResult = null
        repo.refresh(rec, force = true)
        assertTrue(repo.current().enforced)
    }

    @Test fun noProbeWhenEveryRecommendedModelHasZdr() = runTest {
        api.zdrList = setOf("stt/p", "stt/f")
        val repo = repo()
        repo.refresh(rec, force = true)
        assertTrue(api.probedModels.isEmpty())
    }

    @Test fun aBlockedDictationMarksTheAccount() = runTest {
        val repo = repo()
        repo.markEnforced()
        assertTrue(repo.current().enforced)
        assertTrue(repo.observe().first().enforced)
    }

    @Test fun noticeIsShownOncePerEnforcement() = runTest {
        val repo = repo()
        repo.markEnforced()
        assertTrue(repo.observe().first().noticePending)
        repo.acknowledgeNotice()
        assertFalse(repo.observe().first().noticePending)
    }

    private companion object {
        const val DAY = 24L * 3600 * 1000
    }

    @Test fun recheckClearsEnforcementOnceTheAccountAllowsItAgain() = runTest {
        val repo = repo()
        repo.markEnforced()
        api.probeResult = false
        assertEquals(false, repo.recheck(rec))
        assertFalse(repo.current().enforced)
    }

    @Test fun recheckReportsStillEnforced() = runTest {
        val repo = repo()
        api.probeResult = true
        assertEquals(true, repo.recheck(rec))
    }

    @Test fun recheckIsInconclusiveWhenTheProbeFails() = runTest {
        val repo = repo()
        repo.markEnforced()
        api.probeResult = null
        assertEquals(null, repo.recheck(rec))
        assertTrue(repo.current().enforced)
    }

    @Test fun nothingToProbeMeansNotRestricted() = runTest {
        api.zdrList = setOf("stt/p", "stt/f")
        val repo = repo()
        repo.markEnforced()
        assertEquals(false, repo.recheck(rec))
        assertFalse(repo.current().enforced)
    }
}

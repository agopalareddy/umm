package io.github.agopalareddy.umm.core.policy

import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectorTest {
    private val rec = Recommendation(
        1, ModelPair("stt/p", "stt/f"), ModelPair("chat/p", "chat/f"),
        zdr = ModelSet(ModelPair("stt/zp", "stt/zf"), ModelPair("chat/zp", "chat/zf")),
    )
    private val zdrCapable = setOf("stt/f", "stt/zp", "stt/zf", "chat/p", "chat/zp", "chat/zf", "x-zdr")
    private val enforced = DataPolicy(enforced = true, zdrModels = zdrCapable)
    private fun model(id: String, created: Long) = ModelInfo(id, id, created, null, null)
    private val live = listOf(model("a", 100), model("c", 300), model("b", 200))

    @Test fun recommendedUsesList() {
        val plan = ModelSelector.plan(UmmSettings(), rec, live)
        assertEquals(ModelPlan(listOf("stt/p", "stt/f"), listOf("chat/p", "chat/f")), plan)
    }

    @Test fun newestPicksLatestCreated() {
        val plan = ModelSelector.plan(UmmSettings(modelMode = ModelMode.NEWEST_STT), rec, live)
        assertEquals(listOf("c", "stt/p"), plan.stt)
        assertEquals(listOf("chat/p", "chat/f"), plan.cleanup)
    }

    @Test fun newestFallsBackWhenNoLiveList() {
        val plan = ModelSelector.plan(UmmSettings(modelMode = ModelMode.NEWEST_STT), rec, null)
        assertEquals(listOf("stt/p", "stt/f"), plan.stt)
    }

    @Test fun newestDedupes() {
        val plan = ModelSelector.plan(UmmSettings(modelMode = ModelMode.NEWEST_STT), rec, listOf(model("stt/p", 999)))
        assertEquals(listOf("stt/p", "stt/f"), plan.stt)
    }

    @Test fun manualUsesPicksThenRecommended() {
        val plan = ModelSelector.plan(UmmSettings(modelMode = ModelMode.MANUAL, manualSttModel = "x"), rec, live)
        assertEquals(listOf("x", "stt/p"), plan.stt)
        assertEquals(listOf("chat/p", "chat/f"), plan.cleanup)
    }

    @Test fun manualCleanupPick() {
        val plan = ModelSelector.plan(UmmSettings(modelMode = ModelMode.MANUAL, manualCleanupModel = "y"), rec, live)
        assertEquals(listOf("stt/p", "stt/f"), plan.stt)
        assertEquals(listOf("y", "chat/p"), plan.cleanup)
    }

    @Test fun notEnforcedLeavesThePlanAlone() {
        val plan = ModelSelector.plan(UmmSettings(), rec, live, DataPolicy(enforced = false, zdrModels = zdrCapable))
        assertEquals(listOf("stt/p", "stt/f"), plan.stt)
    }

    @Test fun enforcedDropsModelsWithoutZdrEndpoints() {
        val plan = ModelSelector.plan(UmmSettings(), rec, live, enforced)
        // stt/p has no ZDR endpoint; stt/f does, then the curated ZDR set fills in.
        assertEquals(listOf("stt/f", "stt/zp"), plan.stt)
        assertEquals(listOf("chat/p", "chat/zp"), plan.cleanup)
    }

    @Test fun enforcedSkipsANewestModelWithoutZdr() {
        val plan = ModelSelector.plan(UmmSettings(modelMode = ModelMode.NEWEST_STT), rec, live, enforced)
        assertEquals(listOf("stt/f", "stt/zp"), plan.stt)
    }

    @Test fun enforcedKeepsAZdrManualPick() {
        val plan = ModelSelector.plan(UmmSettings(modelMode = ModelMode.MANUAL, manualSttModel = "x-zdr"), rec, live, enforced)
        assertEquals(listOf("x-zdr", "stt/f"), plan.stt)
    }

    @Test fun enforcedWithoutTheZdrListUsesTheCuratedSet() {
        val plan = ModelSelector.plan(UmmSettings(), rec, live, DataPolicy(enforced = true, zdrModels = emptySet()))
        assertEquals(listOf("stt/zp", "stt/zf"), plan.stt)
        assertEquals(listOf("chat/zp", "chat/zf"), plan.cleanup)
    }

    @Test fun availabilityForTheModelsPage() {
        assertEquals(false, enforced.allows("stt/p"))
        assertEquals(true, enforced.allows("stt/f"))
        assertEquals(true, DataPolicy(enforced = false, zdrModels = zdrCapable).allows("stt/p"))
        assertEquals(true, DataPolicy(enforced = true, zdrModels = emptySet()).allows("anything")) // unknown: don't gray out
    }

    @Test fun zdrChosenInTheAppRestrictsLikeTheAccountSetting() {
        val notEnforced = DataPolicy(enforced = false, zdrModels = zdrCapable)
        val plan = ModelSelector.plan(UmmSettings(zdrOnly = true), rec, live, notEnforced)
        assertEquals(listOf("stt/f", "stt/zp"), plan.stt)
    }

    @Test fun zdrChosenInTheAppWithoutAnyPolicyInfoUsesTheCuratedSet() {
        val plan = ModelSelector.plan(UmmSettings(zdrOnly = true), rec, live, null)
        assertEquals(listOf("stt/zp", "stt/zf"), plan.stt)
    }

    @Test fun effectivePolicyCombinesAccountAndAppChoice() {
        val account = DataPolicy(enforced = false, zdrModels = zdrCapable)
        assertEquals(true, account.withAppChoice(zdrOnly = true).enforced)
        assertEquals(false, account.withAppChoice(zdrOnly = false).enforced)
        assertEquals(true, enforced.withAppChoice(zdrOnly = false).enforced)
    }
}

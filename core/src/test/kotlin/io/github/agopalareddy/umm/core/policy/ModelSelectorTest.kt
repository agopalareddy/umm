package io.github.agopalareddy.umm.core.policy

import io.github.agopalareddy.umm.core.data.ModelMode
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.core.openrouter.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectorTest {
    private val rec = Recommendation(1, ModelPair("stt/p", "stt/f"), ModelPair("chat/p", "chat/f"))
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
}

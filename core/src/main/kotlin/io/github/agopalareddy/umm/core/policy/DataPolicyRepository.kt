package io.github.agopalareddy.umm.core.policy

import io.github.agopalareddy.umm.core.data.SettingsRepository
import io.github.agopalareddy.umm.core.openrouter.OpenRouterApi
import io.github.agopalareddy.umm.core.openrouter.OpenRouterException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json

/** Whether the account only allows zero data retention endpoints, and whether the user has been told. */
data class DataPolicyStatus(val enforced: Boolean, val noticePending: Boolean)

/**
 * Tracks the OpenRouter account's zero data retention (ZDR) setting. OpenRouter has no API for the setting itself,
 * so Umm probes a model without ZDR endpoints (free, see [OpenRouterApi.blockedByDataPolicy]) and also learns from
 * blocked dictations.
 */
class DataPolicyRepository(
    private val api: OpenRouterApi,
    private val settings: SettingsRepository,
    private val clock: () -> Long,
) {
    suspend fun current(): DataPolicy = DataPolicy(enforced(), zdrModels())

    fun observe(): Flow<DataPolicyStatus> =
        combine(settings.observeCache(ENFORCED), settings.observeCache(NOTICE)) { enforced, notice ->
            val on = enforced?.first == "true"
            DataPolicyStatus(enforced = on, noticePending = on && notice?.first != "true")
        }

    /** Probes at most once a day, or now when [force] (e.g. after the key changes). */
    suspend fun refresh(rec: Recommendation, force: Boolean) {
        val last = settings.readCache(ENFORCED)
        if (!force && last != null && clock() - last.second < MAX_AGE_MS) return
        recheck(rec)
    }

    /**
     * Checks the account now. Returns whether it enforces ZDR, or null if the check failed (the last answer stays).
     * When every recommended speech model has a ZDR endpoint, the setting cannot get in the way, so it counts as off.
     */
    suspend fun recheck(rec: Recommendation): Boolean? {
        val zdr = zdrModels()
        val target = listOf(rec.stt.primary, rec.stt.fallback).firstOrNull { zdr.isEmpty() || it !in zdr }
        val blocked = if (target == null) false else api.blockedByDataPolicy(target) ?: return null
        setEnforced(blocked)
        return blocked
    }

    /** A real dictation was refused because of the data policy. */
    suspend fun markEnforced() = setEnforced(true)

    suspend fun acknowledgeNotice() = settings.writeCache(NOTICE, "true", clock())

    private suspend fun setEnforced(enforced: Boolean) {
        val was = enforced()
        settings.writeCache(ENFORCED, enforced.toString(), clock())
        if (enforced && !was) settings.writeCache(NOTICE, "false", clock())
    }

    private suspend fun enforced(): Boolean = settings.readCache(ENFORCED)?.first == "true"

    /** OpenRouter's list of models with ZDR endpoints, cached for a day; empty if it has never loaded. */
    suspend fun zdrModels(): Set<String> {
        val cached = settings.readCache(MODELS)?.let { (json, at) ->
            runCatching { Json.decodeFromString<Set<String>>(json) }.getOrNull()?.let { it to at }
        }
        if (cached != null && clock() - cached.second < MAX_AGE_MS) return cached.first
        return try {
            api.zdrModels().also { settings.writeCache(MODELS, Json.encodeToString(it), clock()) }
        } catch (e: OpenRouterException) {
            cached?.first.orEmpty()
        }
    }

    private companion object {
        const val ENFORCED = "zdr_enforced"
        const val NOTICE = "zdr_notice_acknowledged"
        const val MODELS = "zdr_models"
        const val MAX_AGE_MS = 24L * 3600 * 1000
    }
}

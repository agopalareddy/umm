package io.github.agopalareddy.umm.sync.group

import io.github.agopalareddy.umm.core.sync.Member
import kotlinx.serialization.Serializable

@Serializable
data class GroupState(
    val groupId: String,
    val key: String,
    val epoch: Int,
    val previousKey: String?,
    val previousUntil: Long,
    val members: List<Member>,
    val relays: List<String> = DEFAULT_RELAYS,
    val lanEnabled: Boolean = true,
    val relayEnabled: Boolean = true,
) {
    companion object {
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
        )
    }
}

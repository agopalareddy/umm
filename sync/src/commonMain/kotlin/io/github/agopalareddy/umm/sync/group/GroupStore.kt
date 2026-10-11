package io.github.agopalareddy.umm.sync.group

import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.core.auth.SecretCipher
import io.github.agopalareddy.umm.sync.crypto.DeviceKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class GroupStore(
    val store: KeyValueStore,
    private val cipher: SecretCipher,
    private val defaultName: String? = null,
) {
    val device: DeviceKeys = run {
        val stored = store.getString(KEY_DEVICE_PRIV)
        if (stored != null) {
            val decrypted = cipher.decrypt(Base64.UrlSafe.decode(stored))
            DeviceKeys(decrypted)
        } else {
            val generated = DeviceKeys.generate()
            val encrypted = cipher.encrypt(generated.privateKey)
            store.putString(KEY_DEVICE_PRIV, Base64.UrlSafe.encode(encrypted))
            generated
        }
    }

    var deviceName: String
        get() = store.getString(KEY_DEVICE_NAME)?.let {
            runCatching {
                cipher.decrypt(Base64.UrlSafe.decode(it)).decodeToString()
            }.getOrNull()
        } ?: defaultDeviceName()
        set(value) {
            val encrypted = cipher.encrypt(value.encodeToByteArray())
            store.putString(KEY_DEVICE_NAME, Base64.UrlSafe.encode(encrypted))
        }

    private val _state = MutableStateFlow<GroupState?>(
        store.getString(KEY_GROUP_STATE)?.let { stored ->
            runCatching {
                val decrypted = cipher.decrypt(Base64.UrlSafe.decode(stored))
                Json.decodeFromString<GroupState>(decrypted.decodeToString())
            }.getOrNull()
        }
    )
    val state: StateFlow<GroupState?> = _state.asStateFlow()

    fun save(state: GroupState) {
        val json = Json.encodeToString(GroupState.serializer(), state)
        val encrypted = cipher.encrypt(json.encodeToByteArray())
        store.putString(KEY_GROUP_STATE, Base64.UrlSafe.encode(encrypted))
        _state.value = state
    }

    fun clear() {
        store.remove(KEY_GROUP_STATE)
        _state.value = null
    }

    private fun defaultDeviceName(): String {
        return defaultName?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
            ?: runCatching { java.io.File("/etc/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: System.getProperty("user.name")
            ?: "Umm"
    }

    private companion object {
        const val KEY_DEVICE_PRIV = "sync_device_private_key"
        const val KEY_DEVICE_NAME = "sync_device_name"
        const val KEY_GROUP_STATE = "sync_group_state"
    }
}

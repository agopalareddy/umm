package io.github.agopalareddy.umm.sync.group

import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.core.auth.SecretCipher
import io.github.agopalareddy.umm.core.sync.Member
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupStoreTest {

    private class FakeKeyValueStore : KeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(vararg keys: String) { keys.forEach { map.remove(it) } }
    }

    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(blob: ByteArray): ByteArray = blob.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    @Test
    fun persistsAcrossInstances() {
        val kv = FakeKeyValueStore()
        val cipher = FakeCipher()
        val store1 = GroupStore(kv, cipher)
        store1.deviceName = "Workstation"
        val state = GroupState(
            groupId = "grp-1",
            key = "secret-key",
            epoch = 1,
            previousKey = null,
            previousUntil = 0L,
            members = listOf(Member("dev-1", "Workstation", "linux", "pub-1")),
            relays = listOf("wss://relay.damus.io"),
        )
        store1.save(state)

        val store2 = GroupStore(kv, cipher)
        assertEquals(state, store2.state.value)
        assertEquals("Workstation", store2.deviceName)
        assertArrayEquals(store1.device.publicKey, store2.device.publicKey)
    }

    @Test
    fun deviceKeysAreStable() {
        val kv = FakeKeyValueStore()
        val cipher = FakeCipher()
        val store1 = GroupStore(kv, cipher)
        val pk1 = store1.device.publicKey
        val store2 = GroupStore(kv, cipher)
        val pk2 = store2.device.publicKey
        assertArrayEquals(pk1, pk2)

        store1.clear()
        assertNull(store1.state.value)
        val store3 = GroupStore(kv, cipher)
        assertNull(store3.state.value)
        assertArrayEquals(pk1, store3.device.publicKey)
    }
}

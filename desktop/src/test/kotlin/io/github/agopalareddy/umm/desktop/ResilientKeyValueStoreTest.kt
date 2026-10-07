package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.auth.ApiKeyStore
import io.github.agopalareddy.umm.core.auth.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilientKeyValueStoreTest {
    private class Keyring(var failWrites: Boolean = false, var failReads: Boolean = false) : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? {
            if (failReads) error("keyring locked")
            return values[key]
        }
        override fun putString(key: String, value: String) {
            if (failWrites) error("keyring prompt dismissed")
            values[key] = value
        }
        override fun remove(vararg keys: String) {
            if (failWrites) error("keyring prompt dismissed")
            keys.forEach(values::remove)
        }
    }

    @Test fun healthyKeyringIsUsedAndNothingIsHeldInMemory() {
        val keyring = Keyring()
        val store = ResilientKeyValueStore(keyring)
        store.putString("k", "v")
        assertEquals("v", keyring.values["k"])
        assertEquals("v", store.getString("k"))
        assertFalse(store.failed.value)
    }

    @Test fun aFailedWriteKeepsTheValueInMemoryAndFlagsTheProblem() {
        val keyring = Keyring(failWrites = true)
        val store = ResilientKeyValueStore(keyring)
        store.putString("k", "v")
        assertEquals("v", store.getString("k"))
        assertTrue(keyring.values.isEmpty())
        assertTrue(store.failed.value)
    }

    @Test fun aFailedWriteDoesNotLeaveAnOlderKeyringValueShowing() {
        val keyring = Keyring()
        val store = ResilientKeyValueStore(keyring)
        store.putString("k", "old")
        keyring.failWrites = true
        store.putString("k", "new")
        assertEquals("new", store.getString("k"))
    }

    @Test fun aLaterGoodWriteGoesToTheKeyringAgain() {
        val keyring = Keyring(failWrites = true)
        val store = ResilientKeyValueStore(keyring)
        store.putString("k", "one")
        keyring.failWrites = false
        store.putString("k", "two")
        assertEquals("two", keyring.values["k"])
        assertEquals("two", store.getString("k"))
    }

    @Test fun removeClearsBothAndNeverThrows() {
        val keyring = Keyring()
        val store = ResilientKeyValueStore(keyring)
        store.putString("k", "v")
        keyring.failWrites = true
        store.remove("k")
        keyring.failWrites = false
        // The keyring still has it (it refused the delete) but memory no longer shadows nothing: the failure is flagged.
        assertTrue(store.failed.value)
    }

    @Test fun anUnreadableKeyringReadsAsEmptyNotAnError() {
        val store = ResilientKeyValueStore(Keyring(failReads = true))
        assertNull(store.getString("k"))
    }

    @Test fun apiKeyStoreNeverThrowsWhenTheKeyringRefuses() {
        val store = ResilientKeyValueStore(Keyring(failWrites = true))
        val keys = ApiKeyStore(store, PassThroughCipher)
        keys.set("sk-or-test")
        assertEquals("sk-or-test", keys.get())
        keys.clear()
        assertNull(keys.get())
    }
}

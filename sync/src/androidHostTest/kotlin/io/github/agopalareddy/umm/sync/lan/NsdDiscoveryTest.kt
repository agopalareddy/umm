package io.github.agopalareddy.umm.sync.lan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NsdDiscoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun advertiseReturnsCloseable() {
        val discovery = NsdDiscovery(context)
        val handle = discovery.advertise(8080, "test-hash", "device-1")
        assertNotNull(handle)
        handle.close()
    }
}

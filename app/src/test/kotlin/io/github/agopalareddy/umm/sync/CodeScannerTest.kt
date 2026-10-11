package io.github.agopalareddy.umm.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CodeScannerTest {
    @Test
    fun playServicesMissingReturnsNull() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scan = scanPairingCode(context)
        assertNull(scan)
    }
}

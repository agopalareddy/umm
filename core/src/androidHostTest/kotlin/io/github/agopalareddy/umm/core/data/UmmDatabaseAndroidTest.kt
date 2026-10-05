package io.github.agopalareddy.umm.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UmmDatabaseAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun freshInstall_createsUmmDbAndSeeds() = runTest {
        val db = buildUmmDatabase(context)
        try {
            Seeds.apps.forEach { (pkg, category) -> assertEquals(pkg, category, CategoryRepository(db).categoryFor(pkg)) }
            assertTrue(context.getDatabasePath("umm.db").exists())
        } finally {
            db.close()
        }
    }
}

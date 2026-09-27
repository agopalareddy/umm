package io.github.agopalareddy.umm.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CategoryRepositoryTest {
    private val db = UmmDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val repo = CategoryRepository(db)

    @After fun tearDown() = db.close()

    @Test fun seedsMatchSpec() = runTest {
        val expected = mapOf(
            "com.google.android.gm" to Category.EMAIL,
            "com.microsoft.office.outlook" to Category.EMAIL,
            "com.whatsapp" to Category.MESSAGING,
            "com.whatsapp.w4b" to Category.MESSAGING,
            "com.google.android.apps.messaging" to Category.MESSAGING,
            "com.facebook.orca" to Category.MESSAGING,
            "org.telegram.messenger" to Category.MESSAGING,
            "com.instagram.android" to Category.SOCIAL,
            "com.facebook.katana" to Category.SOCIAL,
            "com.twitter.android" to Category.SOCIAL,
            "com.linkedin.android" to Category.SOCIAL,
            "com.google.android.keep" to Category.NOTES,
            "com.google.android.apps.docs.editors.docs" to Category.NOTES,
            "notion.id" to Category.NOTES,
        )
        expected.forEach { (pkg, cat) -> assertEquals(pkg, cat, repo.categoryFor(pkg)) }

        val configs = repo.observeAll().first().associateBy { it.category }
        assertEquals(Category.entries.toSet(), configs.keys)
        assertEquals(CleanupLevel.FORMATTED, configs.getValue(Category.EMAIL).level)
        assertEquals(CleanupLevel.FORMATTED, configs.getValue(Category.NOTES).level)
        assertEquals(CleanupLevel.LIGHT, configs.getValue(Category.MESSAGING).level)
        assertEquals(CleanupLevel.LIGHT, configs.getValue(Category.SOCIAL).level)
        assertNull(configs.getValue(Category.OTHER).level)
        configs.values.forEach { assertEquals(ScriptPreference.LATIN, it.script) }
    }

    @Test fun unknownAppIsOther() = runTest {
        assertEquals(Category.OTHER, repo.categoryFor("com.example.x"))
        assertEquals(Category.OTHER, repo.configFor("com.example.x").category)
    }

    @Test fun assignPersistsAndOtherUnassigns() = runTest {
        repo.assign("com.example.x", Category.EMAIL)
        assertEquals(Category.EMAIL, repo.categoryFor("com.example.x"))
        repo.assign("com.example.x", Category.OTHER)
        assertEquals(Category.OTHER, repo.categoryFor("com.example.x"))
        assertFalse("com.example.x" in repo.appsIn(Category.EMAIL))
    }

    @Test fun reassignMovesSeededApp() = runTest {
        repo.assign("com.whatsapp", Category.SOCIAL)
        assertEquals(Category.SOCIAL, repo.categoryFor("com.whatsapp"))
    }

    @Test fun updateChangesLevelAndScript() = runTest {
        repo.update(CategoryConfig(Category.MESSAGING, CleanupLevel.POLISHED, ScriptPreference.NATIVE))
        assertEquals(
            CategoryConfig(Category.MESSAGING, CleanupLevel.POLISHED, ScriptPreference.NATIVE),
            repo.configFor("com.whatsapp"),
        )
    }
}

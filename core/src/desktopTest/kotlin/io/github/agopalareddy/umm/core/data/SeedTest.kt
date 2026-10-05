package io.github.agopalareddy.umm.core.data

import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedTest {
    private val db = inMemoryUmmDatabase()
    private val repo = CategoryRepository(db)

    @After fun tearDown() = db.close()

    @Test fun newDatabase_seedsEveryCategoryAndApp() = runTest {
        val configs = repo.observeAll().first().associateBy { it.category }
        assertEquals(Seeds.levels.keys, configs.keys)
        Seeds.levels.forEach { (category, level) ->
            assertEquals(category.name, level, configs.getValue(category).level)
            assertEquals(ScriptPreference.LATIN, configs.getValue(category).script)
        }
        Seeds.apps.forEach { (pkg, category) -> assertEquals(pkg, category, repo.categoryFor(pkg)) }
    }
}

package io.github.agopalareddy.umm.core.data

import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryRepository(db: UmmDatabase) {
    private val dao = db.categoryDao()

    fun observeAll(): Flow<List<CategoryConfig>> = dao.observeAll().map { list ->
        list.map { it.toConfig() }.sortedBy { it.category.ordinal }
    }

    suspend fun categoryFor(packageName: String): Category = dao.categoryOf(packageName) ?: Category.OTHER

    suspend fun configFor(packageName: String): CategoryConfig {
        val category = categoryFor(packageName)
        return dao.get(category)?.toConfig() ?: CategoryConfig(category, null, ScriptPreference.LATIN)
    }

    /** Assigning to [Category.OTHER] removes the app's assignment. */
    suspend fun assign(packageName: String, category: Category) {
        if (category == Category.OTHER) dao.unassign(packageName)
        else dao.assign(AppAssignmentEntity(packageName, category))
    }

    suspend fun appsIn(category: Category): List<String> = dao.appsIn(category)

    suspend fun update(config: CategoryConfig) {
        dao.upsert(CategoryEntity(config.category, config.level, config.script))
    }

    private fun CategoryEntity.toConfig() = CategoryConfig(category, level, script)
}

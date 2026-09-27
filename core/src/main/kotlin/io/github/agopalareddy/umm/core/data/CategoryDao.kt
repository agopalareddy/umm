package io.github.agopalareddy.umm.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface CategoryDao {
    @Query("SELECT * FROM categories")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE category = :category")
    suspend fun get(category: Category): CategoryEntity?

    @Upsert
    suspend fun upsert(entity: CategoryEntity)

    @Query("SELECT category FROM app_assignments WHERE packageName = :packageName")
    suspend fun categoryOf(packageName: String): Category?

    @Query("SELECT packageName FROM app_assignments WHERE category = :category ORDER BY packageName")
    suspend fun appsIn(category: Category): List<String>

    @Upsert
    suspend fun assign(entity: AppAssignmentEntity)

    @Query("DELETE FROM app_assignments WHERE packageName = :packageName")
    suspend fun unassign(packageName: String)
}

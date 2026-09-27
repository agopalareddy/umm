package io.github.agopalareddy.umm.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference

enum class Category { EMAIL, MESSAGING, SOCIAL, NOTES, OTHER }

/** [level] is null only for [Category.OTHER], meaning "use the global default". */
data class CategoryConfig(val category: Category, val level: CleanupLevel?, val script: ScriptPreference)

@Entity(tableName = "categories")
internal data class CategoryEntity(
    @PrimaryKey val category: Category,
    val level: CleanupLevel?,
    val script: ScriptPreference,
)

@Entity(tableName = "app_assignments")
internal data class AppAssignmentEntity(
    @PrimaryKey val packageName: String,
    val category: Category,
)

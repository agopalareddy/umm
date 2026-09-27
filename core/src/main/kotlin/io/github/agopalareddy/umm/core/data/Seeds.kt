package io.github.agopalareddy.umm.core.data

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel

/** First-run category levels and popular apps (spec §7). */
internal object Seeds {
    val levels: Map<Category, CleanupLevel?> = mapOf(
        Category.EMAIL to CleanupLevel.FORMATTED,
        Category.MESSAGING to CleanupLevel.LIGHT,
        Category.SOCIAL to CleanupLevel.LIGHT,
        Category.NOTES to CleanupLevel.FORMATTED,
        Category.OTHER to null,
    )

    val apps: Map<String, Category> = mapOf(
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
}

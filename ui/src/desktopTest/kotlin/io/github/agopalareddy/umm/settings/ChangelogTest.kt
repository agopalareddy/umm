package io.github.agopalareddy.umm.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogTest {

    @Test fun readsReleasesInFileOrder() {
        val releases = parseChangelog(
            listOf(
                "# What's new in Umm",
                "",
                "A note to whoever writes this file.",
                "- a line before any release is not a change",
                "",
                "## 1.3.0",
                "",
                "- **Sync.** Pair your devices.",
                "- Spoken emoji.",
                "",
                "## 1.2.0 - 2026-09-30",
                "- Floating button.",
            ),
        )

        assertEquals(
            listOf(
                Release("1.3.0", null, listOf("**Sync.** Pair your devices.", "Spoken emoji.")),
                Release("1.2.0", "2026-09-30", listOf("Floating button.")),
            ),
            releases,
        )
    }

    @Test fun leavesOutAReleaseWithNoChanges() {
        assertEquals(
            listOf(Release("1.0.0", null, listOf("First release."))),
            parseChangelog(listOf("## 1.1.0", "", "## 1.0.0", "- First release.")),
        )
        assertEquals(emptyList<Release>(), parseChangelog(emptyList()))
    }

    @Test fun splitsBoldMarks() {
        assertEquals(
            listOf("Sync." to true, " Pair your " to false, "devices" to true, "." to false),
            boldParts("**Sync.** Pair your **devices**."),
        )
        assertEquals(listOf("Plain text." to false), boldParts("Plain text."))
    }

    @Test fun theBundledChangelogHasReleasesNewestFirst() {
        val releases = parseChangelog(changelogLines)

        assertTrue("CHANGELOG.md has no release", releases.isNotEmpty())
        assertTrue(releases.all { release -> release.changes.all { it.isNotBlank() } })
        val versions = releases.map { release -> release.version.split('.').map { it.toInt() } }
        val newestFirst = versions.sortedWith(compareBy({ -it[0] }, { -it[1] }, { -it[2] }))
        assertEquals("releases must be listed newest first, as x.y.z", newestFirst, versions)
    }
}

package io.github.agopalareddy.umm.linux

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopEntriesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val user by lazy { tmp.newFolder("user") }
    private val system by lazy { tmp.newFolder("system") }

    private fun write(dir: File, path: String, body: String) =
        File(dir, path).apply { parentFile.mkdirs() }.writeText("[Desktop Entry]\nType=Application\n$body\n")

    private fun entries(locale: Locale = Locale.US) = DesktopEntries(listOf(user, system), locale)

    @Test fun readsNameAndId() {
        write(system, "org.kde.kate.desktop", "Name=Kate")
        assertEquals(listOf(DesktopEntry("org.kde.kate", "Kate")), entries().all())
    }

    @Test fun subdirectoryIdUsesDashes() {
        write(system, "kde4/foo.desktop", "Name=Foo")
        assertEquals("kde4-foo", entries().all().single().id)
    }

    @Test fun skipsHiddenNoDisplayAndNonApplications() {
        write(system, "a.desktop", "Name=A\nNoDisplay=true")
        write(system, "b.desktop", "Name=B\nHidden=true")
        File(system, "c.desktop").writeText("[Desktop Entry]\nType=Link\nName=C\n")
        assertTrue(entries().all().isEmpty())
    }

    @Test fun skipsUmmItself() {
        write(system, "io.github.agopalareddy.Umm.desktop", "Name=Umm")
        assertTrue(entries().all().isEmpty())
    }

    @Test fun earlierDirectoryWins() {
        write(system, "x.desktop", "Name=System X")
        write(user, "x.desktop", "Name=User X")
        assertEquals("User X", entries().label("x"))
    }

    @Test fun hiddenUserEntryHidesTheSystemOne() {
        write(system, "x.desktop", "Name=X")
        write(user, "x.desktop", "Name=X\nHidden=true")
        assertNull(entries().label("x"))
    }

    @Test fun localizedNameWins() {
        write(system, "files.desktop", "Name=Files\nName[de]=Dateien")
        assertEquals("Dateien", entries(Locale.GERMANY).label("files"))
        assertEquals("Files", entries(Locale.US).label("files"))
    }

    @Test fun malformedEntriesAreSkipped() {
        write(system, "noname.desktop", "Exec=foo")
        File(system, "badutf.desktop").writeBytes("[Desktop Entry]\nType=Application\nName=Bad\u0000".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28))
        write(system, "germanonly.desktop", "Name[de]=Nur Deutsch")
        File(system, "dir.desktop").mkdirs()
        write(system, "good.desktop", "Name=Good")
        assertEquals(listOf("good"), entries(Locale.US).all().map { it.id })
    }

    @Test fun ignoresKeysOutsideTheDesktopEntryGroup() {
        File(system, "act.desktop").writeText("[Desktop Entry]\nType=Application\nName=Real\n[Desktop Action new]\nName=Action\n")
        assertEquals("Real", entries().label("act"))
    }

    @Test fun sortedByName() {
        write(system, "b.desktop", "Name=beta")
        write(system, "a.desktop", "Name=Alpha")
        write(system, "c.desktop", "Name=Gamma")
        assertEquals(listOf("Alpha", "beta", "Gamma"), entries().all().map { it.name })
    }

    @Test fun environmentDirectories() {
        val home = tmp.newFolder("home")
        val found = DesktopEntries.directoriesFor(mapOf("XDG_DATA_DIRS" to "/opt/share:/usr/share"), home)
        assertEquals(listOf(File(home, ".local/share/applications"), File("/opt/share/applications"), File("/usr/share/applications")), found)
        val defaults = DesktopEntries.directoriesFor(mapOf("XDG_DATA_HOME" to "/data"), home)
        assertEquals(listOf(File("/data/applications"), File("/usr/local/share/applications"), File("/usr/share/applications")), defaults)
    }
}

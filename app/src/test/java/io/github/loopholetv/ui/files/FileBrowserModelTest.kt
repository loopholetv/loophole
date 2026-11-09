package io.github.loopholetv.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileBrowserModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun model(roots: List<FileRoot>, list: (File) -> List<File>? = { it.listFiles()?.toList() }) =
        FileBrowserModel({ roots }, list)

    private fun folder(m: FileBrowserModel) = m.state.value as FileBrowserUi.Folder

    @Test fun rootsLoadedOnlyWhenAsked() {
        val roots = listOf(FileRoot("Download", tmp.newFolder("dl"), alwaysReadable = false))
        var calls = 0
        val m = FileBrowserModel({ calls++; roots }, { it.listFiles()?.toList() })
        // Constructing (in composition, on Main) does no storage IO.
        assertEquals(0, calls)
        assertEquals(FileBrowserUi.Loading, m.state.value)
        assertFalse(m.up())
        m.refresh() // the browser calls this on IO
        assertEquals(1, calls)
        assertEquals(FileBrowserUi.Roots(roots), m.state.value)
    }

    @Test fun emptyListingsReuseLoadedRoots() {
        val download = FileRoot("Download", tmp.newFolder("dl"), alwaysReadable = false)
        val loophole = FileRoot("Loophole folder", tmp.newFolder("app"), alwaysReadable = true)
        var calls = 0
        val m = FileBrowserModel({ calls++; listOf(download, loophole) }, { null })
        m.refresh()
        m.open(download)
        m.refresh()
        assertEquals(1, calls)
        assertEquals("Can't read this folder. Copy the file into the Loophole folder: ${loophole.dir.path}", folder(m).hint)
    }

    @Test fun copyHintWithoutLoopholeFolderHasNoDanglingPath() {
        val download = FileRoot("Download", tmp.newFolder("dl"), alwaysReadable = false)
        val m = model(listOf(download), list = { null })
        m.refresh()
        m.open(download)
        assertEquals("Can't read this folder. Copy the file into the Loophole folder.", folder(m).hint)
    }

    @Test fun listsFoldersFirstThenPlaylistFilesOnly() {
        val dir = tmp.newFolder("dl")
        listOf("b.M3U", "a.m3u8", "notes.txt", "Clip.mp4", "Zed.m3u", "readme.md", ".hidden.m3u").forEach { File(dir, it).writeText("x") }
        listOf("zeta", "Alpha", ".cache").forEach { File(dir, it).mkdir() }
        val root = FileRoot("Download", dir, alwaysReadable = false)
        val m = model(listOf(root))
        m.open(root)
        val f = folder(m)
        assertEquals(dir, f.dir)
        assertEquals(listOf("Alpha", "zeta", "a.m3u8", "b.M3U", "notes.txt", "Zed.m3u"), f.entries.map { it.name })
        assertEquals(listOf(true, true, false, false, false, false), f.entries.map { it.isDir })
        assertNull(f.hint)
    }

    @Test fun enterAndUpNavigate() {
        val dir = tmp.newFolder("usb")
        File(dir, "lists").mkdir()
        File(dir, "lists/tv.m3u").writeText("x")
        val root = FileRoot("USB drive", dir, alwaysReadable = false)
        val m = model(listOf(root))
        m.open(root)
        m.enter(folder(m).entries.single())
        assertEquals(File(dir, "lists"), folder(m).dir)
        assertEquals(listOf("tv.m3u"), folder(m).entries.map { it.name })
        m.enter(folder(m).entries.single()) // a file: not navigable, state unchanged
        assertEquals(File(dir, "lists"), folder(m).dir)
        assertTrue(m.up())
        assertEquals(dir, folder(m).dir)
    }

    @Test fun upFromRootReturnsToRoots() {
        val loophole = FileRoot("Loophole folder", tmp.newFolder("app"), alwaysReadable = true)
        val roots = listOf(FileRoot("Download", tmp.newFolder("dl"), alwaysReadable = false), loophole)
        val m = model(roots)
        m.refresh()
        m.open(roots[0])
        assertTrue(m.up())
        assertEquals(FileBrowserUi.Roots(roots), m.state.value)
        assertFalse(m.up()) // at the roots: the screen closes instead
    }

    @Test fun unreadableFolderShowsCopyHint() {
        val loophole = FileRoot("Loophole folder", tmp.newFolder("app"), alwaysReadable = true)
        val download = FileRoot("Download", tmp.newFolder("dl"), alwaysReadable = false)
        val m = model(listOf(download, loophole), list = { if (it == download.dir) null else it.listFiles()?.toList() })
        m.open(download)
        assertTrue(folder(m).entries.isEmpty())
        assertEquals("Can't read this folder. Copy the file into the Loophole folder: ${loophole.dir.path}", folder(m).hint)
    }

    @Test fun emptyNonLoopholeFolderShowsCopyHint() {
        val loophole = FileRoot("Loophole folder", tmp.newFolder("app"), alwaysReadable = true)
        val download = FileRoot("Download", tmp.newFolder("dl"), alwaysReadable = false)
        File(download.dir, "movie.mkv").writeText("x") // scoped storage may hide the playlist itself
        val m = model(listOf(download, loophole))
        m.open(download)
        assertEquals("Can't read this folder. Copy the file into the Loophole folder: ${loophole.dir.path}", folder(m).hint)
    }

    @Test fun emptyLoopholeFolderSaysNoPlaylistFiles() {
        val loophole = FileRoot("Loophole folder", tmp.newFolder("app"), alwaysReadable = true)
        val m = model(listOf(loophole))
        m.open(loophole)
        assertEquals("No playlist files here", folder(m).hint)
    }

    @Test fun refreshRelistsAfterPermissionGrant() {
        val download = FileRoot("Download", tmp.newFolder("dl"), alwaysReadable = false)
        File(download.dir, "tv.m3u").writeText("x")
        var granted = false
        val m = model(listOf(download), list = { if (granted) it.listFiles()?.toList() else null })
        m.open(download)
        assertTrue(folder(m).entries.isEmpty())
        granted = true
        m.refresh()
        assertEquals(listOf("tv.m3u"), folder(m).entries.map { it.name })
    }
}

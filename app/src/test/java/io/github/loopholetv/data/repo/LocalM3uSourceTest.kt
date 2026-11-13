package io.github.loopholetv.data.repo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant

private const val AUTHORITY = "io.github.loopholetv.test.files"
private const val NOW_MS = 1_800_000_000_000L

private val PLAYLIST = """
    #EXTM3U
    #EXTINF:-1 group-title="News",One
    http://example.test/live/1.ts
    #EXTINF:-1 group-title="News",Two
    http://example.test/live/2.ts
""".trimIndent() + "\n"

private val OLD_PLAYLIST = """
    #EXTM3U
    #EXTINF:-1,Old
    http://example.test/live/old.ts
""".trimIndent() + "\n"

/** Serves files from [root] by name; names starting with "revoked" throw like a revoked grant. */
class TestFilesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.lastPathSegment.orEmpty()
        if (name.startsWith("revoked")) throw SecurityException("Permission Denial: no grant for $uri")
        if (name.startsWith("stale")) throw IllegalArgumentException("Failed to determine if $uri is child of root")
        if (name.startsWith("cancelled")) throw kotlinx.coroutines.CancellationException("cancelled mid-open")
        val file = File(root, name)
        if (!file.isFile) throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Living room.m3u")) }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        lateinit var root: File
    }
}

@RunWith(RobolectricTestRunner::class)
class LocalM3uSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var copies: File
    private lateinit var opener: LocalM3uOpener
    private val resolver get() = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver

    @Before fun setUp() {
        copies = File(tmp.root, "sources")
        TestFilesProvider.root = tmp.newFolder("provided")
        Robolectric.setupContentProvider(TestFilesProvider::class.java, AUTHORITY)
        opener = LocalM3uOpener(resolver, copies, clock = { Instant.ofEpochMilli(NOW_MS) })
    }

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.use { it.readBytes() }

    private suspend fun LocalM3uSource.collect(): List<ImportedChannel> {
        val out = mutableListOf<ImportedChannel>()
        stream { if (it is ImportedChannel) out += it }
        return out
    }

    private fun tempFiles() = copies.listFiles().orEmpty().filter { it.name.startsWith(".tmp-") }

    @Test fun readsFilePathAndKeepsCopy() = runTest {
        val file = tmp.newFile("list.m3u").apply { writeText(PLAYLIST) }
        val source = opener.open(file.absolutePath, playlistId = null)
        val channels = source.collect()
        assertEquals(listOf("One", "Two"), channels.map { it.name })
        assertEquals("http://example.test/live/1.ts", channels[0].key)
        assertNull(source.usedCopyFrom)
        source.keepCopyAs(7)
        source.close()
        val copy = File(copies, "7.m3u")
        assertEquals(PLAYLIST, copy.readText())
        assertEquals(NOW_MS, copy.lastModified())
        assertTrue(tempFiles().isEmpty())
    }

    @Test fun readsFileUri() = runTest {
        val file = tmp.newFile("uri list.m3u").apply { writeText(PLAYLIST) }
        val source = opener.open(Uri.fromFile(file).toString(), playlistId = null)
        assertEquals(2, source.collect().size)
        source.close()
    }

    @Test fun readsContentUri() = runTest {
        File(TestFilesProvider.root, "list.m3u").writeText(PLAYLIST)
        val location = "content://$AUTHORITY/list.m3u"
        val source = opener.open(location, playlistId = 3)
        assertEquals(listOf("One", "Two"), source.collect().map { it.name })
        assertNull(source.usedCopyFrom)
        source.keepCopyAs(3)
        source.close()
        assertEquals(PLAYLIST, File(copies, "3.m3u").readText())
        assertEquals("Living room.m3u", localFileDisplayName(resolver, location))
    }

    @Test fun missingFileUsesLastGoodCopy() = runTest {
        copies.mkdirs()
        val copy = File(copies, "5.m3u").apply { writeText(OLD_PLAYLIST); setLastModified(1_700_000_000_000L) }
        val source = opener.open(File(tmp.root, "gone.m3u").absolutePath, playlistId = 5)
        assertEquals(Instant.ofEpochMilli(copy.lastModified()), source.usedCopyFrom)
        assertEquals(listOf("Old"), source.collect().map { it.name })
        source.keepCopyAs(5)
        source.close()
        // The copy is not rewritten (its time still says when the file was last read).
        assertEquals(1_700_000_000_000L, copy.lastModified())
        assertEquals(OLD_PLAYLIST, copy.readText())
        assertTrue(tempFiles().isEmpty())
    }

    @Test fun revokedGrantUsesCopy() = runTest {
        copies.mkdirs()
        File(copies, "9.m3u").writeText(OLD_PLAYLIST)
        val source = opener.open("content://$AUTHORITY/revoked.m3u", playlistId = 9)
        assertTrue(source.usedCopyFrom != null)
        assertEquals(listOf("Old"), source.collect().map { it.name })
        source.close()
    }

    @Test fun missingFileWithoutCopyFails() = runTest {
        for (id in listOf(null, 11L)) {
            try {
                opener.open(File(tmp.root, "gone.m3u").absolutePath, playlistId = id)
                fail("expected ImportFailure")
            } catch (e: ImportFailure) {
                assertEquals(ImportError.Unreachable, e.error)
                assertEquals("Couldn't open the file.", e.message)
            }
        }
    }

    @Test fun bomAndCrlfParsed() = runTest {
        val file = tmp.newFile("bom.m3u").apply { writeBytes(fixture("local/bom-crlf.m3u")) }
        val source = opener.open(file.absolutePath, playlistId = null)
        val channels = source.collect()
        source.close()
        assertEquals(listOf("Local One", "Local Two"), channels.map { it.name })
        assertEquals(listOf("http://example.test/live/1.ts", "http://example.test/live/2.ts"), channels.map { it.streamUrl })
        assertEquals("one.example", channels[0].tvgId)
        assertEquals(listOf("News", "Sport"), channels.map { it.group })
    }

    @Test fun htmlFileRejected() = runTest {
        val file = tmp.newFile("page.html").apply { writeBytes(fixture("local/page.html")) }
        try {
            opener.open(file.absolutePath, playlistId = null)
            fail("expected ImportFailure")
        } catch (e: ImportFailure) {
            assertEquals(ImportError.NotAPlaylist, e.error)
            assertFalse(e.message.orEmpty().contains(file.absolutePath))
        }
        assertTrue(tempFiles().isEmpty())
    }

    @Test fun noUsableChannelsReportedLikeUrls() = runTest {
        val empty = opener.open(tmp.newFile("empty.m3u").apply { writeText("#EXTM3U\n") }.absolutePath, null)
        assertTrue(empty.collect().isEmpty())
        assertEquals(ImportError.EmptyPlaylist, empty.noChannelsError())
        empty.close()
        val text = opener.open(tmp.newFile("notes.txt").apply { writeText("shopping list\n") }.absolutePath, null)
        assertTrue(text.collect().isEmpty())
        assertEquals(ImportError.NotAPlaylist, text.noChannelsError())
        text.close()
    }

    @Test fun failedParseLeavesOldCopy() = runTest {
        copies.mkdirs()
        val copy = File(copies, "4.m3u").apply { writeText(OLD_PLAYLIST) }
        val location = "content://$AUTHORITY/broken.m3u"
        // More than the sniff window of good entries, then the stream fails (USB pulled mid-read).
        val good = buildString {
            append("#EXTM3U\n")
            var i = 0
            while (length < 100 * 1024) { append("#EXTINF:-1,C$i\nhttp://example.test/live/$i.ts\n"); i++ }
        }.toByteArray()
        shadowOf(resolver).registerInputStreamSupplier(Uri.parse(location)) { FailingAfter(good) }
        val source = opener.open(location, playlistId = 4)
        try {
            source.collect()
            fail("expected ImportFailure")
        } catch (e: ImportFailure) {
            assertEquals(ImportError.Unreachable, e.error)
            assertFalse(e.message.orEmpty().contains("broken"))
        }
        source.keepCopyAs(4) // must not keep a partial read
        source.close()
        assertEquals(OLD_PLAYLIST, copy.readText())
        assertTrue(tempFiles().isEmpty())
    }

    @Test fun copyWriteFailureStillImportsAndKeepsOldCopy() = runTest {
        copies.mkdirs()
        val copy = File(copies, "6.m3u").apply { writeText(OLD_PLAYLIST) }
        // The disk fills up after a few bytes of the copy.
        val full = LocalM3uOpener(resolver, copies, { Instant.ofEpochMilli(NOW_MS) }) { f -> FailingOut(f, limit = 20) }
        val file = tmp.newFile("list.m3u").apply { writeText(PLAYLIST) }
        val source = full.open(file.absolutePath, playlistId = 6)
        assertEquals(listOf("One", "Two"), source.collect().map { it.name })
        source.keepCopyAs(6)
        source.close()
        assertEquals(OLD_PLAYLIST, copy.readText())
        assertTrue(tempFiles().isEmpty())
    }

    @Test fun uncreatableCopyDirStillImports() = runTest {
        val blocked = tmp.newFile("not-a-dir") // copiesDir is a plain file: no temp can be created
        val o = LocalM3uOpener(resolver, blocked, clock = { Instant.ofEpochMilli(NOW_MS) })
        val file = tmp.newFile("list.m3u").apply { writeText(PLAYLIST) }
        val source = o.open(file.absolutePath, playlistId = 1)
        assertEquals(2, source.collect().size)
        source.keepCopyAs(1)
        source.close()
        assertTrue(blocked.isFile)
    }

    @Test fun staleDocumentIdUsesCopy() = runTest {
        copies.mkdirs()
        File(copies, "8.m3u").writeText(OLD_PLAYLIST)
        val source = opener.open("content://$AUTHORITY/stale.m3u", playlistId = 8)
        assertTrue(source.usedCopyFrom != null)
        assertEquals(listOf("Old"), source.collect().map { it.name })
        source.close()
    }

    @Test fun cancellationIsNotTreatedAsAnUnreadableFile() = runTest {
        // CancellationException is an IllegalStateException: it must stop the open, not fall back to the copy.
        copies.mkdirs()
        File(copies, "8.m3u").writeText(OLD_PLAYLIST)
        try {
            opener.open("content://$AUTHORITY/cancelled.m3u", playlistId = 8)
            fail("expected the cancellation to propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("cancelled mid-open", e.message)
        }
    }

    @Test fun oldOrphanTempsRemoved() = runTest {
        copies.mkdirs()
        val old = File(copies, ".tmp-orphan").apply { writeText("x"); setLastModified(NOW_MS - 3_600_000) }
        val fresh = File(copies, ".tmp-inflight").apply { writeText("x"); setLastModified(NOW_MS) }
        val file = tmp.newFile("list.m3u").apply { writeText(PLAYLIST) }
        val source = opener.open(file.absolutePath, playlistId = null)
        source.collect()
        assertFalse(old.exists())
        assertTrue(fresh.exists()) // may belong to an import still running
        source.close()
    }

    private class FailingOut(file: File, private val limit: Int) : java.io.FileOutputStream(file) {
        private var written = 0
        override fun write(b: Int) { if (++written > limit) throw IOException("ENOSPC") else super.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > limit) throw IOException("ENOSPC")
            written += len
            super.write(b, off, len)
        }
    }

    @Test fun unkeptTempRemovedOnClose() = runTest {
        val file = tmp.newFile("list.m3u").apply { writeText(PLAYLIST) }
        val source = opener.open(file.absolutePath, playlistId = null)
        source.collect()
        source.close()
        assertTrue(tempFiles().isEmpty())
        assertTrue(copies.listFiles().orEmpty().none { it.name.endsWith(".m3u") })
    }

    @Test fun deleteCopyRemovesIt() {
        copies.mkdirs()
        val copy = File(copies, "2.m3u").apply { writeText(OLD_PLAYLIST) }
        opener.deleteCopy(2)
        assertFalse(copy.exists())
        opener.deleteCopy(2) // no copy: no-op
    }

    @Test fun displayNameFallsBackToLastSegment() {
        assertEquals("tv.m3u", localFileDisplayName(resolver, "/storage/1234-ABCD/lists/tv.m3u"))
        assertEquals("my list.m3u", localFileDisplayName(resolver, "file:///sdcard/Download/my%20list.m3u"))
    }

    private class FailingAfter(private val bytes: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else throw IOException("read failed: /storage/broken")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) throw IOException("read failed: /storage/broken")
            val n = minOf(len, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }
}

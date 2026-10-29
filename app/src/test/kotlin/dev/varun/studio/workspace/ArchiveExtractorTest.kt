package dev.varun.studio.workspace

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Repository import. Archives come from the internet, so the extractor is a
 * security boundary: a "zip slip" entry (../../) must never escape the
 * destination, and size/entry budgets must stop a zip bomb filling the phone.
 */
class ArchiveExtractorTest {

    private lateinit var tmp: File
    private lateinit var dest: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "varun-archive-${System.nanoTime()}")
        tmp.mkdirs()
        dest = File(tmp, "out")
    }

    @After
    fun tearDown() { tmp.deleteRecursively() }

    /** Build a zip whose entry names are exactly as given (including hostile ones). */
    private fun makeZip(name: String, entries: Map<String, String>): File {
        val f = File(tmp, name)
        ZipOutputStream(FileOutputStream(f)).use { zos ->
            for ((path, body) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(body.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return f
    }

    // ------------------------------------------------------------ happy path

    @Test
    fun `extracts a simple zip preserving structure and bytes`() {
        val zip = makeZip(
            "repo.zip",
            linkedMapOf(
                "repo-main/README.md" to "# hello\n",
                "repo-main/src/Main.kt" to "fun main() {}\n",
            ),
        )
        val r = ArchiveExtractor.extract(zip, dest)
        assertTrue("expected Success, got $r", r is ArchiveExtractor.ExtractResult.Success)
        r as ArchiveExtractor.ExtractResult.Success
        assertEquals(2, r.filesWritten)
        assertEquals(0, r.skipped)
        assertTrue(r.bytesWritten > 0)
        assertEquals("# hello\n", File(dest, "repo-main/README.md").readText())
        assertEquals("fun main() {}\n", File(dest, "repo-main/src/Main.kt").readText())
    }

    @Test
    fun `reports the single wrapping directory GitHub tarballs use`() {
        val zip = makeZip(
            "wrapped.zip",
            linkedMapOf(
                "owner-repo-sha/a.txt" to "a\n",
                "owner-repo-sha/b.txt" to "b\n",
            ),
        )
        val r = ArchiveExtractor.extract(zip, dest) as ArchiveExtractor.ExtractResult.Success
        assertEquals("owner-repo-sha", r.rootPrefix)
    }

    @Test
    fun `rootPrefix is null when entries have several top-level names`() {
        val zip = makeZip("mixed.zip", linkedMapOf("a/x.txt" to "x\n", "b/y.txt" to "y\n"))
        val r = ArchiveExtractor.extract(zip, dest) as ArchiveExtractor.ExtractResult.Success
        assertNull(r.rootPrefix)
    }

    @Test
    fun `progress callback reports each entry`() {
        val zip = makeZip("p.zip", linkedMapOf("a.txt" to "a\n", "b.txt" to "b\n", "c.txt" to "c\n"))
        val seen = ArrayList<String>()
        ArchiveExtractor.extract(zip, dest, progress = { _, name -> seen.add(name) })
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), seen)
    }

    // ------------------------------------------------------------- security

    @Test
    fun `zip slip entry escaping the destination is skipped, not written`() {
        val victim = File(tmp.parentFile, "varun-zipslip-${System.nanoTime()}.txt")
        val zip = makeZip("slip.zip", linkedMapOf("../../${victim.name}" to "pwned\n"))
        val r = ArchiveExtractor.extract(zip, dest)
        assertTrue(r is ArchiveExtractor.ExtractResult.Success)
        r as ArchiveExtractor.ExtractResult.Success
        assertEquals("the hostile entry must be skipped", 1, r.skipped)
        assertEquals(0, r.filesWritten)
        assertFalse("nothing may be written outside the destination", victim.exists())
    }

    @Test
    fun `absolute path entry inside a zip is skipped`() {
        val zip = makeZip("abs.zip", linkedMapOf("/tmp/varun-abs-escape.txt" to "pwned\n"))
        val r = ArchiveExtractor.extract(zip, dest) as ArchiveExtractor.ExtractResult.Success
        assertEquals(1, r.skipped)
        assertEquals(0, r.filesWritten)
        assertFalse(File("/tmp/varun-abs-escape.txt").exists())
    }

    @Test
    fun `nested traversal that still resolves inside the destination is allowed`() {
        // "a/../b.txt" normalises to "b.txt" which IS inside dest - not an attack.
        val zip = makeZip("inner.zip", linkedMapOf("a/../b.txt" to "fine\n"))
        val r = ArchiveExtractor.extract(zip, dest)
        assertTrue(r is ArchiveExtractor.ExtractResult.Success)
        r as ArchiveExtractor.ExtractResult.Success
        // Either it lands inside dest as b.txt, or it is skipped. What must
        // never happen is a write anywhere outside dest.
        assertEquals("one entry accounted for", 1, r.filesWritten + r.skipped)
        if (r.filesWritten == 1) {
            assertTrue(File(dest, "b.txt").isFile)
            assertEquals("fine\n", File(dest, "b.txt").readText())
        }
        val outside = File(tmp.parentFile, "b.txt")
        assertFalse("no write outside the destination", outside.exists())
    }

    @Test
    fun `directory entries are not counted as files`() {
        val f = File(tmp, "dirs.zip")
        ZipOutputStream(FileOutputStream(f)).use { zos ->
            zos.putNextEntry(ZipEntry("onlydir/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("onlydir/file.txt"))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        val r = ArchiveExtractor.extract(f, dest) as ArchiveExtractor.ExtractResult.Success
        assertEquals(1, r.filesWritten)
    }

    // --------------------------------------------------------------- budgets

    @Test
    fun `entry count budget stops a zip bomb`() {
        val many = (1..50).associate { "f$it.txt" to "x" }
        val zip = makeZip("bomb.zip", many)
        val r = ArchiveExtractor.extract(
            zip,
            dest,
            ArchiveExtractor.Options(maxEntries = 5),
        ) as ArchiveExtractor.ExtractResult.Success
        assertEquals(5, r.filesWritten)
        assertEquals(45, r.skipped)
    }

    @Test
    fun `total byte budget aborts with a Failure the user can understand`() {
        val big = "x".repeat(200_000)
        val zip = makeZip("big.zip", linkedMapOf("a.txt" to big, "b.txt" to big))
        val r = ArchiveExtractor.extract(
            zip,
            dest,
            ArchiveExtractor.Options(maxTotalBytes = 100_000),
        )
        assertTrue("expected Failure once the budget is blown, got $r", r is ArchiveExtractor.ExtractResult.Failure)
        val msg = (r as ArchiveExtractor.ExtractResult.Failure).message
        assertTrue("message must mention the budget: $msg", msg.contains("budget"))
    }

    @Test
    fun `single entry byte budget is enforced`() {
        val big = "x".repeat(50_000)
        val zip = makeZip("onebig.zip", linkedMapOf("a.txt" to big))
        val r = ArchiveExtractor.extract(
            zip,
            dest,
            ArchiveExtractor.Options(maxSingleEntryBytes = 1_000),
        )
        assertTrue(r is ArchiveExtractor.ExtractResult.Failure)
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `missing archive is a clean Failure, not an exception`() {
        val r = ArchiveExtractor.extract(File(tmp, "does-not-exist.zip"), dest)
        assertTrue(r is ArchiveExtractor.ExtractResult.Failure)
        assertTrue((r as ArchiveExtractor.ExtractResult.Failure).message.contains("not found"))
    }

    @Test
    fun `a directory passed as the archive is a Failure`() {
        val r = ArchiveExtractor.extract(tmp, dest)
        assertTrue(r is ArchiveExtractor.ExtractResult.Failure)
    }

    @Test
    fun `unsupported extension is rejected with guidance`() {
        val f = File(tmp, "thing.rar").apply { writeText("not a zip") }
        val r = ArchiveExtractor.extract(f, dest)
        assertTrue(r is ArchiveExtractor.ExtractResult.Failure)
        val msg = (r as ArchiveExtractor.ExtractResult.Failure).message
        assertTrue("message should name the supported types: $msg", msg.contains(".zip"))
    }

    @Test
    fun `corrupt zip is a Failure rather than a crash`() {
        val f = File(tmp, "corrupt.zip").apply { writeText("this is definitely not a zip archive") }
        val r = ArchiveExtractor.extract(f, dest)
        assertTrue("corrupt input must be handled, got $r", r is ArchiveExtractor.ExtractResult.Failure)
    }

    @Test
    fun `an empty zip succeeds with zero files`() {
        val zip = makeZip("empty.zip", emptyMap())
        val r = ArchiveExtractor.extract(zip, dest)
        assertTrue(r is ArchiveExtractor.ExtractResult.Success)
        assertEquals(0, (r as ArchiveExtractor.ExtractResult.Success).filesWritten)
    }

    // ------------------------------------------------------------------ tar

    @Test
    fun `tar archive is extracted by the hand-written reader`() {
        val tar = File(tmp, "sample.tar")
        tar.writeBytes(buildTar(listOf("hello.txt" to "hello tar\n", "sub/other.txt" to "other\n")))
        val r = ArchiveExtractor.extract(tar, dest)
        assertTrue("expected Success, got $r", r is ArchiveExtractor.ExtractResult.Success)
        r as ArchiveExtractor.ExtractResult.Success
        assertEquals(2, r.filesWritten)
        assertEquals("hello tar\n", File(dest, "hello.txt").readText())
        assertEquals("other\n", File(dest, "sub/other.txt").readText())
    }

    @Test
    fun `tar entry trying to escape is skipped`() {
        val victim = File(tmp.parentFile, "varun-tar-slip-${System.nanoTime()}.txt")
        val tar = File(tmp, "evil.tar")
        tar.writeBytes(buildTar(listOf("../../${victim.name}" to "pwned\n")))
        val r = ArchiveExtractor.extract(tar, dest) as ArchiveExtractor.ExtractResult.Success
        assertEquals(1, r.skipped)
        assertFalse(victim.exists())
    }

    @Test
    fun `tar symlink entries are never materialised`() {
        // typeflag '2' = symlink. Creating links from an untrusted archive is
        // how a traversal escape survives path validation, so it must be skipped.
        val tar = File(tmp, "link.tar")
        tar.writeBytes(buildTar(listOf("target.txt" to "data\n"), extraTypeFlags = listOf('2')))
        val r = ArchiveExtractor.extract(tar, dest)
        assertTrue(r is ArchiveExtractor.ExtractResult.Success)
        val links = dest.walkTopDown().filter {
            runCatching { java.nio.file.Files.isSymbolicLink(it.toPath()) }.getOrDefault(false)
        }.toList()
        assertTrue("no symlinks may be created: $links", links.isEmpty())
    }

    @Test
    fun `tar gz is decompressed before parsing`() {
        val raw = buildTar(listOf("a.txt" to "compressed contents\n"))
        val tgz = File(tmp, "sample.tar.gz")
        java.util.zip.GZIPOutputStream(FileOutputStream(tgz)).use { it.write(raw) }
        val r = ArchiveExtractor.extract(tgz, dest)
        assertTrue("expected Success, got $r", r is ArchiveExtractor.ExtractResult.Success)
        assertEquals("compressed contents\n", File(dest, "a.txt").readText())
    }

    /**
     * Minimal POSIX/ustar writer, only what the extractor parses: name, size,
     * typeflag and the 512-byte block padding. Checksum is computed properly so
     * the fixture is also valid for external tools.
     */
    private fun buildTar(
        entries: List<Pair<String, String>>,
        extraTypeFlags: List<Char> = emptyList(),
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val all = entries.toMutableList()
        val flags = MutableList(all.size) { '0' }
        // Append one synthetic entry per requested extra typeflag.
        for ((i, tf) in extraTypeFlags.withIndex()) {
            all.add("synthetic-link-$i" to "")
            flags.add(tf)
        }
        for ((i, pair) in all.withIndex()) {
            val (name, body) = pair
            val bytes = body.toByteArray(Charsets.UTF_8)
            val header = ByteArray(512)
            writeTarString(header, 0, name, 100)
            writeTarString(header, 100, "0000644", 8)      // mode
            writeTarString(header, 108, "0000000", 8)      // uid
            writeTarString(header, 116, "0000000", 8)      // gid
            writeTarString(header, 124, oct(bytes.size, 11), 12) // size
            writeTarString(header, 136, oct(0, 11), 12)          // mtime
            header[156] = flags[i].code.toByte()                        // typeflag
            writeTarString(header, 257, "ustar", 6)        // magic
            writeTarString(header, 263, "00", 2)           // version
            // checksum: sum of all bytes with the checksum field treated as spaces
            for (k in 148 until 156) header[k] = ' '.code.toByte()
            var sum = 0
            for (b in header) sum += (b.toInt() and 0xFF)
            writeTarString(header, 148, oct(sum, 6), 7)
            header[155] = 0
            out.write(header)
            out.write(bytes)
            val pad = (512 - (bytes.size % 512)) % 512
            if (pad > 0) out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024)) // two zero blocks terminate a tar
        return out.toByteArray()
    }

    /** Locale-independent octal, as tar requires plain ASCII digits. */
    private fun oct(value: Int, width: Int): String =
        String.format(java.util.Locale.US, "%0${width}o", value)

    private fun writeTarString(buf: ByteArray, offset: Int, value: String, fieldLen: Int) {
        val src = value.toByteArray(Charsets.UTF_8)
        val n = minOf(src.size, fieldLen - 1)
        System.arraycopy(src, 0, buf, offset, n)
    }

    @Test
    fun `options defaults are sane for a phone`() {
        val o = ArchiveExtractor.Options()
        assertEquals(512L * 1024 * 1024, o.maxTotalBytes)
        assertEquals(20_000, o.maxEntries)
        assertEquals(64L * 1024 * 1024, o.maxSingleEntryBytes)
        assertNotNull(o)
    }
}

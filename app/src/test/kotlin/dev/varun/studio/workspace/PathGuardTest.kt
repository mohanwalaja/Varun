package dev.varun.studio.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PathGuard is the security boundary for every file the app touches: model
 * imports, archive extraction, editor saves and model-proposed edits all pass
 * through it. These tests assert it rejects the classic escape techniques.
 */
class PathGuardTest {

    private val root = File("/tmp/varun-test-root").also { it.mkdirs() }

    private fun denyReason(path: String): PathGuard.Reject? =
        (PathGuard.resolve(root, path) as? PathGuard.Decision.Denied)?.reason

    // ------------------------------------------------------------- allowed

    @Test
    fun `simple relative path is allowed`() {
        val d = PathGuard.resolve(root, "src/Main.kt")
        assertTrue("expected Allowed, got $d", d is PathGuard.Decision.Allowed)
        d as PathGuard.Decision.Allowed
        assertEquals("src/Main.kt", d.relativePath)
        assertEquals(File(root, "src/Main.kt").canonicalPath, d.file.canonicalPath)
    }

    @Test
    fun `backslash separators are normalised to forward slashes`() {
        val d = PathGuard.resolve(root, "src\\main\\App.kt")
        assertTrue(d is PathGuard.Decision.Allowed)
        assertEquals("src/main/App.kt", (d as PathGuard.Decision.Allowed).relativePath)
    }

    @Test
    fun `dot segments inside the root collapse and stay allowed`() {
        val d = PathGuard.resolve(root, "./src/./Main.kt")
        assertTrue("expected Allowed, got $d", d is PathGuard.Decision.Allowed)
        assertEquals("src/Main.kt", (d as PathGuard.Decision.Allowed).relativePath)
    }

    @Test
    fun `isSafe agrees with resolve`() {
        assertTrue(PathGuard.isSafe(root, "a/b.txt"))
        assertFalse(PathGuard.isSafe(root, "../../etc/passwd"))
    }

    // ------------------------------------------------------------ rejected

    @Test
    fun `empty and blank paths are rejected`() {
        assertEquals(PathGuard.Reject.EMPTY, denyReason(""))
        assertEquals(PathGuard.Reject.EMPTY, denyReason("   "))
    }

    @Test
    fun `parent traversal is rejected`() {
        assertEquals(PathGuard.Reject.TRAVERSAL, denyReason("../secret.txt"))
        assertEquals(PathGuard.Reject.TRAVERSAL, denyReason("src/../../secret.txt"))
        assertEquals(PathGuard.Reject.TRAVERSAL, denyReason(".."))
    }

    @Test
    fun `absolute posix paths are rejected`() {
        assertEquals(PathGuard.Reject.ABSOLUTE, denyReason("/etc/passwd"))
    }

    @Test
    fun `windows drive letters are rejected`() {
        val r = denyReason("C:\\Windows\\System32\\config")
        assertNotNull("drive-letter path should be denied", r)
        assertTrue(
            "expected ABSOLUTE or TRAVERSAL, got $r",
            r == PathGuard.Reject.ABSOLUTE || r == PathGuard.Reject.TRAVERSAL,
        )
    }

    @Test
    fun `home directory shortcut is rejected`() {
        assertNotNull(denyReason("~/.ssh/id_rsa"))
    }

    @Test
    fun `NUL byte injection is rejected`() {
        assertEquals(PathGuard.Reject.NULL_BYTE, denyReason("safe.txt\u0000.png"))
    }

    @Test
    fun `git internals are rejected`() {
        assertEquals(PathGuard.Reject.RESERVED_NAME, denyReason(".git/config"))
        assertEquals(PathGuard.Reject.RESERVED_NAME, denyReason(".git"))
    }

    @Test
    fun `overlong paths are rejected`() {
        val long = "a".repeat(PathGuard.MAX_PATH_LENGTH + 1)
        assertEquals(PathGuard.Reject.TOO_LONG, denyReason(long))
        // exactly at the limit is fine
        assertTrue(PathGuard.isSafe(root, "b".repeat(PathGuard.MAX_PATH_LENGTH)))
    }

    @Test
    fun `symlink that escapes the root is rejected after canonicalisation`() {
        // Only meaningful on a filesystem that supports symlinks.
        val outside = File("/tmp/varun-test-outside").apply { mkdirs() }
        val victim = File(outside, "secret.txt").apply { writeText("top secret") }
        val link = File(root, "escape-link")
        link.delete()
        val made = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), victim.toPath())
        }.isSuccess
        if (!made) return // filesystem/permission refuses symlinks; nothing to assert

        val d = PathGuard.resolve(root, "escape-link/secret.txt")
        assertTrue("symlinked escape must be denied, got $d", d is PathGuard.Decision.Denied)
        link.delete()
    }

    @Test
    fun `requireSafe throws SecurityException on a bad path`() {
        var thrown: Throwable? = null
        try {
            PathGuard.requireSafe(root, "../../etc/passwd")
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("expected SecurityException, got $thrown", thrown is SecurityException)
        // and returns the file for a good one
        assertNotNull(PathGuard.requireSafe(root, "ok/file.txt"))
    }

    // ----------------------------------------------------------- relativize

    @Test
    fun `relativize returns a slash-separated relative path`() {
        val f = File(root, "a/b/c.txt")
        assertEquals("a/b/c.txt", PathGuard.relativize(root, f))
    }

    @Test
    fun `relativize returns empty string for the root itself`() {
        assertEquals("", PathGuard.relativize(root, root))
    }

    @Test
    fun `relativize returns null for a file outside the root`() {
        assertNull(PathGuard.relativize(root, File("/tmp/elsewhere/x.txt")))
    }

    // ------------------------------------------------------ sanitizeFileName

    @Test
    fun `sanitizeFileName strips separators and traversal`() {
        val s = PathGuard.sanitizeFileName("../../etc/passwd")
        assertFalse("must not contain a slash: $s", s.contains('/'))
        assertFalse("must not contain a backslash: $s", s.contains('\\'))
        assertFalse("must not contain '..': $s", s.contains(".."))
    }

    @Test
    fun `sanitizeFileName falls back for empty and reserved names`() {
        assertEquals("file", PathGuard.sanitizeFileName(""))
        assertEquals("file", PathGuard.sanitizeFileName("..."))
        assertEquals("backup", PathGuard.sanitizeFileName(".git", "backup"))
    }

    @Test
    fun `sanitizeFileName bounds the length`() {
        val s = PathGuard.sanitizeFileName("x".repeat(500))
        assertTrue("expected <=120 chars, got ${s.length}", s.length <= 120)
    }

    @Test
    fun `sanitizeFileName removes NUL bytes`() {
        assertFalse(PathGuard.sanitizeFileName("a\u0000b").contains('\u0000'))
    }
}

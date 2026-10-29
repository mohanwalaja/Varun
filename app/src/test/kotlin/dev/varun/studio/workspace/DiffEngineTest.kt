package dev.varun.studio.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diff engine drives every "original vs proposed" view and every commit
 * body, so it must be exact: wrong line numbers here would mean the user
 * approves a change that is not the change shown.
 */
class DiffEngineTest {

    // ---------------------------------------------------------- splitLines

    @Test
    fun `splitLines handles LF, CRLF and no trailing newline`() {
        assertEquals(listOf("a", "b", "c"), DiffEngine.splitLines("a\nb\nc"))
        assertEquals(listOf("a", "b"), DiffEngine.splitLines("a\nb\n"))
        assertEquals(listOf("a", "b"), DiffEngine.splitLines("a\r\nb"))
        assertEquals(listOf("a", "b"), DiffEngine.splitLines("a\rb"))
        assertEquals(emptyList<String>(), DiffEngine.splitLines(""))
        assertEquals(listOf(""), DiffEngine.splitLines("\n"))
    }

    @Test
    fun `endsWithNewline is accurate`() {
        assertTrue(DiffEngine.endsWithNewline("a\n"))
        assertTrue(DiffEngine.endsWithNewline("a\r"))
        assertFalse(DiffEngine.endsWithNewline("a"))
        assertFalse(DiffEngine.endsWithNewline(""))
    }

    // ---------------------------------------------------------------- diff

    @Test
    fun `identical text reports no changes`() {
        val r = DiffEngine.diff("a\nb\nc\n", "a\nb\nc\n")
        assertTrue("expected identical", r.identical)
        assertEquals(0, r.added)
        assertEquals(0, r.removed)
        assertEquals(3, r.unchanged)
        assertEquals(0, r.totalChanges)
        assertEquals("", DiffEngine.unifiedDiff("f", "f", "a\nb\nc\n", "a\nb\nc\n"))
    }

    @Test
    fun `a single added line is reported as one addition with correct numbering`() {
        val r = DiffEngine.diff("a\nb\n", "a\nX\nb\n")
        assertFalse(r.identical)
        assertEquals(1, r.added)
        assertEquals(0, r.removed)
        val added = r.lines.filter { it.kind == DiffEngine.LineKind.ADDED }
        assertEquals(1, added.size)
        assertEquals("X", added[0].text)
        assertEquals(-1, added[0].oldNumber)
        assertEquals(2, added[0].newNumber)
    }

    @Test
    fun `a single removed line keeps its original number`() {
        val r = DiffEngine.diff("a\nX\nb\n", "a\nb\n")
        assertEquals(0, r.added)
        assertEquals(1, r.removed)
        val removed = r.lines.single { it.kind == DiffEngine.LineKind.REMOVED }
        assertEquals("X", removed.text)
        assertEquals(2, removed.oldNumber)
        assertEquals(-1, removed.newNumber)
    }

    @Test
    fun `a changed line is one removal plus one addition`() {
        val r = DiffEngine.diff("a\nold\nb\n", "a\nnew\nb\n")
        assertEquals(1, r.added)
        assertEquals(1, r.removed)
        assertEquals(2, r.unchanged)
    }

    @Test
    fun `empty to content and content to empty`() {
        val add = DiffEngine.diff("", "a\nb\n")
        assertEquals(2, add.added)
        assertEquals(0, add.removed)

        val del = DiffEngine.diff("a\nb\n", "")
        assertEquals(0, del.added)
        assertEquals(2, del.removed)

        val none = DiffEngine.diff("", "")
        assertTrue(none.identical)
    }

    @Test
    fun `context lines carry both numbers`() {
        val r = DiffEngine.diff("a\nb\nc\n", "a\nb\nZ\n")
        val ctx = r.lines.filter { it.kind == DiffEngine.LineKind.CONTEXT }
        assertTrue(ctx.isNotEmpty())
        for (c in ctx) {
            assertTrue("context old number must be set", c.oldNumber >= 1)
            assertTrue("context new number must be set", c.newNumber >= 1)
        }
    }

    @Test
    fun `large divergent input falls back to whole-region replace instead of hanging`() {
        // MAX_EDIT_DISTANCE is 5_000_000; two unrelated large blocks exceed it.
        val a = (1..3000).joinToString("\n") { "left-$it" }
        val b = (1..3000).joinToString("\n") { "right-$it" }
        val r = DiffEngine.diff(a, b)
        assertFalse(r.identical)
        assertEquals(3000, r.added)
        assertEquals(3000, r.removed)
    }

    // --------------------------------------------------------------- hunks

    @Test
    fun `hunks group nearby changes and expose unified-diff headers`() {
        val old = (1..40).joinToString("\n") { "line$it" } + "\n"
        val new = (1..40).map { if (it == 5) "CHANGED" else "line$it" }.joinToString("\n") + "\n"
        val r = DiffEngine.diff(old, new)
        val hs = DiffEngine.hunks(r, context = 3)
        assertEquals("a single isolated change should be one hunk", 1, hs.size)
        val h = hs[0]
        assertTrue("oldStart must be >= 1", h.oldStart >= 1)
        assertTrue(h.oldCount >= 1)
        assertTrue(h.newCount >= 1)
        // 3 lines of context above + the change + 3 below
        assertEquals(7, h.lines.size)
    }

    @Test
    fun `two distant changes produce two hunks`() {
        val old = (1..60).joinToString("\n") { "line$it" } + "\n"
        val new = (1..60).map { if (it == 3 || it == 55) "CHANGED$it" else "line$it" }
            .joinToString("\n") + "\n"
        val hs = DiffEngine.hunks(DiffEngine.diff(old, new), context = 2)
        assertEquals(2, hs.size)
    }

    @Test
    fun `unifiedDiff has the standard patch shape`() {
        val u = DiffEngine.unifiedDiff("src/A.kt", "src/A.kt", "a\nb\n", "a\nB\n")
        assertTrue("missing --- header:\n$u", u.startsWith("--- a/src/A.kt\n"))
        assertTrue("missing +++ header:\n$u", u.contains("+++ b/src/A.kt\n"))
        assertTrue("missing @@ hunk header:\n$u", u.contains("@@ -"))
        assertTrue("missing removal marker:\n$u", u.contains("\n-b"))
        assertTrue("missing addition marker:\n$u", u.contains("\n+B"))
    }

    // ------------------------------------------------------------ wordDiff

    @Test
    fun `wordDiff marks only the changed tokens`() {
        val d = DiffEngine.wordDiff("val x = 1", "val x = 2")
        val added = d.parts.filter { it.second == DiffEngine.LineKind.ADDED }.map { it.first }
        val removed = d.parts.filter { it.second == DiffEngine.LineKind.REMOVED }.map { it.first }
        assertTrue("expected the new token in $added", added.any { it.contains("2") })
        assertTrue("expected the old token in $removed", removed.any { it.contains("1") })
        assertFalse("unchanged tokens must not be marked added", added.any { it.contains("val") })
    }

    @Test
    fun `wordDiff of identical lines has no changes`() {
        val d = DiffEngine.wordDiff("same line", "same line")
        assertTrue(d.parts.none { it.second != DiffEngine.LineKind.CONTEXT })
    }

    // ------------------------------------------------------------ similarity

    @Test
    fun `similarity is 1 for identical text and lower for divergent text`() {
        assertEquals(1.0, DiffEngine.similarity("abc\ndef\n", "abc\ndef\n"), 1e-9)
        val s = DiffEngine.similarity("abc\ndef\nghi\n", "abc\nXXX\nghi\n")
        assertTrue("expected partial similarity, got $s", s > 0.0 && s < 1.0)
        assertEquals(0.0, DiffEngine.similarity("", "totally different"), 1e-9)
    }
}

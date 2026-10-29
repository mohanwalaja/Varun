package dev.varun.studio.workspace

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Local code search. It must find real matches, must not descend into build
 * output or binary blobs, and must stop at a bounded number of matches so a
 * pathological pattern cannot hang the UI thread pool on a phone.
 */
class CodeSearchTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "varun-search-${System.nanoTime()}")
        root.mkdirs()
        File(root, "src/main").mkdirs()
        File(root, "src/main/App.kt").writeText(
            """
            package app

            class App {
                // TODO: finish the parser
                fun parse(input: String) = Parser.parse(input)
                val PARSE_LIMIT = 100
            }
            """.trimIndent() + "\n",
        )
        File(root, "src/main/Parser.kt").writeText(
            """
            package app

            object Parser {
                fun parse(input: String): Int = input.length
            }
            """.trimIndent() + "\n",
        )
        File(root, "README.md").writeText("# Project\n\nCall parse() to start.\n")
    }

    @After
    fun tearDown() { root.deleteRecursively() }

    private fun paths(r: CodeSearch.Result) = r.matches.map { it.relativePath }.distinct().sorted()

    // ---------------------------------------------------------------- search

    @Test
    fun `finds a literal match in every file that contains it`() {
        val r = CodeSearch.search(root, "parse")
        assertTrue("expected matches, got ${r.matches.size}", r.matches.isNotEmpty())
        assertTrue("App.kt should match", paths(r).contains("src/main/App.kt"))
        assertTrue("Parser.kt should match", paths(r).contains("src/main/Parser.kt"))
        assertTrue("README.md should match", paths(r).contains("README.md"))
        assertFalse(r.truncated)
        assertTrue(r.filesScanned >= 3)
    }

    @Test
    fun `search is case-insensitive by default`() {
        val lower = CodeSearch.search(root, "PARSE_LIMIT")
        val upper = CodeSearch.search(root, "parse_limit", CodeSearch.Options(caseSensitive = false))
        assertEquals(lower.matches.size, upper.matches.size)
        assertTrue(upper.matches.isNotEmpty())
    }

    @Test
    fun `caseSensitive option actually distinguishes case`() {
        val sensitive = CodeSearch.search(root, "PARSE_LIMIT", CodeSearch.Options(caseSensitive = true))
        val wrong = CodeSearch.search(root, "parse_limit", CodeSearch.Options(caseSensitive = true))
        assertTrue("exact case must match", sensitive.matches.isNotEmpty())
        assertEquals("wrong case must not match", 0, wrong.matches.size)
    }

    @Test
    fun `wholeWord excludes substrings inside longer identifiers`() {
        val all = CodeSearch.search(root, "parse")
        val words = CodeSearch.search(root, "parse", CodeSearch.Options(wholeWord = true))
        assertTrue("plain search finds more", all.matches.size > words.matches.size)
        // "parse" appears as a whole word in Parser.kt's fun parse(...)
        assertTrue(words.matches.any { it.relativePath == "src/main/Parser.kt" })
        // but PARSE_LIMIT must not count as the whole word "parse"
        assertFalse(words.matches.any { it.line.contains("PARSE_LIMIT") })
    }

    @Test
    fun `regex mode matches a pattern`() {
        val r = CodeSearch.search(root, "fun\\s+\\w+\\(", CodeSearch.Options(regex = true))
        assertTrue("expected regex matches", r.matches.isNotEmpty())
        assertTrue(r.matches.any { it.line.contains("fun parse") })
    }

    @Test
    fun `an invalid regex returns no matches instead of throwing`() {
        val r = CodeSearch.search(root, "([unclosed", CodeSearch.Options(regex = true))
        assertEquals(0, r.matches.size)
    }

    @Test
    fun `match records the line number and column`() {
        val r = CodeSearch.search(root, "PARSE_LIMIT", CodeSearch.Options(caseSensitive = true))
        assertEquals(1, r.matches.size)
        val m = r.matches[0]
        assertEquals("src/main/App.kt", m.relativePath)
        assertTrue("line numbers are 1-based", m.lineNumber >= 1)
        assertTrue("column must be found", m.column >= 0)
        assertEquals("PARSE_LIMIT", m.matched)
        assertTrue(m.line.contains("PARSE_LIMIT"))
    }

    @Test
    fun `empty query and missing root return an empty result`() {
        assertEquals(0, CodeSearch.search(root, "").matches.size)
        assertEquals(0, CodeSearch.search(File(root, "nope"), "parse").matches.size)
    }

    @Test
    fun `maxMatches truncates and reports truncation honestly`() {
        val many = File(root, "src/main/Many.kt")
        many.writeText((1..200).joinToString("\n") { "needle line $it" } + "\n")
        val r = CodeSearch.search(root, "needle", CodeSearch.Options(maxMatches = 10))
        assertEquals(10, r.matches.size)
        assertTrue("truncation must be reported, never silent", r.truncated)
    }

    @Test
    fun `build output and vcs directories are not searched`() {
        File(root, "build").mkdirs()
        File(root, "build/Generated.kt").writeText("parse parse parse\n")
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("parse\n")
        val r = CodeSearch.search(root, "parse")
        assertFalse("build/ must be skipped", paths(r).any { it.startsWith("build/") })
        assertFalse(".git/ must be skipped", paths(r).any { it.startsWith(".git/") })
    }

    @Test
    fun `hidden directories are skipped unless includeHidden is set`() {
        File(root, ".secret").mkdirs()
        File(root, ".secret/hidden.kt").writeText("uniqueTokenXyz\n")
        val off = CodeSearch.search(root, "uniqueTokenXyz")
        assertEquals(0, off.matches.size)
        val on = CodeSearch.search(root, "uniqueTokenXyz", CodeSearch.Options(includeHidden = true))
        assertTrue("includeHidden must find it", on.matches.isNotEmpty())
    }

    @Test
    fun `github workflows are still searched when hidden dirs are off`() {
        // .github is a deliberate exception: CI config is real source.
        File(root, ".github/workflows").mkdirs()
        File(root, ".github/workflows/ci.yml").writeText("runs: parse-step\n")
        val r = CodeSearch.search(root, "parse-step")
        assertTrue("expected .github to be searchable", r.matches.isNotEmpty())
    }

    @Test
    fun `files over maxFileBytes are skipped, not read`() {
        val big = File(root, "src/main/Big.kt")
        big.writeText("needle\n" + "x".repeat(50_000))
        val r = CodeSearch.search(root, "needle", CodeSearch.Options(maxFileBytes = 1_000))
        assertFalse("oversized file must not be searched", paths(r).contains("src/main/Big.kt"))
        assertTrue(r.filesSkipped >= 1)
    }

    // ------------------------------------------------------------ text sniff

    @Test
    fun `isProbablyText accepts source and rejects binaries`() {
        assertTrue(CodeSearch.isProbablyText(File(root, "src/main/App.kt")))
        assertTrue(CodeSearch.isProbablyText(File(root, "README.md")))

        val bin = File(root, "image.png")
        bin.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x00, 0x1A.toByte(), 0x0A))
        assertFalse("a NUL byte means binary", CodeSearch.isProbablyText(bin))

        assertFalse("a directory is not a text file", CodeSearch.isProbablyText(File(root, "src")))
        assertFalse("a missing file is not text", CodeSearch.isProbablyText(File(root, "ghost.kt")))
    }

    @Test
    fun `isProbablyText recognises extension-less build files by name`() {
        val mk = File(root, "Makefile").apply { writeText("all:\n\tmake parse\n") }
        assertTrue(CodeSearch.isProbablyText(mk))
        val dk = File(root, "Dockerfile").apply { writeText("FROM scratch\n") }
        assertTrue(CodeSearch.isProbablyText(dk))
    }

    @Test
    fun `an empty file with no extension is treated as text`() {
        val e = File(root, "EMPTYFILE").apply { writeText("") }
        assertTrue(CodeSearch.isProbablyText(e))
    }

    @Test
    fun `shouldSkipDir matches the documented ignore list`() {
        val o = CodeSearch.Options()
        for (d in listOf(".git", "build", "node_modules", ".gradle", "dist", "__pycache__")) {
            assertTrue("$d should be skipped", CodeSearch.shouldSkipDir(d, o))
        }
        assertFalse("src must not be skipped", CodeSearch.shouldSkipDir("src", o))
        assertTrue("hidden dirs are skipped by default", CodeSearch.shouldSkipDir(".idea-local", o))
        assertFalse(
            "includeHidden lets hidden dirs through",
            CodeSearch.shouldSkipDir(".config", CodeSearch.Options(includeHidden = true)),
        )
        assertFalse(".github is explicitly allowed", CodeSearch.shouldSkipDir(".github", o))
    }

    // --------------------------------------------------------- context rank

    @Test
    fun `rankForContext returns existing files capped at maxFiles`() {
        val ranked = CodeSearch.rankForContext(root, "parse", maxFiles = 2)
        assertTrue("expected at most 2 files, got ${ranked.size}", ranked.size <= 2)
        assertTrue(ranked.all { it.exists() })
        assertTrue(ranked.all { it.isFile })
    }

    @Test
    fun `rankForContext prefers the file with more matches`() {
        File(root, "src/main/Heavy.kt").writeText((1..20).joinToString("\n") { "parseTarget$it" } + "\n")
        val ranked = CodeSearch.rankForContext(root, "parseTarget", maxFiles = 5)
        assertTrue(ranked.isNotEmpty())
        assertEquals("Heavy.kt", ranked.first().name)
    }

    @Test
    fun `rankForContext with a query that matches nothing is empty`() {
        assertTrue(CodeSearch.rankForContext(root, "zzzNoSuchSymbolZZZ", maxFiles = 5).isEmpty())
    }
}

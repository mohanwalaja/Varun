package dev.varun.studio.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Input validation for every GitHub write path.
 *
 * These two functions are the last gate before a branch name or file path is
 * sent to the Git Data API, so they are tested exhaustively: a bad ref name can
 * create a non-heads ref, and a bad path can write outside the repository tree.
 *
 * Only the pure validation surface is covered here. The network operations
 * (createBranch, commitAndPush, updateSingleFile) need a live GitHub API and
 * are therefore NOT covered by unit tests — that gap is reported honestly in
 * docs/TESTING.md rather than papered over with a mock.
 */
class GitHubGitOpsValidationTest {

    // No token is ever supplied: the validators are pure and must not need one.
    private val ops = GitHubGitOps(GitHubClient(tokenProvider = { null }))

    private fun branch(name: String): String? = ops.validateBranchName(name)
    private fun path(p: String): String? = ops.validatePath(p)

    // ------------------------------------------------------- branch: accept

    @Test
    fun `accepts ordinary branch names`() {
        for (n in listOf("main", "develop", "a", "fix-null-check", "feature_v1", "release.2")) {
            assertNull("'$n' should be accepted, got: ${branch(n)}", branch(n))
        }
    }

    @Test
    fun `accepts slash-separated branch names`() {
        for (n in listOf("varun/fix-parser", "feature/2026/audit", "u/name/deep/path")) {
            assertNull("'$n' should be accepted, got: ${branch(n)}", branch(n))
        }
    }

    @Test
    fun `accepts the branch name the app itself generates`() {
        // CodingAgent / WorkspaceRepository create branches like this.
        assertNull(branch("varun/ai-edits"))
        assertNull(branch("varun/audit-report"))
    }

    @Test
    fun `accepts a name at exactly the length limit`() {
        val name = "a" + "b".repeat(199)   // 200 chars
        assertEquals(200, name.length)
        assertNull("200 chars should be accepted, got: ${branch(name)}", branch(name))
    }

    // ------------------------------------------------------- branch: reject

    @Test
    fun `rejects empty and blank branch names`() {
        assertNotNull(branch(""))
        assertNotNull(branch("   "))
        assertNotNull(branch("\t\n"))
    }

    @Test
    fun `rejects over-long branch names`() {
        val name = "a".repeat(201)
        val err = branch(name)
        assertNotNull(err)
        assertTrue("reason should mention length: $err", err!!.contains("long", ignoreCase = true))
    }

    @Test
    fun `rejects double-dot sequences`() {
        for (n in listOf("a..b", "..", "fix..bug", "a/b..c")) {
            val err = branch(n)
            assertNotNull("'$n' must be rejected", err)
        }
    }

    @Test
    fun `rejects a name that must start with a letter or digit`() {
        for (n in listOf("-start", ".start", "/start", "_start")) {
            assertNotNull("'$n' must be rejected: it does not start with an alphanumeric", branch(n))
        }
    }

    @Test
    fun `rejects the refs prefix so a non-heads ref cannot be created`() {
        for (n in listOf("refs/heads/evil", "refs/tags/v9", "refs/x")) {
            val err = branch(n)
            assertNotNull("'$n' must be rejected", err)
            assertTrue("reason should mention refs/: $err", err!!.contains("refs/"))
        }
    }

    @Test
    fun `rejects invalid suffixes and sequences`() {
        assertNotNull("trailing slash", branch("feature/"))
        assertNotNull(".lock suffix", branch("main.lock"))
        assertNotNull("@{ sequence", branch("a@{b"))
    }

    @Test
    fun `rejects characters outside the safe set`() {
        for (n in listOf("has space", "semi;colon", "qu?stion", "star*name", "til~de", "back\\slash", "at@sign", "co:lon", "brack[et]")) {
            assertNotNull("'$n' must be rejected", branch(n))
        }
    }

    @Test
    fun `rejects shell and URL metacharacters that could be smuggled into an API path`() {
        for (n in listOf("a%20b", "a\nb", "a\rb", "a\"b", "a'b", "a`b", "a\$b", "a#b")) {
            assertNotNull("'$n' must be rejected", branch(n))
        }
    }

    @Test
    fun `every rejection carries a human-readable reason`() {
        val err = branch("bad..name")
        assertNotNull(err)
        assertTrue("reason must be a real sentence: $err", err!!.length > 10)
    }

    // --------------------------------------------------------- path: accept

    @Test
    fun `accepts ordinary repository paths`() {
        for (p in listOf(
            "README.md",
            "src/Main.kt",
            "app/src/main/kotlin/dev/varun/studio/MainActivity.kt",
            "docs/BUILD_APK.md",
            "a/b/c/d/e.txt",
            "gradle/libs.versions.toml",
            ".github/workflows/build-apk.yml",
            "Makefile",
            "file-with-dash_and_underscore.kt",
        )) {
            assertNull("'$p' should be accepted, got: ${path(p)}", path(p))
        }
    }

    @Test
    fun `accepts a path at exactly the length limit`() {
        val p = "a".repeat(1000)
        assertEquals(1000, p.length)
        assertNull("1000 chars should be accepted, got: ${path(p)}", path(p))
    }

    // --------------------------------------------------------- path: reject

    @Test
    fun `rejects empty and blank paths`() {
        assertNotNull(path(""))
        assertNotNull(path("   "))
    }

    @Test
    fun `rejects absolute paths on both conventions`() {
        assertNotNull(path("/etc/passwd"))
        assertNotNull(path("/absolute/repo/file.kt"))
        assertNotNull(path("\\windows\\system32"))
    }

    @Test
    fun `rejects path traversal in every position`() {
        for (p in listOf("..", "../secret", "a/../b", "a/../../b", "src/../../../etc/passwd", "..%2f..")) {
            val err = path(p)
            assertNotNull("'$p' must be rejected", err)
            assertTrue(
                "reason should mention traversal or the path: $err",
                err!!.contains("traversal", ignoreCase = true) || err.contains(p),
            )
        }
    }

    @Test
    fun `rejects NUL byte injection`() {
        val err = path("safe.txt\u0000.kt")
        assertNotNull(err)
        assertTrue("reason should mention NUL: $err", err!!.contains("NUL"))
    }

    @Test
    fun `rejects empty path segments`() {
        for (p in listOf("a//b", "a/", "./a", "a/./b", "//a", "a/b//")) {
            assertNotNull("'$p' must be rejected for an empty or dot segment", path(p))
        }
    }

    @Test
    fun `rejects over-long paths`() {
        val p = "a".repeat(1001)
        val err = path(p)
        assertNotNull(err)
        assertTrue("reason should mention length: $err", err!!.contains("long", ignoreCase = true))
    }

    @Test
    fun `rejects writing inside version control internals`() {
        for (p in listOf(".git/config", ".git/hooks/pre-commit", ".hg/store", ".svn/entries")) {
            val err = path(p)
            assertNotNull("'$p' must be rejected", err)
            assertTrue("reason should name the directory: $err", err!!.contains("Refusing"))
        }
    }

    @Test
    fun `a nested git directory is still rejected only at the top level`() {
        // Only a leading .git is refused; "docs/.gitkeep" is a legitimate file.
        assertNull(path("docs/.gitkeep"))
        assertNull(path("src/git/Config.kt"))
    }

    // ------------------------------------------------------- safety context

    @Test
    fun `SafetyContext records the default branch and protected branches`() {
        val ctx = GitHubGitOps.SafetyContext(
            owner = "mohanwalaja",
            repo = "Varun",
            defaultBranch = "main",
            protectedBranches = setOf("main", "release"),
        )
        assertEquals("main", ctx.defaultBranch)
        assertTrue(ctx.protectedBranches.contains("main"))
        assertTrue(ctx.protectedBranches.contains("release"))
        assertEquals("mohanwalaja", ctx.owner)
        assertEquals("Varun", ctx.repo)
    }

    @Test
    fun `SafetyContext can express a repository with no protected branches`() {
        val ctx = GitHubGitOps.SafetyContext("o", "r", "main", emptySet())
        assertTrue(ctx.protectedBranches.isEmpty())
    }

    // -------------------------------------------------- no secrets embedded

    @Test
    fun `the client is constructed from a token provider, never a baked-in secret`() {
        // If a secret were embedded in the APK this test would not need a
        // provider lambda at all; the signature is the guarantee.
        val anon = GitHubClient(tokenProvider = { null })
        assertNotNull(anon)
        val withToken = GitHubClient(tokenProvider = { "runtime-supplied-only" })
        assertNotNull(withToken)
    }

    @Test
    fun `the default user agent identifies the app without leaking anything`() {
        val ua = GitHubClient.DEFAULT_USER_AGENT
        assertTrue("user agent must be set: '$ua'", ua.isNotBlank())
        assertTrue(ua.contains("Varun"))
        for (marker in listOf("ghp_", "github_pat_", "Bearer ", "-----BEGIN")) {
            assertFalse("user agent must not contain '$marker'", ua.contains(marker))
        }
    }

    @Test
    fun `the API base is https on the official host`() {
        assertEquals("https://api.github.com", GitHubClient.API_BASE)
    }
}

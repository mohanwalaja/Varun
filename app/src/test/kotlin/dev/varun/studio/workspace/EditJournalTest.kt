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

/**
 * The journal is the safety net for every AI-proposed edit: backup first, then
 * an atomic write, then a journal line. These tests prove the ordering, because
 * a journal that records a change it did not actually make (or vice versa)
 * would silently destroy user source code.
 */
class EditJournalTest {

    private lateinit var root: File
    private lateinit var journal: EditJournal

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "varun-journal-test-${System.nanoTime()}")
        root.mkdirs()
        journal = EditJournal(root)
    }

    @After
    fun tearDown() { root.deleteRecursively() }

    private fun proposal(
        path: String,
        original: String?,
        proposed: String,
        reason: String = "test change",
        isNew: Boolean = false,
    ) = EditJournal.Proposal(path, original, proposed, reason, isNew)

    // -------------------------------------------------------- construction

    @Test
    fun `creating a journal makes the workspace and backup directory`() {
        assertTrue("workspace root must exist", root.isDirectory)
        assertTrue(
            "backup dir must exist",
            File(root, EditJournal.BACKUP_DIR_NAME).isDirectory,
        )
        assertEquals(".varun-journal.log", EditJournal.JOURNAL_NAME)
        assertEquals(".varun-backups", EditJournal.BACKUP_DIR_NAME)
    }

    @Test
    fun `a fresh journal has no entries`() {
        assertTrue(journal.entries().isEmpty())
        assertNull(journal.lastApplied("anything.kt"))
        assertTrue(journal.listBackups().isEmpty())
        assertEquals(0L, journal.backupDirSizeBytes())
    }

    // -------------------------------------------------------- apply / backup

    @Test
    fun `applying to a new file writes it and journals the application`() {
        val p = proposal("src/New.kt", null, "fun main() {}\n", isNew = true)
        val result = journal.applyApproved(p)
        assertTrue("expected Applied, got $result", result is EditJournal.ApplyResult.Applied)

        val written = File(root, "src/New.kt")
        assertTrue("file must exist on disk", written.isFile)
        assertEquals("fun main() {}\n", written.readText())

        val entries = journal.entries()
        assertTrue("an APPLIED entry must be recorded", entries.any { it.kind == EditJournal.EntryKind.APPLIED })
        // a brand-new file has nothing to back up
        assertFalse("no BACKUP entry for a new file", entries.any { it.kind == EditJournal.EntryKind.BACKUP })
    }

    @Test
    fun `applying to an existing file backs it up BEFORE overwriting`() {
        val target = File(root, "Existing.kt").apply { writeText("original contents\n") }
        val result = journal.applyApproved(proposal("Existing.kt", "original contents\n", "changed contents\n"))
        assertTrue(result is EditJournal.ApplyResult.Applied)
        assertEquals("changed contents\n", target.readText())

        val entries = journal.entries()
        val backupIdx = entries.indexOfFirst { it.kind == EditJournal.EntryKind.BACKUP }
        val appliedIdx = entries.indexOfFirst { it.kind == EditJournal.EntryKind.APPLIED }
        assertTrue("a BACKUP entry is required", backupIdx >= 0)
        assertTrue("an APPLIED entry is required", appliedIdx >= 0)
        assertTrue("backup must be journaled before the apply", backupIdx < appliedIdx)

        // The backup file itself must hold the ORIGINAL bytes.
        val backups = journal.listBackups()
        assertEquals(1, backups.size)
        assertEquals("original contents\n", backups[0].readText())
        assertTrue(journal.backupDirSizeBytes() > 0)
    }

    @Test
    fun `applied entry records the sha256 of the proposed text`() {
        val text = "println(\"hello\")\n"
        journal.applyApproved(proposal("A.kt", null, text, isNew = true))
        val applied = journal.lastApplied("A.kt")
        assertNotNull(applied)
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
        assertEquals(expected, applied!!.sha256)
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), applied.bytes)
    }

    @Test
    fun `applied result carries the diff against the original`() {
        val r = journal.applyApproved(proposal("D.kt", "a\nb\n", "a\nB\n"))
        assertTrue(r is EditJournal.ApplyResult.Applied)
        val diff = (r as EditJournal.ApplyResult.Applied).diff
        assertNotNull("diff against the original is required for review", diff)
        assertEquals(1, diff!!.added)
        assertEquals(1, diff.removed)
    }

    // ------------------------------------------------------------- rollback

    @Test
    fun `rollback restores the original bytes exactly`() {
        File(root, "R.kt").writeText("line1\nline2\nline3\n")
        journal.applyApproved(proposal("R.kt", "line1\nline2\nline3\n", "line1\nBROKEN\nline3\n"))
        assertEquals("line1\nBROKEN\nline3\n", File(root, "R.kt").readText())

        val r = journal.rollback("R.kt")
        assertTrue("rollback should succeed, got $r", r is EditJournal.ApplyResult.Applied)
        assertEquals("line1\nline2\nline3\n", File(root, "R.kt").readText())
        assertTrue(
            "a ROLLED_BACK entry must be journaled",
            journal.entries().any { it.kind == EditJournal.EntryKind.ROLLED_BACK },
        )
    }

    @Test
    fun `rollback is refused when nothing was ever applied`() {
        val r = journal.rollback("NeverTouched.kt")
        assertTrue(r is EditJournal.ApplyResult.Refused)
        assertTrue((r as EditJournal.ApplyResult.Refused).reason.contains("No applied change"))
    }

    @Test
    fun `rollback is refused for a new file that has no backup`() {
        journal.applyApproved(proposal("Brand.kt", null, "new\n", isNew = true))
        val r = journal.rollback("Brand.kt")
        assertTrue("a new file has no original to roll back to", r is EditJournal.ApplyResult.Refused)
    }

    @Test
    fun `rollback refuses a traversal path`() {
        val r = journal.rollback("../../etc/passwd")
        assertTrue(r is EditJournal.ApplyResult.Refused)
    }

    // ------------------------------------------------------- refusal paths

    @Test
    fun `apply refuses a path that escapes the workspace`() {
        val outside = File(root.parentFile, "varun-escape-${System.nanoTime()}.txt")
        val r = journal.applyApproved(proposal("../../${outside.name}", null, "pwned\n", isNew = true))
        assertTrue("traversal must be refused, got $r", r is EditJournal.ApplyResult.Refused)
        assertFalse("nothing may be written outside the root", outside.exists())
    }

    @Test
    fun `apply refuses an absolute path`() {
        val r = journal.applyApproved(proposal("/tmp/varun-should-not-exist.txt", null, "x\n", isNew = true))
        assertTrue(r is EditJournal.ApplyResult.Refused)
        assertFalse(File("/tmp/varun-should-not-exist.txt").exists())
    }

    @Test
    fun `apply refuses to write through a directory`() {
        File(root, "adir").mkdirs()
        val r = journal.applyApproved(proposal("adir", null, "x\n"))
        assertTrue("a directory target must be refused, got $r", r is EditJournal.ApplyResult.Refused)
        assertTrue("the directory must survive", File(root, "adir").isDirectory)
    }

    @Test
    fun `apply refuses to write through a symbolic link`() {
        val outside = File(System.getProperty("java.io.tmpdir"), "varun-link-target-${System.nanoTime()}")
        outside.writeText("precious original\n")
        val link = File(root, "sneaky.kt")
        val made = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }.isSuccess
        if (!made) { outside.delete(); return }

        val r = journal.applyApproved(proposal("sneaky.kt", null, "overwritten\n"))
        assertTrue("symlink write must be refused, got $r", r is EditJournal.ApplyResult.Refused)
        assertEquals(
            "the real file outside the workspace must be untouched",
            "precious original\n",
            outside.readText(),
        )
        link.delete(); outside.delete()
    }

    @Test
    fun `apply leaves no temp file behind`() {
        journal.applyApproved(proposal("T.kt", null, "content\n", isNew = true))
        val leftovers = root.walkTopDown().filter { it.name.endsWith(".varun-tmp") }.toList()
        assertTrue("temp files must be cleaned up: $leftovers", leftovers.isEmpty())
    }

    // -------------------------------------------------- proposals & rejects

    @Test
    fun `recordProposal journals without touching the target file`() {
        journal.recordProposal(proposal("Untouched.kt", "before\n", "after\n"))
        assertFalse(
            "an unapproved proposal must NOT modify the file",
            File(root, "Untouched.kt").exists(),
        )
        val entries = journal.entries()
        assertEquals(1, entries.size)
        assertEquals(EditJournal.EntryKind.PROPOSED, entries[0].kind)
    }

    @Test
    fun `recordRejection journals the decision and leaves the file alone`() {
        File(root, "Keep.kt").writeText("do not change me\n")
        val e = journal.recordRejection(proposal("Keep.kt", "do not change me\n", "changed\n"), "user said no")
        assertEquals(EditJournal.EntryKind.REJECTED, e.kind)
        assertEquals("do not change me\n", File(root, "Keep.kt").readText())
        assertTrue(e.note.contains("user said no"))
    }

    @Test
    fun `entriesFor filters by relative path`() {
        journal.recordProposal(proposal("a/A.kt", null, "a\n", isNew = true))
        journal.recordProposal(proposal("b/B.kt", null, "b\n", isNew = true))
        journal.applyApproved(proposal("a/A.kt", null, "a\n", isNew = true))
        assertEquals(2, journal.entriesFor("a/A.kt").size)
        assertEquals(1, journal.entriesFor("b/B.kt").size)
        assertEquals(0, journal.entriesFor("nope.kt").size)
    }

    @Test
    fun `entry ids increase monotonically`() {
        journal.recordProposal(proposal("1.kt", null, "1\n", isNew = true))
        journal.recordProposal(proposal("2.kt", null, "2\n", isNew = true))
        journal.recordProposal(proposal("3.kt", null, "3\n", isNew = true))
        val ids = journal.entries().map { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.distinct().size, ids.size)
    }

    // ------------------------------------------------- journal durability

    @Test
    fun `journal survives a reload from disk`() {
        journal.applyApproved(proposal("P.kt", null, "persisted\n", isNew = true))
        // A brand-new instance reading the same root must see the same history.
        val reopened = EditJournal(root)
        val entries = reopened.entries()
        assertEquals(journal.entries().size, entries.size)
        assertNotNull(reopened.lastApplied("P.kt"))
        assertEquals("persisted\n", File(root, "P.kt").readText())
    }

    @Test
    fun `a corrupt trailing line is skipped rather than crashing`() {
        journal.recordProposal(proposal("ok.kt", null, "fine\n", isNew = true))
        File(root, EditJournal.JOURNAL_NAME).appendText("THIS IS NOT A VALID JOURNAL LINE\n")
        val entries = journal.entries()
        assertEquals("the valid entry must still be readable", 1, entries.size)
        assertEquals("ok.kt", entries[0].relativePath)
    }

    @Test
    fun `Entry encode and decode round-trip including awkward characters`() {
        val e = EditJournal.Entry(
            id = 42,
            kind = EditJournal.EntryKind.APPLIED,
            relativePath = "weird\u0001path/with spaces.kt",
            backupPath = ".varun-backups/20260101-000000-000-x.kt",
            note = "note with ${'\u0001'} separator and \\ backslash",
            timestamp = 1_700_000_000_000L,
            sha256 = "abc123",
            bytes = 999,
        )
        val decoded = EditJournal.Entry.decode(e.encode())
        assertNotNull(decoded)
        assertEquals(e, decoded)
    }

    @Test
    fun `decode returns null for malformed input instead of throwing`() {
        assertNull(EditJournal.Entry.decode(""))
        assertNull(EditJournal.Entry.decode("garbage"))
        assertNull(EditJournal.Entry.decode("1\u00012\u00013"))
    }

    // ------------------------------------------------------ backup cleanup

    @Test
    fun `clearBackupsOlderThan only removes genuinely old backups`() {
        File(root, "Old.kt").writeText("old\n")
        journal.applyApproved(proposal("Old.kt", "old\n", "new\n"))
        assertEquals(1, journal.listBackups().size)

        val hour = 60L * 60 * 1000
        // A backup written moments ago is not "older than an hour", so a
        // positive window must collect nothing. (Asserting on a 0 ms window
        // would be timing-dependent, so it is deliberately not tested.)
        assertEquals(0, journal.clearBackupsOlderThan(hour))
        assertEquals(1, journal.listBackups().size)

        // Age the backup explicitly; now it must be collected.
        assertTrue(
            "should be able to age the backup file",
            journal.listBackups()[0].setLastModified(System.currentTimeMillis() - 2 * hour),
        )
        assertEquals(1, journal.clearBackupsOlderThan(hour))
        assertEquals(0, journal.listBackups().size)
        assertEquals(0L, journal.backupDirSizeBytes())
    }

    @Test
    fun `proposal diff is null when there is no original text`() {
        val p = proposal("N.kt", null, "new file\n", isNew = true)
        assertNull(p.diff)
        val q = proposal("N.kt", "a\n", "b\n")
        assertNotNull(q.diff)
    }
}

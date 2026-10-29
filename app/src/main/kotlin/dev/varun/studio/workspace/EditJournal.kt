package dev.varun.studio.workspace

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transactional, journaled file editing.
 *
 * Rules this enforces, in order:
 *  1. Nothing is written until the user approves that specific change.
 *  2. Before any write, the original bytes are copied to a timestamped backup.
 *  3. Every write is appended to a change journal, so the history survives a
 *     crash and can be replayed or rolled back.
 *  4. All paths go through [PathGuard]; a traversal attempt is refused, not
 *     silently rewritten.
 *  5. Rollback restores from the backup and is itself journaled.
 *
 * The journal is an append-only line-based file (not SQLite) so that a
 * partially-written last line after a crash cannot corrupt earlier records.
 */
class EditJournal(private val workspaceRoot: File) {

    enum class EntryKind { PROPOSED, BACKUP, APPLIED, REJECTED, ROLLED_BACK }

    data class Entry(
        val id: Long,
        val kind: EntryKind,
        val relativePath: String,
        val backupPath: String?,
        val note: String,
        val timestamp: Long,
        val sha256: String?,
        val bytes: Long,
    ) {
        fun encode(): String = listOf(
            id.toString(),
            kind.name,
            enc(relativePath),
            backupPath?.let { enc(it) } ?: "-",
            enc(note),
            timestamp.toString(),
            sha256 ?: "-",
            bytes.toString(),
        ).joinToString(SEP)

        companion object {
            fun decode(line: String): Entry? {
                val parts = line.split(SEP)
                if (parts.size < 8) return null
                return try {
                    Entry(
                        id = parts[0].toLong(),
                        kind = EntryKind.valueOf(parts[1]),
                        relativePath = dec(parts[2]),
                        backupPath = parts[3].takeIf { it != "-" }?.let { dec(it) },
                        note = dec(parts[4]),
                        timestamp = parts[5].toLong(),
                        sha256 = parts[6].takeIf { it != "-" },
                        bytes = parts[7].toLong(),
                    )
                } catch (_: Throwable) {
                    // A truncated final line after a crash is skipped, not fatal.
                    null
                }
            }
        }
    }

    /** A pending change the user has not approved yet. */
    data class Proposal(
        val relativePath: String,
        val originalText: String?,
        val proposedText: String,
        val reason: String,
        val isNewFile: Boolean,
    ) {
        val diff: DiffEngine.DiffResult?
            get() = originalText?.let { DiffEngine.diff(it, proposedText) }
    }

    sealed interface ApplyResult {
        data class Applied(val entry: Entry, val backupFile: File?, val diff: DiffEngine.DiffResult?) : ApplyResult
        data class Refused(val reason: String) : ApplyResult
    }

    private val journalFile = File(workspaceRoot, JOURNAL_NAME)
    private val backupDir = File(workspaceRoot, BACKUP_DIR_NAME)
    private val timeFormat = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)

    init {
        workspaceRoot.mkdirs()
        backupDir.mkdirs()
    }

    /** Read the whole journal, oldest first. Tolerates a corrupt tail line. */
    fun entries(): List<Entry> {
        if (!journalFile.exists()) return emptyList()
        return journalFile.readLines().mapNotNull { line ->
            if (line.isBlank()) null else Entry.decode(line)
        }
    }

    fun entriesFor(relativePath: String): List<Entry> =
        entries().filter { it.relativePath == relativePath }

    /** Most recent APPLIED entry for a path, which is what rollback targets. */
    fun lastApplied(relativePath: String): Entry? =
        entriesFor(relativePath).lastOrNull { it.kind == EntryKind.APPLIED }

    private fun append(entry: Entry) {
        journalFile.appendText(entry.encode() + "\n")
    }

    private fun nextId(): Long = (entries().maxOfOrNull { it.id } ?: 0L) + 1L

    /**
     * Record that a change has been PROPOSED. This never touches the target
     * file — it only writes to the journal, so an unapproved proposal cannot
     * alter the repository.
     */
    fun recordProposal(proposal: Proposal): Entry {
        val decision = PathGuard.resolve(workspaceRoot, proposal.relativePath)
        val relative = when (decision) {
            is PathGuard.Decision.Allowed -> decision.relativePath
            is PathGuard.Decision.Denied ->
                throw SecurityException("Refusing to propose an unsafe path: ${decision.detail}")
        }
        val bytes = proposal.proposedText.toByteArray(Charsets.UTF_8).size.toLong()
        val entry = Entry(
            id = nextId(),
            kind = EntryKind.PROPOSED,
            relativePath = relative,
            backupPath = null,
            note = proposal.reason,
            timestamp = System.currentTimeMillis(),
            sha256 = Digests.sha256Hex(proposal.proposedText.toByteArray(Charsets.UTF_8)),
            bytes = bytes,
        )
        append(entry)
        return entry
    }

    /**
     * Apply an APPROVED change:
     *  - validate the path,
     *  - copy the current bytes to backups/,
     *  - write the new bytes atomically (temp file + rename),
     *  - journal both steps.
     *
     * Atomic write matters on a phone: a rename is far less likely to leave a
     * half-written source file than an in-place write during a battery cut.
     */
    fun applyApproved(proposal: Proposal): ApplyResult {
        val decision = PathGuard.resolve(workspaceRoot, proposal.relativePath)
        val target = when (decision) {
            is PathGuard.Decision.Allowed -> decision.file
            is PathGuard.Decision.Denied -> return ApplyResult.Refused(decision.detail)
        }
        // Never follow a symlinked target out of the workspace.
        if (target.exists() && java.nio.file.Files.isSymbolicLink(target.toPath())) {
            return ApplyResult.Refused("Refusing to write through a symbolic link: ${proposal.relativePath}")
        }
        if (target.isDirectory) {
            return ApplyResult.Refused("Target is a directory: ${proposal.relativePath}")
        }

        val originalText: String? = if (target.exists()) {
            runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
        } else null

        // 1) Backup
        var backupFile: File? = null
        if (target.exists()) {
            val stamp = timeFormat.format(Date())
            val safeName = PathGuard.sanitizeFileName(target.name, "file")
            val backup = File(backupDir, "$stamp-$safeName")
            val copied = runCatching { target.copyTo(backup, overwrite = true) }.isSuccess
            if (!copied) {
                return ApplyResult.Refused("Could not create a backup; aborting without writing.")
            }
            backupFile = backup
            append(
                Entry(
                    id = nextId(),
                    kind = EntryKind.BACKUP,
                    relativePath = decision.relativePath,
                    backupPath = PathGuard.relativize(workspaceRoot, backup),
                    note = "Backup before edit",
                    timestamp = System.currentTimeMillis(),
                    sha256 = Digests.sha256Hex(target.readBytes()),
                    bytes = backup.length(),
                ),
            )
        }

        // 2) Atomic write
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.varun-tmp")
        val written = runCatching {
            tmp.writeText(proposal.proposedText, Charsets.UTF_8)
            // Best-effort durability; ignore failures on filesystems that
            // reject fsync on directories.
            runCatching { java.io.FileOutputStream(tmp, true).use { it.fd.sync() } }
            if (!tmp.renameTo(target)) {
                // Rename can fail across mount points; fall back to a copy.
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        if (written.isFailure) {
            tmp.delete()
            return ApplyResult.Refused("Write failed: ${written.exceptionOrNull()?.message}")
        }

        // 3) Journal the application
        val applied = Entry(
            id = nextId(),
            kind = EntryKind.APPLIED,
            relativePath = decision.relativePath,
            backupPath = backupFile?.let { PathGuard.relativize(workspaceRoot, it) },
            note = proposal.reason,
            timestamp = System.currentTimeMillis(),
            sha256 = Digests.sha256Hex(proposal.proposedText.toByteArray(Charsets.UTF_8)),
            bytes = target.length(),
        )
        append(applied)
        val result = originalText?.let { DiffEngine.diff(it, proposal.proposedText) }
        return ApplyResult.Applied(applied, backupFile, result)
    }

    /**
     * Reject a proposal. The target file is never opened for writing, so the
     * original bytes are provably untouched; we still journal the decision.
     */
    fun recordRejection(proposal: Proposal, reason: String = "Rejected by user"): Entry {
        val decision = PathGuard.resolve(workspaceRoot, proposal.relativePath)
        // A Denied decision deliberately carries no resolved path - refusing to
        // produce one is the entire point of the guard. Journal the path that
        // was asked for instead: it is stored as inert text in the audit trail
        // and is never used to open a file.
        val relative = when (decision) {
            is PathGuard.Decision.Allowed -> decision.relativePath
            is PathGuard.Decision.Denied -> proposal.relativePath
        }
        val noteText = when (decision) {
            is PathGuard.Decision.Denied ->
                "$reason - refused by PathGuard (${decision.reason}: ${decision.detail})"
            is PathGuard.Decision.Allowed -> reason
        }
        val entry = Entry(
            id = nextId(),
            kind = EntryKind.REJECTED,
            relativePath = relative,
            backupPath = null,
            note = noteText,
            timestamp = System.currentTimeMillis(),
            sha256 = null,
            bytes = 0,
        )
        append(entry)
        return entry
    }

    /**
     * Roll a path back to the backup recorded by its most recent APPLIED entry.
     * Refuses if no backup exists rather than guessing.
     */
    fun rollback(relativePath: String): ApplyResult {
        val applied = lastApplied(relativePath)
            ?: return ApplyResult.Refused("No applied change recorded for $relativePath")
        val backupRelative = applied.backupPath
            ?: return ApplyResult.Refused("No backup was recorded for $relativePath (it may have been a new file)")

        val backupDecision = PathGuard.resolve(workspaceRoot, backupRelative)
        val backupFile = when (backupDecision) {
            is PathGuard.Decision.Allowed -> backupFile0(backupDecision)
            is PathGuard.Decision.Denied -> return ApplyResult.Refused(backupDecision.detail)
        } ?: return ApplyResult.Refused("Backup file is missing: $backupRelative")

        val targetDecision = PathGuard.resolve(workspaceRoot, relativePath)
        val target = when (targetDecision) {
            is PathGuard.Decision.Allowed -> targetDecision.file
            is PathGuard.Decision.Denied -> return ApplyResult.Refused(targetDecision.detail)
        }

        val restored = runCatching {
            target.parentFile?.mkdirs()
            backupFile.copyTo(target, overwrite = true)
        }
        if (restored.isFailure) {
            return ApplyResult.Refused("Rollback failed: ${restored.exceptionOrNull()?.message}")
        }
        append(
            Entry(
                id = nextId(),
                kind = EntryKind.ROLLED_BACK,
                relativePath = targetDecision.relativePath,
                backupPath = backupRelative,
                note = "Restored from backup",
                timestamp = System.currentTimeMillis(),
                sha256 = Digests.sha256Hex(target.readBytes()),
                bytes = target.length(),
            ),
        )
        return ApplyResult.Applied(
            entry = entries().last(),
            backupFile = backupFile,
            diff = null,
        )
    }

    private fun backupFile0(decision: PathGuard.Decision.Allowed): File? =
        decision.file.takeIf { it.exists() && it.isFile }

    /** Delete a file created by an approved change, if the journal says it was new. */
    fun listBackups(): List<File> =
        backupDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun backupDirSizeBytes(): Long = listBackups().sumOf { it.length() }

    fun clearBackupsOlderThan(millis: Long): Int {
        val cutoff = System.currentTimeMillis() - millis
        var n = 0
        for (f in listBackups()) if (f.lastModified() < cutoff && f.delete()) n++
        return n
    }

    companion object {
        const val JOURNAL_NAME = ".varun-journal.log"
        const val BACKUP_DIR_NAME = ".varun-backups"
        private const val SEP = "\u0001"

        private fun enc(s: String): String = s.replace("\\", "\\\\").replace(SEP, "\\u0001").replace("\n", "\\n")
        private fun dec(s: String): String = s.replace("\\n", "\n").replace("\\u0001", SEP).replace("\\\\", "\\")
    }
}

/**
 * Small digest/encoding helper so we do not pull in a crypto library.
 *
 * Uses java.util.Base64 (available since API 26, our minSdk) rather than
 * android.util.Base64 so these functions also run on a plain JVM in CI tests.
 */
internal object Digests {
    private val HEX = "0123456789abcdef".toCharArray()

    fun sha256Hex(bytes: ByteArray): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    fun sha256Base64(bytes: ByteArray): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            java.util.Base64.getEncoder().encodeToString(md.digest(bytes))
        } catch (_: Throwable) {
            ""
        }
    }

    fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)
}

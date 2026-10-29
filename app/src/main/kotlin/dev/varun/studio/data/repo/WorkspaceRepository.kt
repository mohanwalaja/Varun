package dev.varun.studio.data.repo

import android.content.Context
import android.net.Uri
import dev.varun.studio.core.AppLog
import dev.varun.studio.data.db.JournalRecord
import dev.varun.studio.data.db.VarunDatabase
import dev.varun.studio.github.GitHubClient
import dev.varun.studio.workspace.ArchiveExtractor
import dev.varun.studio.workspace.CodeSearch
import dev.varun.studio.workspace.DiffEngine
import dev.varun.studio.workspace.EditJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The local repository workspace: import, browse, read, edit, search, diff,
 * journal and roll back — all inside app-private storage.
 *
 * A workspace is a directory under filesDir/workspaces/<slug>. There is no
 * local .git object store: git history is read from GitHub, and writes are
 * constructed through the Git Data API (see GitHubRepository.commitAndPush).
 * This is deliberate — see docs/LIMITATIONS.md.
 */
class WorkspaceRepository(
    private val context: Context,
    private val db: VarunDatabase,
    private val settings: SettingsRepository,
    private val gitHub: GitHubClient,
) {

    data class FileNode(
        val relativePath: String,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val children: List<FileNode> = emptyList(),
    )

    data class Workspace(
        val name: String,
        val root: File,
        val origin: String?,
        val fileCount: Int,
        val totalBytes: Long,
    )

    sealed interface ImportResult {
        data class Success(val workspace: Workspace, val filesWritten: Int, val skipped: Int) : ImportResult
        data class Failure(val message: String) : ImportResult
    }

    private val root: File = File(context.filesDir, "workspaces").apply { mkdirs() }

    private val _current = MutableStateFlow<Workspace?>(null)
    val current: StateFlow<Workspace?> = _current.asStateFlow()

    private val _nodes = MutableStateFlow<List<FileNode>>(emptyList())
    val nodes: StateFlow<List<FileNode>> = _nodes.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private var journal: EditJournal? = null

    fun workspacesRoot(): File = root

    fun journalFor(ws: Workspace): EditJournal =
        journal?.takeIf { it === journalForCache?.second && journalForCache?.first == ws.root } ?: run {
            val j = EditJournal(ws.root)
            journalForCache = ws.root to j
            journal = j
            return j
        }

    private var journalForCache: Pair<File, EditJournal>? = null

    suspend fun listWorkspaces(): List<Workspace> = withContext(Dispatchers.IO) {
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching { describe(dir) }.getOrNull()
        }?.sortedByDescending { it.root.lastModified() } ?: emptyList()
    }

    private fun describe(dir: File): Workspace {
        var files = 0
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeFirst()
            for (c in d.listFiles() ?: emptyArray()) {
                if (c.isDirectory) {
                    if (!CodeSearch.shouldSkipDir(c.name, CodeSearch.Options())) stack.addLast(c)
                } else { files++; bytes += c.length() }
            }
        }
        val meta = File(dir, ORIGIN_FILE)
        val origin = if (meta.exists()) runCatching { meta.readText().trim() }.getOrNull() else null
        return Workspace(dir.name, dir, origin, files, bytes)
    }

    suspend fun open(name: String): Workspace? = withContext(Dispatchers.IO) {
        val dir = File(root, name)
        if (!dir.isDirectory) return@withContext null
        val ws = describe(dir)
        _current.value = ws
        journalForCache = ws.root to EditJournal(ws.root)
        journal = journalForCache?.second
        settings.update { it.copy(lastWorkspacePath = ws.root.absolutePath) }
        loadNodes("")
        ws
    }

    /** Browse one directory level. Relative paths are always '/'-separated. */
    suspend fun loadNodes(relativeDir: String): List<FileNode> = withContext(Dispatchers.IO) {
        val ws = _current.value ?: return@withContext emptyList()
        val dir = if (relativeDir.isBlank()) ws.root
        else dev.varun.studio.workspace.PathGuard.requireSafe(ws.root, relativeDir)
        if (!dir.isDirectory) return@withContext emptyList()
        val children = dir.listFiles()?.map { f ->
            val rel = dev.varun.studio.workspace.PathGuard.relativize(ws.root, f) ?: f.name
            FileNode(
                relativePath = rel,
                name = f.name,
                isDirectory = f.isDirectory,
                sizeBytes = if (f.isFile) f.length() else 0L,
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        _nodes.value = children
        children
    }

    /** Read a text file for the editor. Enforces the configured size limit. */
    suspend fun readText(relativePath: String): Result<String> = withContext(Dispatchers.IO) {
        val ws = _current.value ?: return@withContext Result.failure(IllegalStateException("No workspace is open."))
        val decision = dev.varun.studio.workspace.PathGuard.resolve(ws.root, relativePath)
        val file = when (decision) {
            is dev.varun.studio.workspace.PathGuard.Decision.Allowed -> decision.file
            is dev.varun.studio.workspace.PathGuard.Decision.Denied ->
                return@withContext Result.failure(SecurityException(decision.detail))
        }
        if (!file.exists()) return@withContext Result.failure(IllegalStateException("File does not exist."))
        if (file.isDirectory) return@withContext Result.failure(IllegalStateException("That path is a directory."))
        val limit = settings.settings.value.maxFileKb * 1024L
        if (file.length() > EDITOR_MAX_BYTES) {
            return@withContext Result.failure(
                IllegalStateException(
                    "File is ${file.length() / 1024} KB, above the ${EDITOR_MAX_BYTES / 1024} KB editor limit. " +
                        "Editing a file that large on a phone is unreliable.",
                ),
            )
        }
        if (!CodeSearch.isProbablyText(file)) {
            return@withContext Result.failure(IllegalStateException("That looks like a binary file."))
        }
        runCatching { file.readText(Charsets.UTF_8) }
            .recoverCatching {
                throw IllegalStateException("Could not read this file as UTF-8 text.", it)
            }
            .also { if (limit > 0 && file.length() > limit) AppLog.d("Workspace: ${file.name} exceeds the AI context limit") }
    }

    /**
     * Save an edited file. Goes through the journal so a backup exists and the
     * change is recorded even when the user edited by hand rather than via AI.
     */
    suspend fun writeText(relativePath: String, newText: String, note: String = "Manual edit"): Result<File> =
        withContext(Dispatchers.IO) {
            val ws = _current.value ?: return@withContext Result.failure(IllegalStateException("No workspace is open."))
            val j = journalFor(ws)
            val proposal = EditJournal.Proposal(
                relativePath = relativePath,
                originalText = null,
                proposedText = newText,
                reason = note,
                isNewFile = false,
            )
            when (val r = j.applyApproved(proposal)) {
                is EditJournal.ApplyResult.Applied -> {
                    recordJournal(ws, r.entry)
                    Result.success(dev.varun.studio.workspace.PathGuard.requireSafe(ws.root, relativePath))
                }
                is EditJournal.ApplyResult.Refused -> Result.failure(SecurityException(r.reason))
            }
        }

    private fun recordJournal(ws: Workspace, e: EditJournal.Entry) {
        runCatching {
            db.insertJournal(
                JournalRecord(
                    workspacePath = ws.root.absolutePath,
                    relativePath = e.relativePath,
                    kind = e.kind.name,
                    note = e.note,
                    backupPath = e.backupPath,
                    sha256 = e.sha256,
                    bytes = e.bytes,
                    createdAt = e.timestamp,
                ),
            )
        }
    }

    /** Diff the working copy against the original text the caller holds. */
    fun diff(original: String, proposed: String): DiffEngine.DiffResult = DiffEngine.diff(original, proposed)

    /** Local full-text search across the open workspace. */
    suspend fun search(query: String, options: CodeSearch.Options = CodeSearch.Options()): CodeSearch.Result =
        withContext(Dispatchers.Default) {
            val ws = _current.value ?: return@withContext CodeSearch.Result(emptyList(), 0, 0, false, 0)
            val limited = options.copy(maxFileBytes = settings.settings.value.maxFileKb * 1024L)
            CodeSearch.search(ws.root, query, limited)
        }

    /** Every changed file relative to the imported snapshot, for review + commit. */
    suspend fun changedFiles(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val ws = _current.value ?: return@withContext emptyList()
        val j = journalFor(ws)
        val out = ArrayList<Pair<String, String>>()
        for (entry in j.entries().filter { it.kind == EditJournal.EntryKind.APPLIED }) {
            val decision = dev.varun.studio.workspace.PathGuard.resolve(ws.root, entry.relativePath)
            if (decision is dev.varun.studio.workspace.PathGuard.Decision.Allowed && decision.file.exists()) {
                val text = runCatching { decision.file.readText() }.getOrNull() ?: continue
                out.add(entry.relativePath to text)
            }
        }
        // De-duplicate: keep the most recent version of each path.
        out.groupBy { it.first }.map { (path, versions) -> path to versions.last().second }
    }

    /** Roll one file back to its pre-edit backup. */
    suspend fun rollback(relativePath: String): String = withContext(Dispatchers.IO) {
        val ws = _current.value ?: return@withContext "No workspace is open."
        val j = journalFor(ws)
        when (val r = j.rollback(relativePath)) {
            is EditJournal.ApplyResult.Applied -> {
                recordJournal(ws, r.entry)
                "Restored $relativePath from backup."
            }
            is EditJournal.ApplyResult.Refused -> "Rollback refused: ${r.reason}"
        }
    }

    fun journalEntries(): List<EditJournal.Entry> {
        val ws = _current.value ?: return emptyList()
        return journalFor(ws).entries()
    }

    // ------------------------------------------------------------- importing

    /** Import a .zip/.tar.gz the user picked from storage. */
    suspend fun importArchive(uri: Uri, name: String?): ImportResult = withContext(Dispatchers.IO) {
        try {
            val slug = slugify(name ?: "import")
            val dir = File(root, slug)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            val staging = File(context.cacheDir, "import-${System.currentTimeMillis()}")
            val copied = copyToStaging(uri, staging)
            if (!copied) {
                dir.deleteRecursively()
                return@withContext ImportResult.Failure("Could not read that file.")
            }
            _busy.value = "Extracting…"
            val maxMb = settings.settings.value.maxWorkspaceImportMb
            val result = ArchiveExtractor.extract(
                archive = staging,
                destination = dir,
                options = ArchiveExtractor.Options(
                    maxTotalBytes = maxMb.toLong() * 1024 * 1024,
                ),
            ) { done, entry ->
                if (done % 25 == 0) _busy.value = "Extracting $done files… ${entry.substringAfterLast('/')}"
            }
            staging.delete()
            when (result) {
                is ArchiveExtractor.ExtractResult.Failure -> {
                    dir.deleteRecursively()
                    ImportResult.Failure(result.message)
                }
                is ArchiveExtractor.ExtractResult.Success -> {
                    // Flatten GitHub's single top-level directory.
                    val flattenInto = result.rootPrefix?.let { File(dir, it) }
                    if (flattenInto != null && flattenInto.isDirectory) {
                        flattenInto.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
                        flattenInto.delete()
                    }
                    File(dir, ORIGIN_FILE).writeText("local-archive:${name ?: slug}")
                    val ws = describe(dir)
                    _current.value = ws
                    journalForCache = ws.root to EditJournal(ws.root)
                    journal = journalForCache?.second
                    loadNodes("")
                    ImportResult.Success(ws, result.filesWritten, result.skipped)
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.e("Archive import failed", t)
            ImportResult.Failure(AppLog.describeError(t))
        } finally {
            _busy.value = null
        }
    }

    /**
     * Import a repository from GitHub by downloading its tarball.
     *
     * Targeted retrieval alternative: for a large repository, use
     * [importPaths] instead and fetch only the files you need.
     */
    suspend fun importFromGitHub(owner: String, repo: String, ref: String): ImportResult =
        withContext(Dispatchers.IO) {
            try {
                _busy.value = "Contacting GitHub…"
                val maxMb = settings.settings.value.maxWorkspaceImportMb
                val maxBytes = maxMb.toLong() * 1024 * 1024
                val result = gitHub.downloadArchive(owner, repo, ref, maxBytes) { total ->
                    _busy.value = "Downloading ${total / (1024 * 1024)} MB…"
                    true
                }
                val bytes = when (result) {
                    is GitHubClient.ApiResult.Ok -> result.value
                    is GitHubClient.ApiResult.Err -> return@withContext ImportResult.Failure(result.error.message)
                }
                val slug = slugify("$owner-$repo")
                val dir = File(root, slug)
                if (dir.exists()) dir.deleteRecursively()
                dir.mkdirs()
                val archive = File(context.cacheDir, "$slug-${System.currentTimeMillis()}.tar.gz")
                archive.writeBytes(bytes)
                _busy.value = "Extracting…"
                val extracted = ArchiveExtractor.extract(archive, dir, ArchiveExtractor.Options(maxTotalBytes = maxBytes))
                archive.delete()
                when (extracted) {
                    is ArchiveExtractor.ExtractResult.Failure -> {
                        dir.deleteRecursively()
                        ImportResult.Failure(extracted.message)
                    }
                    is ArchiveExtractor.ExtractResult.Success -> {
                        extracted.rootPrefix?.let { prefix ->
                            val inner = File(dir, prefix)
                            if (inner.isDirectory) {
                                inner.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
                                inner.delete()
                            }
                        }
                        File(dir, ORIGIN_FILE).writeText("github:$owner/$repo@$ref")
                        val ws = describe(dir)
                        _current.value = ws
                        journalForCache = ws.root to EditJournal(ws.root)
                        journal = journalForCache?.second
                        loadNodes("")
                        ImportResult.Success(ws, extracted.filesWritten, extracted.skipped)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                ImportResult.Failure(AppLog.describeError(t))
            } finally {
                _busy.value = null
            }
        }

    /**
     * Fetch ONLY the listed paths into a workspace. This is the large-repository
     * strategy: no clone, no full history, just the files the user chose.
     */
    suspend fun importPaths(
        owner: String,
        repo: String,
        ref: String,
        paths: List<String>,
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            _busy.value = "Fetching ${paths.size} file(s)…"
            val slug = slugify("$owner-$repo-partial")
            val dir = File(root, slug)
            dir.mkdirs()
            var written = 0
            var skipped = 0
            for ((i, path) in paths.withIndex()) {
                _busy.value = "Fetching ${i + 1}/${paths.size}: ${path.substringAfterLast('/')}"
                val decision = dev.varun.studio.workspace.PathGuard.resolve(dir, path)
                val target = when (decision) {
                    is dev.varun.studio.workspace.PathGuard.Decision.Allowed -> decision.file
                    is dev.varun.studio.workspace.PathGuard.Decision.Denied -> { skipped++; continue }
                }
                when (val r = gitHub.fileContent(owner, repo, path, ref)) {
                    is GitHubClient.ApiResult.Ok -> {
                        val text = r.value.text
                        if (text == null) { skipped++; continue }
                        target.parentFile?.mkdirs()
                        target.writeText(text, Charsets.UTF_8)
                        written++
                    }
                    is GitHubClient.ApiResult.Err -> skipped++
                }
            }
            File(dir, ORIGIN_FILE).writeText("github-partial:$owner/$repo@$ref (${paths.size} requested)")
            val ws = describe(dir)
            _current.value = ws
            journalForCache = ws.root to EditJournal(ws.root)
            journal = journalForCache?.second
            loadNodes("")
            ImportResult.Success(ws, written, skipped)
        } catch (c: CancellationException) {
            throw c
        } finally {
            _busy.value = null
        }
    }

    suspend fun deleteWorkspace(name: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(root, name)
        if (!dir.isDirectory) return@withContext false
        val ok = dir.deleteRecursively()
        if (_current.value?.name == name) {
            _current.value = null
            _nodes.value = emptyList()
            journal = null
            journalForCache = null
        }
        ok
    }

    private fun copyToStaging(uri: Uri, target: File): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out ->
                val isGz = runCatching {
                    GZIPInputStream(input).use { it.read() >= 0 }
                }.isSuccess
                // Re-open: the probe consumed the stream.
                input.close()
                context.contentResolver.openInputStream(uri)!!.use { fresh ->
                    fresh.copyTo(out)
                }
                if (isGz && target.name.endsWith(".gz")) Unit
            }
        } ?: return@runCatching false
        target.length() > 0
    }.getOrDefault(false)

    private fun slugify(name: String): String {
        val base = name.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.')
            .take(60)
        return base.ifBlank { "workspace" }
    }

    companion object {
        const val ORIGIN_FILE = ".varun-origin"
        const val EDITOR_MAX_BYTES = 2L * 1024 * 1024
    }
}

package dev.varun.studio.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.varun.studio.ai.LlamaEngine
import dev.varun.studio.ai.MemoryEstimator
import dev.varun.studio.ai.ModelCatalog
import dev.varun.studio.ai.ModelLoadConfig
import dev.varun.studio.core.AppLog
import dev.varun.studio.data.db.ModelRecord
import dev.varun.studio.data.db.VarunDatabase
import dev.varun.studio.workspace.Digests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Model management: import from phone storage via the Storage Access Framework,
 * download from the catalogue with explicit consent, load, unload, delete.
 *
 * Consent rule enforced here, not just in the UI: [download] refuses to start
 * unless the caller passes `userConsented = true`, and no code path calls it
 * automatically at startup.
 */
class ModelRepository(
    private val context: Context,
    private val db: VarunDatabase,
    private val engine: LlamaEngine,
    private val settings: SettingsRepository,
) {

    sealed interface LoadUiState {
        data object Idle : LoadUiState
        data class Progress(val fraction: Float, val message: String) : LoadUiState
        data class Loaded(val path: String, val estimate: MemoryEstimator.Estimate?) : LoadUiState
        data class Failed(val kind: String, val message: String, val advice: String) : LoadUiState
    }

    data class DownloadState(
        val entryId: String?,
        val fileName: String?,
        val bytesDone: Long,
        val bytesTotal: Long,
        val active: Boolean,
        val error: String?,
    ) {
        val fraction: Float
            get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
    }

    private val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    private val _models = MutableStateFlow<List<ModelRecord>>(emptyList())
    val models: StateFlow<List<ModelRecord>> = _models.asStateFlow()

    private val _loadState = MutableStateFlow<LoadUiState>(LoadUiState.Idle)
    val loadState: StateFlow<LoadUiState> = _loadState.asStateFlow()

    private val _downloadState = MutableStateFlow(DownloadState(null, null, 0, 0, false, null))
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _loadedModelId = MutableStateFlow<Long?>(null)
    val loadedModelId: StateFlow<Long?> = _loadedModelId.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)   // large downloads: no overall cap
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun modelsDir(): File = modelsDir

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val rows = db.models().filter { File(it.absolutePath).exists() }
        // Drop records whose file the user deleted outside the app.
        val known = db.models()
        for (row in known) {
            if (!File(row.absolutePath).exists()) {
                db.deleteModel(row.id)
                AppLog.i("ModelRepository: pruned missing model record ${row.fileName}")
            }
        }
        _models.value = rows
    }

    /**
     * Import a GGUF the user picked with ACTION_OPEN_DOCUMENT.
     *
     * We copy into app-private storage rather than keeping the SAF Uri, because
     * a persisted Uri can be revoked by the user or the provider at any time and
     * llama.cpp needs a real filesystem path it can mmap.
     */
    suspend fun importFromUri(uri: Uri): Result<ModelRecord> = withContext(Dispatchers.IO) {
        try {
            val (displayName, size) = queryDisplayName(uri)
            if (!displayName.lowercase().endsWith(".gguf")) {
                return@withContext Result.failure(
                    IllegalArgumentException("That file is not a .gguf model ($displayName)."),
                )
            }
            val free = context.filesDir.usableSpace
            if (size > 0 && free < size + MIN_FREE_AFTER_IMPORT) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Not enough free storage. You need about " +
                            "${MemoryEstimator.humanBytes(size + MIN_FREE_AFTER_IMPORT)} and only " +
                            "${MemoryEstimator.humanBytes(free)} is available.",
                    ),
                )
            }
            val target = uniqueTargetFile(displayName)
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                        _loadState.value = LoadUiState.Progress(
                            if (size > 0) copied.toFloat() / size else 0f,
                            "Importing ${MemoryEstimator.humanBytes(copied)}" +
                                if (size > 0) " of ${MemoryEstimator.humanBytes(size)}" else "",
                        )
                    }
                    runCatching { out.fd.sync() }
                }
            } ?: return@withContext Result.failure(IllegalStateException("Could not open that file."))

            val actualSize = target.length()
            val record = ModelRecord(
                displayName = displayName.removeSuffix(".gguf").removeSuffix(".GGUF"),
                fileName = target.name,
                absolutePath = target.absolutePath,
                sizeBytes = actualSize,
                quantTag = MemoryEstimator.detectQuant(displayName),
                format = "GGUF",
                sourceUri = uri.toString(),
                origin = "import",
                sha256 = null, // skipped on purpose: hashing 1-2 GB on a phone is slow
                addedAt = System.currentTimeMillis(),
                lastLoadedAt = null,
                notes = null,
            )
            val id = db.upsertModel(record)
            refresh()
            _loadState.value = LoadUiState.Idle
            Result.success(record.copy(id = id))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.e("Model import failed", t)
            _loadState.value = LoadUiState.Idle
            Result.failure(t)
        }
    }

    /**
     * Download a catalogue entry. Requires explicit consent and can be cancelled
     * by cancelling the enclosing coroutine. Writes to a `.part` file so an
     * interrupted download never looks like a complete model.
     */
    suspend fun download(entry: ModelCatalog.Entry, userConsented: Boolean): Result<ModelRecord> =
        withContext(Dispatchers.IO) {
            if (!userConsented) {
                return@withContext Result.failure(
                    IllegalStateException("Model downloads require explicit user consent."),
                )
            }
            val urlCheck = ModelCatalog.checkUrl(entry.url)
            if (urlCheck is ModelCatalog.UrlCheck.Bad) {
                return@withContext Result.failure(IllegalStateException(urlCheck.reason))
            }
            val free = context.filesDir.usableSpace
            if (free < entry.sizeBytes + MIN_FREE_AFTER_IMPORT) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Not enough free storage for ${entry.displayName}. " +
                            "Need ~${MemoryEstimator.humanBytes(entry.sizeBytes + MIN_FREE_AFTER_IMPORT)}, " +
                            "have ${MemoryEstimator.humanBytes(free)}.",
                    ),
                )
            }

            val target = uniqueTargetFile(entry.fileName)
            val part = File(target.parentFile, target.name + ".part")
            _downloadState.value = DownloadState(entry.id, entry.fileName, 0, entry.sizeBytes, true, null)

            try {
                val request = Request.Builder().url(entry.url)
                    .header("User-Agent", "VarunStudio-Android/1.0")
                    .build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed with HTTP ${response.code}.")
                    }
                    val total = response.header("Content-Length")?.toLongOrNull()
                        ?.takeIf { it > 0 } ?: entry.sizeBytes
                    val body = response.body ?: throw IllegalStateException("Empty response body.")
                    var done = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { out ->
                            val buf = ByteArray(512 * 1024)
                            while (true) {
                                // Cooperative cancellation: the coroutine job is
                                // checked between chunks so Stop works promptly.
                                kotlinx.coroutines.ensureActive()
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done > total * 1.5 && total > 0) {
                                    throw IllegalStateException("Download exceeded the expected size; aborting.")
                                }
                                _downloadState.value = DownloadState(entry.id, entry.fileName, done, total, true, null)
                            }
                            runCatching { out.fd.sync() }
                        }
                    }
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                val record = ModelRecord(
                    displayName = entry.displayName,
                    fileName = target.name,
                    absolutePath = target.absolutePath,
                    sizeBytes = target.length(),
                    quantTag = entry.quant,
                    format = "GGUF",
                    sourceUri = entry.url,
                    origin = "catalog:${entry.id}",
                    sha256 = null,
                    addedAt = System.currentTimeMillis(),
                    lastLoadedAt = null,
                    notes = entry.notes,
                )
                val id = db.upsertModel(record)
                refresh()
                _downloadState.value = DownloadState(entry.id, entry.fileName, target.length(), target.length(), false, null)
                Result.success(record.copy(id = id))
            } catch (c: CancellationException) {
                part.delete()
                _downloadState.value = DownloadState(entry.id, entry.fileName, 0, 0, false, "Cancelled")
                throw c
            } catch (t: Throwable) {
                part.delete()
                val msg = AppLog.describeError(t)
                AppLog.e("Model download failed", t)
                _downloadState.value = DownloadState(entry.id, entry.fileName, 0, 0, false, msg)
                Result.failure(t)
            }
        }

    /** Load a model into RAM. Never throws; failures become [LoadUiState.Failed]. */
    suspend fun load(id: Long): Boolean = withContext(Dispatchers.IO) {
        val record = db.modelById(id)
        if (record == null) {
            _loadState.value = LoadUiState.Failed("missing", "That model is no longer registered.", "Refresh the list.")
            return@withContext false
        }
        val file = File(record.absolutePath)
        if (!file.exists() || !file.isFile) {
            db.deleteModel(id)
            refresh()
            _loadState.value = LoadUiState.Failed("missing", "The GGUF file is gone.", "Import it again.")
            return@withContext false
        }
        val s = settings.settings.value
        _loadState.value = LoadUiState.Progress(0f, "Loading ${record.displayName}…")

        val result = engine.load(
            ModelLoadConfig(
                modelPath = record.absolutePath,
                nCtx = s.contextLength,
                nBatch = s.batch,
                nThreads = s.threads,
                nThreadsBatch = s.threads,
                useMmap = s.useMmap,
            ),
        ) { p ->
            _loadState.value = LoadUiState.Progress(0.1f + p * 0.8f, "Loading model… ${(p * 100).toInt()}%")
        }

        when (result) {
            is dev.varun.studio.ai.EngineLoadResult.Success -> {
                db.markModelLoaded(id, System.currentTimeMillis())
                _loadedModelId.value = id
                settings.update { it.copy(lastModelId = id) }
                val est = MemoryEstimator.estimateFromModel(
                    modelBytes = result.stats.modelBytes,
                    nLayer = result.stats.nLayer,
                    nHeadKv = (result.stats.nEmbd / 64).coerceAtLeast(1),
                    nEmbd = result.stats.nEmbd,
                    nCtx = result.stats.nCtx,
                    nBatch = s.batch,
                    nThreads = s.threads,
                    availableBytes = Runtime.getRuntime().maxMemory() * 3,
                )
                _loadState.value = LoadUiState.Loaded(record.absolutePath, est)
                true
            }
            is dev.varun.studio.ai.EngineLoadResult.Failure -> {
                _loadedModelId.value = null
                _loadState.value = LoadUiState.Failed(
                    result.kind.name,
                    result.message,
                    adviceFor(result.kind, record),
                )
                false
            }
        }
    }

    private fun adviceFor(kind: dev.varun.studio.ai.EngineFailureKind, record: ModelRecord): String =
        when (kind) {
            dev.varun.studio.ai.EngineFailureKind.OUT_OF_MEMORY,
            dev.varun.studio.ai.EngineFailureKind.CONTEXT_ALLOCATION ->
                "Try a smaller quantization (Q4_K_S or Q3_K_M), lower the context length in Settings, " +
                    "close other apps, then retry. ${record.displayName} is " +
                    "${MemoryEstimator.humanBytes(record.sizeBytes)} on disk."
            dev.varun.studio.ai.EngineFailureKind.CORRUPT_OR_UNSUPPORTED ->
                "Re-download or re-import the file. If it was interrupted mid-download it will be " +
                    "incomplete. Also confirm the model architecture is supported by llama.cpp."
            dev.varun.studio.ai.EngineFailureKind.RUNTIME_UNAVAILABLE ->
                "The native runtime is not present for this device's CPU architecture. " +
                    "You need an arm64-v8a build of the APK."
            dev.varun.studio.ai.EngineFailureKind.BAD_PATH -> "Import the model again."
            dev.varun.studio.ai.EngineFailureKind.ALREADY_LOADED ->
                "Unload the current model first (Models → Unload)."
            dev.varun.studio.ai.EngineFailureKind.OTHER -> "Check the message above and retry."
        }

    suspend fun unload() = withContext(Dispatchers.IO) {
        engine.unload()
        _loadedModelId.value = null
        _loadState.value = LoadUiState.Idle
    }

    suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        val record = db.modelById(id) ?: return@withContext false
        if (_loadedModelId.value == id) unload()
        val deleted = runCatching { File(record.absolutePath).delete() }.getOrDefault(false)
        db.deleteModel(id)
        refresh()
        deleted
    }

    /**
     * Estimate whether a not-yet-downloaded model will fit, using the RAM
     * Android reports as available right now.
     */
    fun estimateFor(entry: ModelCatalog.Entry, availableRamBytes: Long): MemoryEstimator.Estimate {
        val s = settings.settings.value
        return entry.estimate(availableRamBytes, s.contextLength.coerceAtMost(entry.contextTokens), s.threads)
    }

    fun estimateForFile(fileBytes: Long, fileName: String, availableRamBytes: Long): MemoryEstimator.Estimate {
        val s = settings.settings.value
        return MemoryEstimator.estimateFromFile(
            fileBytes = fileBytes,
            quantTag = MemoryEstimator.detectQuant(fileName),
            nCtx = s.contextLength,
            nBatch = s.batch,
            nThreads = s.threads,
            availableBytes = availableRamBytes,
        )
    }

    private fun queryDisplayName(uri: Uri): Pair<String, Long> {
        var name = "model.gguf"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }
        if (size <= 0) {
            // Fall back to the file descriptor, which works for many providers.
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            }
        }
        return sanitizeName(name) to size
    }

    private fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').ifBlank { "model.gguf" }
        return base.replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(120).ifBlank { "model.gguf" }
    }

    private fun uniqueTargetFile(displayName: String): File {
        val safe = sanitizeName(displayName)
        var target = File(modelsDir, safe)
        var n = 1
        while (target.exists()) {
            val stem = safe.substringBeforeLast('.')
            val ext = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
            target = File(modelsDir, "$stem-$n$ext")
            n++
        }
        return target
    }

    /** Verify a file's digest on demand (used after a manual download). */
    suspend fun computeSha256(file: File): String = withContext(Dispatchers.IO) {
        runCatching { Digests.sha256Hex(file.readBytes()) }.getOrDefault("")
    }

    companion object {
        /** Keep at least 250 MB free after an import so the OS stays healthy. */
        const val MIN_FREE_AFTER_IMPORT = 250L * 1024 * 1024
    }
}

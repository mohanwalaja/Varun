package dev.varun.studio.workspace

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Progress callback: (entriesDone, currentEntryName).
 *
 * Declared at file top level because Kotlin does not support nested or local
 * type aliases - it cannot live inside `object ArchiveExtractor`.
 */
typealias ArchiveProgress = (Int, String) -> Unit

/**
 * Extracts repository archives into a workspace.
 *
 * GitHub's tarball endpoint returns `.tar.gz`, and users may also import a
 * `.zip` they created themselves. Both are untrusted: entries can contain
 * absolute paths, `../` traversal, symlink tricks and enormous declared sizes
 * (a "zip bomb"). Every entry goes through [PathGuard] and a size budget.
 *
 * Implemented with java.util.zip plus a minimal TAR reader so the APK does not
 * need commons-compress.
 */
object ArchiveExtractor {

    data class Options(
        val maxTotalBytes: Long = 512L * 1024 * 1024,
        val maxEntries: Int = 20_000,
        val maxSingleEntryBytes: Long = 64L * 1024 * 1024,
    )

    sealed interface ExtractResult {
        data class Success(
            val filesWritten: Int,
            val bytesWritten: Long,
            val skipped: Int,
            val rootPrefix: String?,
        ) : ExtractResult

        data class Failure(val message: String) : ExtractResult
    }

    fun extract(archive: File, destination: File, options: Options = Options(), progress: ArchiveProgress? = null): ExtractResult {
        if (!archive.exists() || !archive.isFile) {
            return ExtractResult.Failure("Archive not found: ${archive.name}")
        }
        val name = archive.name.lowercase()
        return try {
            when {
                name.endsWith(".zip") -> extractZip(archive, destination, options, progress)
                name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                    archive.inputStream().buffered().use { raw ->
                        GZIPInputStream(raw).use { gz -> extractTar(gz, destination, options, progress) }
                    }
                name.endsWith(".tar") ->
                    archive.inputStream().buffered().use { extractTar(it, destination, options, progress) }
                else -> ExtractResult.Failure(
                    "Unsupported archive type. Import a .zip, .tar or .tar.gz file.",
                )
            }
        } catch (t: Throwable) {
            ExtractResult.Failure("Extraction failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ ZIP

    private fun extractZip(archive: File, dest: File, options: Options, progress: ArchiveProgress?): ExtractResult {
        dest.mkdirs()
        var written = 0
        var bytes = 0L
        var skipped = 0
        val prefixes = mutableSetOf<String>()

        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                if (written >= options.maxEntries) {
                    skipped++
                    zis.closeEntry()
                    continue
                }
                val decision = PathGuard.resolve(dest, entry.name)
                val target = when (decision) {
                    is PathGuard.Decision.Allowed -> decision.file
                    is PathGuard.Decision.Denied -> {
                        skipped++
                        zis.closeEntry()
                        continue
                    }
                }
                prefixes.add(entry.name.split('/').first())

                val result = copyLimited(zis, target, options, bytes)
                if (result < 0) {
                    return ExtractResult.Failure(
                        "Archive exceeds the size budget (${options.maxTotalBytes / (1024 * 1024)} MB). " +
                            "Import a smaller repository or fetch only the files you need.",
                    )
                }
                bytes += result
                written++
                progress?.invoke(written, entry.name)
                zis.closeEntry()
            }
        }
        return ExtractResult.Success(written, bytes, skipped, singlePrefix(prefixes))
    }

    // ------------------------------------------------------------------ TAR

    private fun extractTar(input: java.io.InputStream, dest: File, options: Options, progress: ArchiveProgress?): ExtractResult {
        dest.mkdirs()
        var written = 0
        var bytes = 0L
        var skipped = 0
        val prefixes = mutableSetOf<String>()
        val header = ByteArray(512)

        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break  // two zero blocks end a tar

            val name = parseTarString(header, 0, 100)
            val prefix = parseTarString(header, 345, 155)
            val sizeOctal = parseTarString(header, 124, 12)
            val typeFlag = header[156].toInt().toChar()

            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            val size = parseOctal(sizeOctal)

            // Consume the entry payload (padded to a 512-byte boundary).
            val blocks = (size + 511) / 512

            when (typeFlag) {
                '0', '\u0000', '7' -> {
                    if (written >= options.maxEntries) {
                        skipped++
                        skipBytes(input, blocks * 512)
                        continue
                    }
                    val decision = PathGuard.resolve(dest, fullName)
                    val target = when (decision) {
                        is PathGuard.Decision.Allowed -> decision.file
                        is PathGuard.Decision.Denied -> {
                            skipped++
                            skipBytes(input, blocks * 512)
                            continue
                        }
                    }
                    prefixes.add(fullName.split('/').first())
                    val result = copyLimitedCounted(input, target, options, bytes, size)
                    skipBytes(input, blocks * 512 - size)
                    if (result < 0) {
                        return ExtractResult.Failure(
                            "Archive exceeds the size budget (${options.maxTotalBytes / (1024 * 1024)} MB).",
                        )
                    }
                    bytes += result
                    written++
                    progress?.invoke(written, fullName)
                }
                // '1' hardlink, '2' symlink, '3'/'4' devices, '5' dir, '6' fifo,
                // 'g'/'x' pax headers: deliberately NOT materialised. Creating
                // links from an untrusted archive is how a traversal escape
                // survives path validation.
                else -> skipBytes(input, blocks * 512)
            }
        }
        return ExtractResult.Success(written, bytes, skipped, singlePrefix(prefixes))
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun skipBytes(input: java.io.InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val n = input.skip(remaining)
            if (n > 0) { remaining -= n; continue }
            if (input.read() < 0) return
            remaining--
        }
    }

    private fun parseTarString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = offset + length
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun parseOctal(s: String): Long {
        val trimmed = s.trim().trim('\u0000').trim()
        if (trimmed.isEmpty()) return 0
        return try { java.lang.Long.parseLong(trimmed, 8) } catch (_: Throwable) { 0L }
    }

    /**
     * Streams [input] into [target], enforcing both a per-entry cap and the
     * overall budget. Returns bytes written, or -1 if the budget was exceeded.
     */
    private fun copyLimited(input: java.io.InputStream, target: File, options: Options, alreadyWritten: Long): Long {
        target.parentFile?.mkdirs()
        var total = 0L
        val buf = ByteArray(64 * 1024)
        FileOutputStream(target).use { out ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > options.maxSingleEntryBytes) return -1
                if (alreadyWritten + total > options.maxTotalBytes) return -1
                out.write(buf, 0, n)
            }
        }
        return total
    }

    private fun copyLimitedCounted(
        input: java.io.InputStream, target: File, options: Options, alreadyWritten: Long, declared: Long,
    ): Long {
        target.parentFile?.mkdirs()
        if (declared > options.maxSingleEntryBytes) return -1
        if (alreadyWritten + declared > options.maxTotalBytes) return -1
        var total = 0L
        val buf = ByteArray(64 * 1024)
        var remaining = declared
        FileOutputStream(target).use { out ->
            while (remaining > 0) {
                val want = minOf(remaining, buf.size.toLong()).toInt()
                val n = input.read(buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
                remaining -= n
            }
        }
        return total
    }

    /**
     * GitHub tarballs wrap everything in one top-level directory
     * (`owner-repo-sha/`). Reporting it lets the workspace UI strip the noise.
     */
    private fun singlePrefix(prefixes: Set<String>): String? =
        if (prefixes.size == 1) prefixes.first() else null
}

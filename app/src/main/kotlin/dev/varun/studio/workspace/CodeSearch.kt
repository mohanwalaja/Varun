package dev.varun.studio.workspace

import java.io.File

/**
 * Local source-code search over a workspace.
 *
 * Runs entirely on-device with no index to build and no daemon to run, which
 * matters on a phone: a plain streaming scan of text files is fast enough for
 * the tens of thousands of lines a mobile user will actually import, and it
 * never holds the repository in memory.
 */
object CodeSearch {

    data class Match(
        val relativePath: String,
        val lineNumber: Int,
        val line: String,
        val column: Int,
        val matched: String,
    )

    data class Options(
        val caseSensitive: Boolean = false,
        val wholeWord: Boolean = false,
        val regex: Boolean = false,
        val maxMatches: Int = 500,
        val maxFileBytes: Long = 2L * 1024 * 1024,
        val includeHidden: Boolean = false,
    )

    data class Result(
        val matches: List<Match>,
        val filesScanned: Int,
        val filesSkipped: Int,
        val truncated: Boolean,
        val elapsedMs: Long,
    )

    /** Extensions we consider source/config text worth searching. */
    private val TEXT_EXTENSIONS = setOf(
        "kt", "kts", "java", "gradle", "xml", "json", "yaml", "yml", "toml", "ini", "cfg",
        "properties", "pro", "md", "markdown", "txt", "sh", "bash", "zsh", "py", "js", "mjs",
        "cjs", "ts", "tsx", "jsx", "html", "htm", "css", "scss", "less", "c", "h", "cc", "cpp",
        "cxx", "hpp", "hh", "cs", "go", "rs", "rb", "php", "swift", "m", "mm", "sql", "r",
        "lua", "pl", "dart", "vue", "svelte", "dockerfile", "makefile", "cmake", "env",
        "gitignore", "gitattributes", "editorconfig", "lock", "csv", "tsv",
    )

    /** Directories that are never worth scanning on a phone. */
    private val SKIP_DIRS = setOf(
        ".git", ".hg", ".svn", "build", "dist", "out", "target", "node_modules", ".gradle",
        ".idea", ".cxx", "__pycache__", ".venv", "venv", ".mypy_cache", ".pytest_cache",
        ".next", ".nuxt", ".output", "Pods", ".terraform", "vendor",
    )

    private val BINARY_MARKERS = byteArrayOf(0)

    fun isProbablyText(file: File, maxProbeBytes: Int = 4096): Boolean {
        if (!file.isFile) return false
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) {
            val name = file.name.lowercase()
            if (name !in setOf("makefile", "dockerfile", "license", "notice", "rakefile", "gemfile")) {
                // Unknown extension: sniff bytes.
                return sniff(file, maxProbeBytes)
            }
            return true
        }
        if (ext in TEXT_EXTENSIONS) return true
        // Anything not recognised gets a cheap byte sniff.
        return sniff(file, maxProbeBytes)
    }

    private fun sniff(file: File, maxProbeBytes: Int): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(maxProbeBytes)
                val read = input.read(buf)
                if (read <= 0) return@use true
                for (i in 0 until read) {
                    if (buf[i] in BINARY_MARKERS) return@use false
                }
                true
            }
        }.getOrDefault(false)
    }

    fun shouldSkipDir(name: String, options: Options): Boolean {
        if (name in SKIP_DIRS) return true
        if (!options.includeHidden && name.startsWith(".") && name != ".github") return true
        return false
    }

    /**
     * Search every text file under [root]. Returns as soon as [Options.maxMatches]
     * is reached so a pathological pattern cannot hang the UI.
     */
    fun search(root: File, query: String, options: Options = Options()): Result {
        val started = System.currentTimeMillis()
        val matches = ArrayList<Match>()
        var scanned = 0
        var skipped = 0
        var truncated = false

        if (query.isEmpty() || !root.isDirectory) {
            return Result(matches, 0, 0, false, System.currentTimeMillis() - started)
        }

        val needle = if (options.caseSensitive) query else query.lowercase()
        val pattern: Regex? = if (options.regex) {
            runCatching {
                Regex(
                    query,
                    if (options.caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE),
                )
            }.getOrNull()
        } else null

        if (options.regex && pattern == null) {
            return Result(emptyList(), 0, 0, false, System.currentTimeMillis() - started)
        }

        val queue = ArrayDeque<File>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children.sortedWith(compareBy({ !it.isDirectory }, { it.name }))) {
                if (child.isDirectory) {
                    if (!shouldSkipDir(child.name, options)) queue.addLast(child)
                    continue
                }
                if (child.length() > options.maxFileBytes) { skipped++; continue }
                val relative = PathGuard.relativize(root, child) ?: run { skipped++; continue }
                if (!isProbablyText(child)) { skipped++; continue }
                scanned++
                val text = runCatching { child.readText(Charsets.UTF_8) }.getOrNull()
                if (text == null) { skipped++; scanned--; continue }
                var lineNumber = 0
                for (rawLine in DiffEngine.splitLines(text)) {
                    lineNumber++
                    val haystack = if (options.caseSensitive) rawLine else rawLine.lowercase()
                    val hitColumn = if (pattern != null) {
                        pattern.find(rawLine)?.range?.first ?: -1
                    } else haystack.indexOf(needle)
                    if (hitColumn < 0) continue
                    val matched = if (options.wholeWord) {
                        if (!isWholeWord(rawLine, hitColumn, query.length)) continue
                        query
                    } else if (pattern != null) {
                        rawLine.substring(pattern.find(rawLine)!!.range)
                    } else {
                        val end = minOf(rawLine.length, hitColumn + query.length)
                        rawLine.substring(hitColumn, end)
                    }
                    matches.add(Match(relative, lineNumber, rawLine.trim(), hitColumn, matched))
                    if (matches.size >= options.maxMatches) {
                        truncated = true
                        return Result(matches, scanned, skipped, true, System.currentTimeMillis() - started)
                    }
                }
            }
        }
        return Result(matches, scanned, skipped, truncated, System.currentTimeMillis() - started)
    }

    private fun isWholeWord(line: String, start: Int, length: Int): Boolean {
        val before = if (start > 0) line[start - 1] else ' '
        val afterIdx = start + length
        val after = if (afterIdx < line.length) line[afterIdx] else ' '
        return !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
    }

    /**
     * Rank files for AI context: prefer small, text-like files whose path or
     * content matches the query. Used to pick which files to feed a 1B-3B model
     * whose context is precious.
     */
    fun rankForContext(root: File, query: String, maxFiles: Int): List<File> {
        val result = search(root, query, Options(maxMatches = 200))
        val scored = result.matches
            .groupBy { it.relativePath }
            .map { (path, hits) ->
                val file = File(root, path)
                val sizePenalty = if (file.exists()) file.length() / 4096.0 else 0.0
                Triple(path, hits.size.toDouble() - sizePenalty.coerceAtMost(hits.size.toDouble() * 0.5), file)
            }
            .sortedByDescending { it.second }
        return scored.take(maxFiles).map { it.third }.filter { it.exists() }
    }
}

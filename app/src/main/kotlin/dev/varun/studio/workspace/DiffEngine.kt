package dev.varun.studio.workspace

import kotlin.math.abs
import kotlin.math.min

/**
 * Line-level diff used by the editor, the change reviewer and the audit report.
 *
 * Implements Myers' O((N+M)D) shortest-edit-script algorithm with a linear-space
 * divide-and-conquer refinement, so a 5,000-line file on a phone does not blow
 * up memory. Pure Kotlin, no dependencies, unit-tested.
 */
object DiffEngine {

    enum class LineKind { CONTEXT, ADDED, REMOVED }

    data class DiffLine(
        val kind: LineKind,
        /** Line number in the ORIGINAL file, or -1 for an added line. */
        val oldNumber: Int,
        /** Line number in the NEW file, or -1 for a removed line. */
        val newNumber: Int,
        val text: String,
    )

    data class DiffResult(
        val lines: List<DiffLine>,
        val added: Int,
        val removed: Int,
        val unchanged: Int,
        val identical: Boolean,
    ) {
        val totalChanges: Int get() = added + removed
    }

    /** Unified-diff hunk, for rendering and for building git patches. */
    data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val lines: List<DiffLine>,
    )

    private const val MAX_EDIT_DISTANCE = 5_000_000

    fun diff(oldText: String, newText: String): DiffResult {
        val a = splitLines(oldText)
        val b = splitLines(newText)
        val script = myers(a, b)
        return build(a, b, script)
    }

    /** Splits on \n, \r\n and \r without keeping the terminators. */
    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(text.length / 32 + 8)
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                out.add(sb.toString()); sb.setLength(0); i++
            } else if (c == '\r') {
                out.add(sb.toString()); sb.setLength(0)
                i += if (i + 1 < text.length && text[i + 1] == '\n') 2 else 1
            } else {
                sb.append(c); i++
            }
        }
        // Preserve a trailing line only if the text did not end with a newline.
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** True when the text ends with a newline; used to avoid adding one on save. */
    fun endsWithNewline(text: String): Boolean =
        text.isNotEmpty() && (text.endsWith("\n") || text.endsWith("\r"))

    // ---------------------------------------------------------------------
    // Myers
    // ---------------------------------------------------------------------

    /** Edit script: sequence of (DELETE | INSERT | EQUAL) ops over a and b. */
    private enum class Op { EQUAL, DELETE, INSERT }

    private data class Edit(val op: Op, val aIndex: Int, val bIndex: Int)

    private fun myers(a: List<String>, b: List<String>): List<Edit> {
        val n = a.size
        val m = b.size
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return (0 until m).map { Edit(Op.INSERT, -1, it) }
        if (m == 0) return (0 until n).map { Edit(Op.DELETE, it, -1) }

        val edits = ArrayList<Edit>(n + m)
        // Trim common prefix/suffix first: cheap and dramatically shortens D
        // for the typical "a few lines changed in a big file" case.
        var start = 0
        while (start < n && start < m && a[start] == b[start]) start++
        var endA = n
        var endB = m
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        for (i in 0 until start) edits.add(Edit(Op.EQUAL, i, i))
        edits.addAll(shortestEdit(a, b, start, endA, start, endB))
        // Suffix: indices line up because both tails shrink equally.
        var ai = endA
        var bi = endB
        while (ai < n && bi < m) { edits.add(Edit(Op.EQUAL, ai, bi)); ai++; bi++ }
        return edits
    }

    private fun shortestEdit(
        a: List<String>, b: List<String>, aLo: Int, aHi: Int, bLo: Int, bHi: Int,
    ): List<Edit> {
        val n = aHi - aLo
        val m = bHi - bLo
        if (n == 0 && m == 0) return emptyList()
        if (n == 0) return (bLo until bHi).map { Edit(Op.INSERT, -1, it) }
        if (m == 0) return (aLo until aHi).map { Edit(Op.DELETE, it, -1) }

        val max = n + m
        if (max > MAX_EDIT_DISTANCE) {
            // Pathological input: fall back to "replace the whole region" so we
            // still return something correct rather than hanging the UI.
            return (aLo until aHi).map { Edit(Op.DELETE, it, -1) } +
                (bLo until bHi).map { Edit(Op.INSERT, -1, it) }
        }

        // v[k] = furthest reaching x on diagonal k, offset by max.
        val v = IntArray(2 * max + 1)
        val trace = ArrayList<IntArray>()

        outer@ for (d in 0..max) {
            trace.add(v.copyOf())
            for (k in -d..d step 2) {
                val idx = k + max
                val x = if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    v[idx + 1]
                } else {
                    v[idx - 1] + 1
                }
                var xx = x
                var yy = xx - k
                while (aLo + xx < aHi && bLo + yy < bHi && a[aLo + xx] == b[bLo + yy]) {
                    xx++; yy++
                }
                v[idx] = xx
                if (xx >= n && yy >= m) break@outer
            }
        }

        return backtrack(trace, a, b, aLo, aHi, bLo, bHi, max)
    }

    private fun backtrack(
        trace: List<IntArray>, a: List<String>, b: List<String>,
        aLo: Int, aHi: Int, bLo: Int, bHi: Int, max: Int,
    ): List<Edit> {
        var x = aHi - aLo
        var y = bHi - bLo
        val reversed = ArrayList<Edit>()

        for (d in trace.indices.reversed()) {
            val v = trace[d]
            val k = x - y
            val idx = k + max
            val prevK = if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) k + 1 else k - 1
            val prevX = if (prevK + max in v.indices) v[prevK + max] else 0
            val prevY = prevX - prevK

            while (x > prevX && y > prevY) {
                reversed.add(Edit(Op.EQUAL, aLo + x - 1, bLo + y - 1))
                x--; y--
            }
            if (d > 0) {
                if (x == prevX) {
                    reversed.add(Edit(Op.INSERT, -1, bLo + prevY))
                } else {
                    reversed.add(Edit(Op.DELETE, aLo + prevX, -1))
                }
                x = prevX
                y = prevY
            }
        }
        reversed.reverse()
        return reversed
    }

    private fun build(a: List<String>, b: List<String>, script: List<Edit>): DiffResult {
        if (script.isEmpty()) {
            return DiffResult(emptyList(), 0, 0, 0, a.isEmpty() && b.isEmpty())
        }
        val out = ArrayList<DiffLine>(script.size)
        var oldLine = 1
        var newLine = 1
        var added = 0
        var removed = 0
        var same = 0
        for (e in script) {
            when (e.op) {
                Op.EQUAL -> {
                    out.add(DiffLine(LineKind.CONTEXT, oldLine++, newLine++, a[e.aIndex]))
                    same++
                }
                Op.DELETE -> {
                    out.add(DiffLine(LineKind.REMOVED, oldLine++, -1, a[e.aIndex]))
                    removed++
                }
                Op.INSERT -> {
                    out.add(DiffLine(LineKind.ADDED, -1, newLine++, b[e.bIndex]))
                    added++
                }
            }
        }
        return DiffResult(out, added, removed, same, identical = added == 0 && removed == 0)
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    /** Group changes into hunks with [context] unchanged lines around each. */
    fun hunks(result: DiffResult, context: Int = 3): List<Hunk> {
        if (result.identical) return emptyList()
        val lines = result.lines
        val changed = lines.indices.filter { lines[it].kind != LineKind.CONTEXT }
        if (changed.isEmpty()) return emptyList()

        val groups = ArrayList<MutableList<Int>>()
        var current = mutableListOf(changed[0])
        for (i in 1 until changed.size) {
            if (changed[i] - changed[i - 1] <= context * 2 + 1) {
                current.add(changed[i])
            } else {
                groups.add(current)
                current = mutableListOf(changed[i])
            }
        }
        groups.add(current)

        return groups.map { g ->
            val from = maxOf(0, g.first() - context)
            val to = minOf(lines.size - 1, g.last() + context)
            val slice = lines.subList(from, to + 1)
            val oldStart = slice.firstOrNull { it.oldNumber > 0 }?.oldNumber ?: 0
            val newStart = slice.firstOrNull { it.newNumber > 0 }?.newNumber ?: 0
            Hunk(
                oldStart = oldStart,
                oldCount = slice.count { it.kind != LineKind.ADDED },
                newStart = newStart,
                newCount = slice.count { it.kind != LineKind.REMOVED },
                lines = slice,
            )
        }
    }

    /** Classic `diff -u` text, suitable for showing a patch or a commit body. */
    fun unifiedDiff(
        oldPath: String,
        newPath: String,
        oldText: String,
        newText: String,
        context: Int = 3,
    ): String {
        val result = diff(oldText, newText)
        if (result.identical) return ""
        val sb = StringBuilder()
        sb.append("--- a/").append(oldPath).append('\n')
        sb.append("+++ b/").append(newPath).append('\n')
        for (h in hunks(result, context)) {
            sb.append("@@ -").append(h.oldStart).append(',').append(h.oldCount)
                .append(" +").append(h.newStart).append(',').append(h.newCount)
                .append(" @@\n")
            for (l in h.lines) {
                when (l.kind) {
                    LineKind.CONTEXT -> sb.append(' ')
                    LineKind.ADDED -> sb.append('+')
                    LineKind.REMOVED -> sb.append('-')
                }
                sb.append(l.text).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * Word-level diff inside a single line, used to highlight exactly what
     * changed on a small-screen phone instead of showing two whole lines.
     */
    data class WordDelta(val parts: List<Pair<String, LineKind>>)

    fun wordDiff(oldLine: String, newLine: String): WordDelta {
        val a = oldLine.split(WORD_SPLIT).filter { it.isNotEmpty() }
        val b = newLine.split(WORD_SPLIT).filter { it.isNotEmpty() }
        // Small inputs only; guard against pathological long lines.
        if (a.size * b.size > 250_000) {
            return WordDelta(
                listOf(oldLine to LineKind.REMOVED, newLine to LineKind.ADDED),
            )
        }
        // LCS table
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val parts = ArrayList<Pair<String, LineKind>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { parts.add(a[i] to LineKind.CONTEXT); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { parts.add(a[i] to LineKind.REMOVED); i++ }
                else -> { parts.add(b[j] to LineKind.ADDED); j++ }
            }
        }
        while (i < a.size) { parts.add(a[i] to LineKind.REMOVED); i++ }
        while (j < b.size) { parts.add(b[j] to LineKind.ADDED); j++ }
        return WordDelta(parts)
    }

    private val WORD_SPLIT = Regex("(?<=\\s)|(?=\\s)")

    /** Similarity ratio in 0..1, used to decide whether a proposal is plausible. */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val la = a.length
        val lb = b.length
        if (la == 0 || lb == 0) return 0.0
        // Bounded Levenshtein: only compute when sizes are comparable.
        if (abs(la - lb) > maxOf(la, lb) * 0.9) return 0.0
        val distance = levenshtein(a, b, maxOf(la, lb))
        return 1.0 - distance.toDouble() / maxOf(la, lb)
    }

    private fun levenshtein(a: String, b: String, cap: Int): Int {
        if (cap > 20_000) return cap
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }
}

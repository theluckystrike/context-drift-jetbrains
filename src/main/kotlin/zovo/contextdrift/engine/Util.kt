package zovo.contextdrift.engine

internal const val MAX_INSTRUCTION_BYTES = 1024 * 1024
internal const val MAX_MANIFEST_BYTES = 1024 * 1024
internal const val MAX_WALK_ENTRIES = 200_000
internal const val MAX_LINE_CHARS = 4000
internal const val MAX_GLOBS_PER_FILE = 200
internal const val GLOB_WORK_PER_FILE = 3_000_000
internal const val MAX_GITIGNORE_RULES = 5000
internal const val MAX_SPANS_PER_FILE = 20_000
internal const val MAX_FINDINGS_PER_FILE = 2000

internal val IGNORED_DIRS = setOf(
    "node_modules", ".git", "dist", "build", "vendor", "target", ".venv", "venv",
    "__pycache__", ".next", ".nuxt", ".svelte-kit", ".turbo", ".cache", "coverage",
    ".gradle", ".idea", "Pods", ".terraform", ".tox", ".mypy_cache", ".pytest_cache",
    ".ruff_cache", "bower_components", ".output", ".parcel-cache", ".yarn", ".pnpm-store",
    "site-packages", ".hg", ".svn", ".direnv", ".eggs",
)

internal val INSTRUCTION_BASENAMES = setOf(
    "AGENTS.md", "CLAUDE.md", "CLAUDE.local.md", "GEMINI.md", "SKILL.md",
    ".cursorrules", ".windsurfrules", ".clinerules",
)

private val CURSOR_RULE = Regex("(^|/)\\.cursor/rules/(?:[^/]+/)*[^/]+\\.mdc$")
private val COPILOT = Regex("(^|/)\\.github/copilot-instructions\\.md$")
private val COPILOT_SCOPED = Regex("(^|/)\\.github/instructions/(?:[^/]+/)*[^/]+\\.instructions\\.md$")
private val CLINE_RULE = Regex("(^|/)\\.clinerules/(?:[^/]+/)*[^/]+\\.md$")
private val WINDSURF_RULE = Regex("(^|/)\\.windsurf/rules/(?:[^/]+/)*[^/]+\\.md$")

/** Returns a short kind string when rel (posix, relative) is an instruction file, else null. */
fun classifyInstructionFile(rel: String): String? {
    val p = rel.replace('\\', '/')
    val base = p.substring(p.lastIndexOf('/') + 1)
    if (base in INSTRUCTION_BASENAMES) return base
    if (CURSOR_RULE.containsMatchIn(p)) return "cursor-rule"
    if (COPILOT.containsMatchIn(p)) return "copilot-instructions"
    if (COPILOT_SCOPED.containsMatchIn(p)) return "copilot-scoped"
    if (CLINE_RULE.containsMatchIn(p)) return "cline-rule"
    if (WINDSURF_RULE.containsMatchIn(p)) return "windsurf-rule"
    return null
}

private val SCOPE_RE = Regex("^(.*?)(?:^|/)(?:\\.github|\\.cursor|\\.clinerules|\\.windsurf)/")

/** Directory whose files the instruction file "governs" (for CD005 and CD007 grouping). */
fun scopeDirOf(rel: String): String {
    val kind = classifyInstructionFile(rel)
    if (kind != null && kind !in INSTRUCTION_BASENAMES) {
        SCOPE_RE.find(rel)?.let { return it.groupValues[1] }
    }
    return posixDirname(rel)
}

internal fun posixDirname(rel: String): String {
    val i = rel.lastIndexOf('/')
    return if (i < 0) "" else rel.substring(0, i)
}

internal fun posixJoin(a: String?, b: String?): String? {
    if (b == null) return null
    if (a.isNullOrEmpty()) return normalizeRel(b)
    return normalizeRel("$a/$b")
}

/** Normalizes a relative posix path. Returns null when it escapes the root. */
internal fun normalizeRel(p: String?): String? {
    if (p == null) return null
    val out = ArrayList<String>()
    for (seg in p.split('/')) {
        if (seg.isEmpty() || seg == ".") continue
        if (seg == "..") {
            if (out.isEmpty()) return null
            out.removeAt(out.size - 1)
            continue
        }
        out.add(seg)
    }
    return out.joinToString("/")
}

internal fun ancestors(relDir: String?): List<String> {
    val out = ArrayList<String>()
    var d = relDir ?: ""
    while (true) {
        out.add(d)
        if (d.isEmpty()) break
        d = posixDirname(d)
    }
    return out
}

internal fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (kotlin.math.abs(a.length - b.length) > 3) return 99
    val m = a.length
    val n = b.length
    var prev = IntArray(n + 1) { it }
    for (i in 1..m) {
        val cur = IntArray(n + 1)
        cur[0] = i
        for (j in 1..n) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return prev[n]
}

internal fun closest(name: String, candidates: Iterable<String>): String? {
    var best: String? = null
    var bestD = 99
    for (c in candidates) {
        val d = levenshtein(name, c)
        if (d < bestD) {
            best = c; bestD = d
        }
    }
    val limit = if (name.length <= 4) 1 else 2
    return if (bestD <= limit) best else null
}

internal fun isBinaryText(text: String): Boolean {
    val n = minOf(text.length, 8000)
    for (i in 0 until n) if (text[i].code == 0) return true
    return false
}

internal fun stripBom(t: String): String = if (t.isNotEmpty() && t[0].code == 0xfeff) t.substring(1) else t

private val LINE_SPLIT = Regex("\r\n|\r|\n")
internal fun splitLines(t: String): List<String> = t.split(LINE_SPLIT)

private val UNSAFE_CHARS = Regex("[\\u0000-\\u0008\\u000b-\\u001f\\u007f-\\u009f\\u061c\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")

/** Replaces control and bidi characters with visible \uXXXX escapes (tabs and line breaks stay). */
fun safeText(s: String): String = UNSAFE_CHARS.replace(s) { m -> "\\u" + m.value[0].code.toString(16).padStart(4, '0') }

internal fun mkDiag(
    ruleId: String, severity: Severity, rel: String, line: Int, col: Int, endCol: Int, message: String,
    fix: Fix? = null, related: List<Related> = emptyList(), data: Map<String, Any?> = emptyMap(),
): Diagnostic = Diagnostic(
    ruleId, severity, rel, line, col, line, endCol, safeText(message),
    fix?.copy(title = safeText(fix.title)), related.map { it.copy(message = safeText(it.message)) }, data,
)

internal fun baseName(rel: String) = rel.substring(rel.lastIndexOf('/') + 1)

/** JS-style "last match end" for a regex over t, or -1. */
internal fun lastEnd(re: Regex, t: String): Int {
    var end = -1
    for (m in re.findAll(t)) end = m.range.last + 1
    return end
}

internal fun wordCount(t: String): Int = t.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

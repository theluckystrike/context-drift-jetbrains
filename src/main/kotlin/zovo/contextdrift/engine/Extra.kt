package zovo.contextdrift.engine

import java.io.File
import java.util.concurrent.TimeUnit

/* Rules added in the JetBrains port: CD007 duplicated instructions and CD008 size limits. Plus CD006 (git) and
 * the "disable for this line" directive placement used by the quick fix. */

/* ---------- CD007 duplicated instructions ---------- */

private val LIST_MARKER = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(?:\[[ xX]\]\s+)?""")
private val TRAILING_PUNCT = Regex("""[\s.;:,!]+$""")
private val MULTI_WS = Regex("""\s+""")
private val SKIP_LINE = Regex("""^\s*(?:#|\||>|<|---|\*\*\*|___|@\S+\s*$)""")
private const val MIN_DUP_WORDS = 5
private const val MAX_DUP_PER_FILE = 50

/** A normalized instruction line: list markers, case, spacing and end punctuation do not matter. */
internal fun normalizeInstruction(line: String): String? {
    if (SKIP_LINE.containsMatchIn(line)) return null
    val t = line.replace(LIST_MARKER, "").replace(TRAILING_PUNCT, "").replace(MULTI_WS, " ").trim().lowercase()
    if (t.isEmpty() || wordCount(t) < MIN_DUP_WORDS) return null
    return t
}

/** Instruction lines of a document outside code fences and frontmatter: normalized text -> first line numbers. */
internal fun instructionLines(doc: Doc): List<Pair<String, Int>> {
    val out = ArrayList<Pair<String, Int>>()
    for (p in doc.prose) {
        val n = normalizeInstruction(p.text) ?: continue
        out.add(n to p.line)
    }
    return out
}

internal fun duplicateLinesInFile(rel: String, doc: Doc): List<Diagnostic> {
    val first = HashMap<String, Int>()
    val out = ArrayList<Diagnostic>()
    for ((norm, line) in instructionLines(doc)) {
        val prev = first[norm]
        if (prev == null) { first[norm] = line; continue }
        if (out.size >= MAX_DUP_PER_FILE) break
        val text = doc.lines[line]
        val start = text.length - text.trimStart().length
        out.add(mkDiag("CD007", Severity.WARNING, rel, line, start, maxOf(start + 1, text.trimEnd().length),
            "This instruction repeats line ${prev + 1}. The agent reads it twice, and a later edit to one copy will make them disagree.",
            fix = Fix("Remove this duplicate line", line, 0, line + 1, 0, ""),
            data = mapOf("kind" to "dup-line", "first" to prev)))
    }
    return out
}

/** Instruction file families that one agent loads together when the second file's folder is inside the first's. */
private fun family(kind: String?): String? = when (kind) {
    "CLAUDE.md", "CLAUDE.local.md" -> "claude"
    "AGENTS.md" -> "agents"
    "GEMINI.md" -> "gemini"
    else -> null
}
private val AGENT_NAME = mapOf("claude" to "Claude Code", "agents" to "Codex and other AGENTS.md readers", "gemini" to "Gemini CLI")

/** CD007 across files: the same instruction in two files the same agent loads at once (root and nested, or CLAUDE.md and CLAUDE.local.md). */
internal fun crossFileDuplicates(results: List<FileResult>): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    val withFam = results.filter { family(it.facts.kind) != null && it.doc != null }.sortedWith(compareBy({ it.rel.count { c -> c == '/' } }, { it.rel }))
    for (bi in withFam.indices) {
        val b = withFam[bi]
        var n = 0
        val bLines = instructionLines(b.doc!!)
        for (ai in 0 until bi) {
            val a = withFam[ai]
            if (family(a.facts.kind) != family(b.facts.kind)) continue
            val aDir = posixDirname(a.rel)
            val bDir = posixDirname(b.rel)
            if (!(aDir.isEmpty() || bDir == aDir || bDir.startsWith("$aDir/"))) continue
            if (aDir == bDir && a.facts.kind == b.facts.kind) continue
            val aIndex = HashMap<String, Int>()
            for ((norm, line) in instructionLines(a.doc!!)) aIndex.putIfAbsent(norm, line)
            for ((norm, line) in bLines) {
                val al = aIndex[norm] ?: continue
                if (n++ >= MAX_DUP_PER_FILE) break
                val text = b.doc.lines[line]
                val start = text.length - text.trimStart().length
                out.add(mkDiag("CD007", Severity.INFO, b.rel, line, start, maxOf(start + 1, text.trimEnd().length),
                    "This instruction is also in ${a.rel} (line ${al + 1}). ${AGENT_NAME[family(b.facts.kind)]} loads both files, so the agent reads it twice.",
                    related = listOf(Related(a.rel, al, 0, a.doc.lines[al].length, "${a.rel}:${al + 1}")),
                    data = mapOf("kind" to "dup-cross", "other" to a.rel)))
            }
        }
    }
    val seen = HashSet<String>()
    return out.filter { seen.add(it.file + ":" + it.line) }
}

/* ---------- CD008 size limit ---------- */

internal fun sizeFinding(rel: String, kind: String?, text: String, doc: Doc, l: SizeLimits): Diagnostic? {
    val lines = doc.lines
    fun lineAtChar(offset: Int): Int {
        var acc = 0
        for ((i, ln) in lines.withIndex()) { acc += ln.length + 1; if (acc > offset) return i }
        return maxOf(0, lines.size - 1)
    }
    fun lineAtByte(limit: Int): Int {
        var acc = 0
        for ((i, ln) in lines.withIndex()) { acc += ln.toByteArray(Charsets.UTF_8).size + 1; if (acc > limit) return i }
        return maxOf(0, lines.size - 1)
    }
    fun diag(sev: Severity, line: Int, msg: String, data: Map<String, Any?>): Diagnostic {
        val lt = lines.getOrElse(line) { "" }
        return mkDiag("CD008", sev, rel, line, 0, maxOf(1, lt.length), msg, data = data)
    }
    val chars = text.length
    val bytes = text.toByteArray(Charsets.UTF_8).size
    fun kb(n: Int) = String.format(java.util.Locale.ROOT, "%.1f", n / 1024.0)
    return when (kind) {
        "AGENTS.md" -> if (bytes > l.agentsMdBytes) diag(Severity.WARNING, lineAtByte(l.agentsMdBytes),
            "AGENTS.md is ${kb(bytes)} KiB. Codex reads at most ${l.agentsMdBytes / 1024} KiB of project instructions (project_doc_max_bytes) and cuts the rest, starting on this line. Move detail into nested AGENTS.md files or linked docs.",
            mapOf("kind" to "size", "limit" to l.agentsMdBytes, "size" to bytes)) else null
        "CLAUDE.md", "CLAUDE.local.md" -> if (chars > l.claudeMdChars) diag(Severity.WARNING, lineAtChar(l.claudeMdChars),
            "${baseName(rel)} has ${"%,d".format(java.util.Locale.ROOT, chars)} characters. Claude Code warns above ${"%,d".format(java.util.Locale.ROOT, l.claudeMdChars)} (\"Large CLAUDE.md will impact performance\"); the limit is crossed on this line. Split it into folder CLAUDE.md files.",
            mapOf("kind" to "size", "limit" to l.claudeMdChars, "size" to chars)) else null
        "copilot-instructions", "copilot-scoped" -> if (chars > l.copilotChars) diag(Severity.INFO, lineAtChar(l.copilotChars),
            "This file has ${"%,d".format(java.util.Locale.ROOT, chars)} characters. Copilot code review reads only the first ${"%,d".format(java.util.Locale.ROOT, l.copilotChars)}, so review ignores everything from this line on (Copilot Chat reads it all).",
            mapOf("kind" to "size", "limit" to l.copilotChars, "size" to chars)) else null
        ".windsurfrules" -> if (chars > l.windsurfChars) diag(Severity.WARNING, lineAtChar(l.windsurfChars),
            ".windsurfrules has ${"%,d".format(java.util.Locale.ROOT, chars)} characters. Windsurf reads at most ${"%,d".format(java.util.Locale.ROOT, l.windsurfChars)} and ignores the rest, starting on this line.",
            mapOf("kind" to "size", "limit" to l.windsurfChars, "size" to chars)) else null
        "windsurf-rule" -> if (chars > 2 * l.windsurfChars) diag(Severity.INFO, lineAtChar(2 * l.windsurfChars),
            "This rule file has ${"%,d".format(java.util.Locale.ROOT, chars)} characters, over Windsurf's documented rule limit of ${"%,d".format(java.util.Locale.ROOT, 2 * l.windsurfChars)}. Text from this line on may be dropped.",
            mapOf("kind" to "size", "limit" to 2 * l.windsurfChars, "size" to chars)) else null
        "cursor-rule", ".cursorrules" -> if (lines.size > l.cursorLines) diag(Severity.INFO, l.cursorLines,
            "This rule has ${lines.size} lines. Cursor recommends keeping a rule under ${l.cursorLines} lines; split it into smaller rules.",
            mapOf("kind" to "size", "limit" to l.cursorLines, "size" to lines.size)) else null
        "SKILL.md" -> if (lines.size > l.cursorLines) diag(Severity.INFO, l.cursorLines,
            "SKILL.md has ${lines.size} lines. Anthropic's skill guide recommends a body under ${l.cursorLines} lines; move detail into reference files.",
            mapOf("kind" to "size", "limit" to l.cursorLines, "size" to lines.size)) else null
        else -> if (chars > l.otherChars) diag(Severity.INFO, lineAtChar(l.otherChars),
            "This instruction file has ${"%,d".format(java.util.Locale.ROOT, chars)} characters. Agents load it into every session; consider splitting it.",
            mapOf("kind" to "size", "limit" to l.otherChars, "size" to chars)) else null
    }
}

/* ---------- "disable for this line" directive placement ---------- */

data class DirectiveInsertion(val line: Int, val text: String, val block: Boolean)

/** Where a "disable" directive must go for line: a YAML comment in frontmatter, above the fence for code blocks. */
fun directiveInsertion(text: String, line: Int, ruleId: String): DirectiveInsertion {
    val doc = scanDocument(stripBom(text))
    var at = line
    var yaml = false
    val fm = doc.frontmatter
    if (fm != null && line > fm.first && line < fm.last) yaml = true
    else doc.fences.firstOrNull { line > it.start && line <= it.end }?.let { at = it.start }
    val lt = doc.lines.getOrElse(at) { "" }
    val indent = lt.substring(0, lt.length - lt.trimStart().length)
    val comment = if (yaml) "$indent# context-drift-disable-next-line $ruleId\n" else "$indent<!-- context-drift-disable-next-line $ruleId -->\n"
    return DirectiveInsertion(at, comment, at != line)
}

/* ---------- CD006 stale file (read-only git, 3 s timeout per call) ---------- */

internal object Git {
    private const val TIMEOUT_MS = 3000L

    fun run(cwd: String, vararg args: String): String? = try {
        val p = ProcessBuilder(listOf("git", "-c", "core.quotepath=off") + args).directory(File(cwd)).redirectErrorStream(false).start()
        p.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { try { out.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) { } }
        reader.isDaemon = true
        reader.start()
        if (!p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); null }
        else { reader.join(1000); if (p.exitValue() == 0) out.toString() else null }
    } catch (_: Exception) { null }

    fun staleness(ctx: RepoContext, results: List<FileResult>, options: Options): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        val now = options.now / 1000
        val staleSec = options.staleDays.toLong() * 86400
        var times: List<Long>? = null
        for (r in results) {
            val ct = run(ctx.root, "log", "-1", "--format=%ct", "--", r.rel)?.trim()?.toLongOrNull() ?: continue
            if (now - ct < staleSec) continue
            if (times == null) times = run(ctx.root, "log", "--format=%ct", "HEAD")?.lines()?.mapNotNull { it.trim().toLongOrNull() } ?: return out
            val since = times.count { it > ct }
            if (since < options.staleCommits) continue
            if (r.doc != null && isDisabled(r.doc, "CD006", 0)) continue
            val days = (now - ct) / 86400
            val date = java.time.Instant.ofEpochSecond(ct).toString().substring(0, 10)
            val first = splitLines(r.text).firstOrNull() ?: ""
            out.add(mkDiag("CD006", Severity.INFO, r.rel, 0, 0, maxOf(1, first.length),
                "Last changed $days days ago ($date); the repo has $since commits since. Review it for stale facts.", data = mapOf("kind" to "stale", "days" to days, "commits" to since)))
        }
        return out
    }
}

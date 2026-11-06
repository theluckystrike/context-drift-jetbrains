package zovo.contextdrift.engine

import java.io.File

/** Per-file analysis result. */
internal class FileResult(val rel: String, val diagnostics: List<Diagnostic>, val facts: FileFacts, val text: String, val doc: Doc?)

private val INLINE_PROMPT = Regex("^(?:\\$|❯|>)\\s+")
private val FENCE_PROMPT = Regex("^(\\s*)(?:\\$|%|❯|PS[^>]*>)\\s")
private val CD_TARGET_ABS = Regex("^[~/$-]|^[A-Za-z]:")
private val CREATOR_CMD = Regex("^(?:mkdir|git|npx|pnpm|yarn|bun|bunx|npm|cargo|uv|poetry|django-admin|rails|composer|dotnet|go)$")
private val PM_PROSE = Regex("""\b(?:use|uses|using|prefer|prefers|run with|install with|via)\s+`?(npm|pnpm|yarn|bun)`?(?![\w.@/-])""", RegexOption.IGNORE_CASE)
private val PM_GLOBAL_AFTER = Regex("""^\w+`?\s*(?:to|for)\s+(?:publish|log ?in|install\s+(?:it\s+)?globally|global)|\s-g\b|--global""", RegexOption.IGNORE_CASE)
private val PM_OVER = Regex("""\b(?:over|rather than|instead of|stop|stopped|quit|no longer|never)\s*$""", RegexOption.IGNORE_CASE)
private val FIRST_DIGIT = Regex("\\d")
private val MIN_REST = Regex("""^\s*(?:\+|or (?:newer|later|above|higher)|and (?:newer|later|above|up))""", RegexOption.IGNORE_CASE)
private val RANGE_REST = Regex("""^\s*(?:-|–|to|through)\s""", RegexOption.IGNORE_CASE)
private val RANGE_REST2 = Regex("""^\s*[-–]\s*v?\d""")
private val FM_GLOB_KEY = Regex("""^\s*(globs|applyTo)\s*:(.*)$""")
private val FM_LIST_ITEM = Regex("""^(\s*-\s*)(.*)$""")
private val FM_ARRAY_ITEM = Regex(""""([^"]*)"|'([^']*)'|([^,\[\]\s][^,\[\]]*)""")

/** Analyze one instruction file (CD001 to CD004 and CD008, plus the in-file part of CD007). */
internal fun analyzeText(ctx: RepoContext, rel: String, text0: String, deadline: Long = Long.MAX_VALUE, limits: SizeLimits = SizeLimits()): FileResult {
    val out = ArrayList<Diagnostic>()
    val facts = FileFacts(classifyInstructionFile(rel))
    val text = stripBom(text0)
    if (text.length > MAX_INSTRUCTION_BYTES || isBinaryText(text)) return FileResult(rel, out, facts, text, null)
    val doc = scanDocument(text)
    val instrDir = posixDirname(rel)
    var capped = false
    var timedOut = false
    var tick = 0
    fun stop(): Boolean {
        if (capped || timedOut) return true
        if ((++tick and 63) == 0 && System.currentTimeMillis() > deadline) { timedOut = true; facts.timedOut = true }
        return timedOut
    }
    fun emit(d: Diagnostic) {
        if (isDisabled(doc, d.ruleId, d.line)) return
        if (out.size >= MAX_FINDINGS_PER_FILE) { if (!capped) { capped = true; facts.capped = true }; return }
        out.add(d)
    }
    val seen = HashSet<String>()
    fun emitOnce(d: Diagnostic) { if (seen.add(d.absKey)) emit(d) }

    val pmInfo = ctx.repoPackageManager(instrDir)
    val repoFacts = facts.kind != "SKILL.md"

    // ---- commands from inline code and shell fences
    val sources = ArrayList<CmdSource>()
    for (s in doc.inline) sources.add(CmdSource(s.text, s.line, s.col, s.lineText, s.prevText, s.endCol, true))
    for (f in doc.fence) {
        if (f.lang !in SHELL_LANGS) continue
        var t = f.text
        if (f.lang in PROMPT_LANGS || f.block.hasPrompt) {
            val pm = FENCE_PROMPT.find(t) ?: continue
            t = " ".repeat(pm.value.length) + t.substring(pm.value.length)
        }
        if (t.endsWith("\\")) t = t.dropLast(1)
        sources.add(CmdSource(t, f.line, 0, "", "", t.length, false, f.afterCreate))
    }
    if (repoFacts) for (src in sources) {
        if (stop()) break
        var t = src.text
        if (src.inline) INLINE_PROMPT.find(t)?.let { pm -> t = " ".repeat(pm.value.length) + t.substring(pm.value.length) }
        val segs = tokenizeShell(t, src.col)
        val interesting = segs.any { sg -> val tk = stripPrefixes(sg.toks); tk.isNotEmpty() && tk[0].v in COMMANDS_OF_INTEREST }
        if (!interesting) continue
        if (src.inline && negated(src.lineText, src.col, src.endCol, src.prevText)) continue
        src.elsewhere = src.inline && ELSEWHERE.containsMatchIn(sentenceAround(src.lineText, src.col, src.endCol, src.prevText))
        var cwdSet = false
        var cwd: String? = null // valid when cwdSet; null = unknown
        for ((segIndex, seg) in segs.withIndex()) {
            if (capped) break
            val toks = stripPrefixes(seg.toks)
            if (toks.isEmpty()) continue
            val cmd = toks[0].v
            if (cmd == "cd" || cmd == "pushd") {
                val target = toks.getOrNull(1)
                if (target == null || CD_TARGET_ABS.containsMatchIn(target.v) || !isLiteralPath(target.v)) { cwdSet = true; cwd = null; continue }
                val base = if (!cwdSet) instrDir else cwd
                if (base == null) continue
                val cand0 = normalizeRel(posixJoin(base, target.v))
                val cand1 = normalizeRel(target.v)
                if (cand0 == null) { cwdSet = true; cwd = null; continue }
                val hit = listOf(cand0, cand1).firstOrNull { it != null && ctx.isDirRel(it) }
                if (hit != null) { cwdSet = true; cwd = hit }
                else {
                    cwdSet = true; cwd = null
                    val created = src.afterCreate || segs.subList(0, segIndex).any { sg -> sg.toks.isNotEmpty() && CREATOR_CMD.matches(sg.toks[0].v) }
                    if (!created && (src.inline || seg.toks[0] === toks[0])) {
                        if (!looksIgnoredOrGenerated(ctx, cand1 ?: cand0) && !(src.inline && negated(src.lineText, src.col, src.endCol, src.prevText))) {
                            emitOnce(mkDiag("CD002", Severity.WARNING, rel, src.line, target.s, target.e,
                                "`cd ${target.v}`: directory \"${target.v}\" does not exist in the repo.",
                                data = mapOf("kind" to "path", "path" to target.v, "resolved" to cand0, "parent" to ctx.nearestExistingParent(cand0), "sparse" to ctx.sparse)))
                        }
                    }
                }
                continue
            }
            if (cwdSet && cwd == null) continue
            if (seg.dynamic) continue
            val dir = if (cwdSet) cwd!! else instrDir
            facts.dir = if (cwdSet) cwd else "."
            val emitCmd: Emit = { d ->
                var keep = true
                if (d.ruleId == "CD001") {
                    if (src.elsewhere) keep = false
                    if (ctx.sparse && (d.data["manifest"] == null || d.data["anyPkg"] == true || (d.data["kind"] as? String)?.startsWith("cargo") == true)) keep = false
                }
                if (keep) emitOnce(d)
            }
            checkCommand(ctx, rel, src, toks, dir, emitCmd, facts)
        }
    }

    // ---- CD003 prose: "use yarn", "we use npm"
    if (repoFacts && pmInfo != null) {
        for (p in doc.prose) {
            if (stop()) break
            for (m in PM_PROSE.findAll(p.text)) {
                val pmName = m.groupValues[1].lowercase()
                if (pmName == pmInfo.pm) continue
                val col = m.range.first + m.value.lastIndexOf(m.groupValues[1])
                val after = p.text.substring(col, minOf(p.text.length, col + 80))
                if (PM_GLOBAL_AFTER.containsMatchIn(after)) continue
                val before = stripSpans(contextBefore(p.text, m.range.first, p.prevText))
                if (negatedBefore(before)) continue
                if (PM_OVER.containsMatchIn(before)) continue
                emitOnce(mkDiag("CD003", Severity.WARNING, rel, p.line, col, col + m.groupValues[1].length,
                    "This file says to use $pmName, but the repo uses ${pmInfo.pm} (${pmInfo.source}).", data = mapOf("kind" to "pm", "expected" to pmInfo.pm)))
            }
        }
    }

    // ---- CD004 version claims (prose and shell fences)
    class VLine(val text: String, val line: Int, val prevText: String, val fence: Boolean)
    val vLines = ArrayList<VLine>()
    for (p in doc.prose) vLines.add(VLine(p.text, p.line, p.prevText, false))
    for (f in doc.fence) if (f.lang in SHELL_LANGS) vLines.add(VLine(f.text, f.line, "", true))
    if (repoFacts) for (vl in vLines) {
        if (stop()) break
        var perLine = 0
        pats@ for (pat in VERSION_PATTERNS) {
            if (vl.fence && !pat.cmd) continue
            for (m in pat.re.findAll(vl.text)) {
                if (++perLine > 16) break@pats
                val op: String
                val verStr: String
                val minSuffix: String
                if (pat.cmd) { op = ""; verStr = m.groupValues[1]; minSuffix = "" }
                else { op = m.groups[1]?.value ?: ""; verStr = m.groupValues[2]; minSuffix = m.groups[3]?.value ?: "" }
                val v = parseVer(verStr) ?: continue
                if (pat.minMajor > 0 && v[0] < pat.minMajor) continue
                if (v[0] > 40 && pat.lang == "node") continue
                val startCol = m.range.first
                val endCol = m.range.last + 1
                val lineText = vl.text
                val ctxB = stripSpans(contextBefore(lineText, startCol, vl.prevText))
                if (VERSION_NOT_A_CLAIM.containsMatchIn(ctxB)) continue
                val tail = lineText.substring(endCol, minOf(lineText.length, endCol + 60))
                if (VERSION_TAIL_NOT_A_CLAIM.containsMatchIn(tail)) continue
                val vers = arrayListOf(v)
                var rangeTo: List<Int>? = null
                var rest = tail
                var guard = 0
                while (guard++ < 8) {
                    val mm = MULTI_TAIL.find(rest) ?: break
                    val nv = parseVer(mm.groupValues[1]) ?: break
                    if (RANGE_REST.containsMatchIn(rest) || RANGE_REST2.containsMatchIn(rest)) rangeTo = nv
                    vers.add(nv)
                    rest = rest.substring(mm.value.length)
                }
                if (rangeTo != null && vers.size == 2) for (x in v[0] + 1 until rangeTo[0]) vers.add(listOf(x))
                val isMin = minSuffix.isNotEmpty() || op in listOf(">=", "=>", "≥", ">", "^") || MIN_REST.containsMatchIn(rest)
                val claim = Claim(vers, isMin)
                val bad = versionSources(ctx, pat.lang, instrDir).filter { !claimConsistent(claim, it) }
                if (bad.isEmpty()) continue
                val claimText = lineText.substring(startCol, endCol).trim()
                val srcText = bad.joinToString("; ") { "${it.source} says ${it.value}" }
                val fixText = if (bad.size == 1 && bad[0].kind == "pin" && !isMin && vers.size == 1) versionFix(lineText, startCol, endCol, verStr, bad[0]) else null
                emitOnce(mkDiag("CD004", Severity.WARNING, rel, vl.line, startCol, endCol,
                    "Version claim \"$claimText\" does not match the repo: $srcText.", fix = fixText?.let { (s, e, t) -> Fix("Replace with \"$t\"", vl.line, s, vl.line, e, t) },
                    data = mapOf("kind" to "version", "lang" to LANG_LABEL[pat.lang], "sources" to bad.map { it.source })))
            }
        }
    }

    // ---- CD002 paths (inline code and markdown links; never fenced blocks)
    val stats = PathStats()
    facts.paths = stats
    fun emitPath(d: Diagnostic) {
        if (d.severity != Severity.INFO) stats.missing++
        emitOnce(if (ctx.sparse) d.copy(data = d.data + ("sparse" to true)) else d)
    }
    val skill = if (repoFacts) null else skillRoots(ctx, instrDir)
    for (s in doc.inline) {
        if (stop()) break
        if (!repoFacts && !SKILL_BUNDLE_PATH.matches(s.text.replace('\\', '/'))) continue
        if (classifyPathCandidate(s.text, false) == null) continue
        checkPathCandidate(ctx, rel, instrDir, s, false, ::emitPath, skill, stats)
    }
    for (l in doc.links) {
        if (stop()) break
        if (classifyPathCandidate(l.text, true) == null) continue
        checkPathCandidate(ctx, rel, instrDir, l, true, ::emitPath, skill, stats)
    }

    // ---- CD002 frontmatter globs in .mdc / .instructions.md
    val fm = doc.frontmatter
    if (fm != null && !stop() && (facts.kind == "cursor-rule" || facts.kind == "copilot-scoped")) {
        val scope = scopeDirOf(rel)
        var i = fm.first + 1
        fmLoop@ while (i < fm.last) {
            val lineI = doc.lines[i]
            if (lineI.length > MAX_LINE_CHARS) { i++; continue }
            val m = FM_GLOB_KEY.find(lineI)
            if (m == null) { i++; continue }
            class Item(val g: String, val line: Int, val col: Int)
            val items = ArrayList<Item>()
            val value = m.groupValues[2].trim()
            val valCol = if (value.isNotEmpty()) lineI.length - m.groupValues[2].length + m.groupValues[2].indexOf(value) else 0
            if (value.isNotEmpty()) {
                if (value.startsWith("[")) {
                    for (mm in FM_ARRAY_ITEM.findAll(value)) {
                        val g = (mm.groups[1]?.value ?: mm.groups[2]?.value ?: mm.groups[3]?.value ?: "").trim()
                        items.add(Item(g, i, valCol + mm.range.first))
                    }
                } else {
                    val q = if (value[0] == '"' || value[0] == '\'') 1 else 0
                    val unq = value.substring(q, value.length - (if (q == 1 && value.length > 1 && value.endsWith(value[0])) 1 else 0))
                    var off = q
                    for (part in splitOutsideBraces(unq)) {
                        val g = part.trim()
                        if (g.isNotEmpty()) items.add(Item(g, i, valCol + off + part.indexOf(g)))
                        off += part.length + 1
                    }
                }
            } else {
                for (j in i + 1 until fm.last) {
                    val lj = doc.lines[j]
                    if (lj.length > MAX_LINE_CHARS) break
                    val li = FM_LIST_ITEM.find(lj) ?: break
                    var g = li.groupValues[2].trimEnd()
                    var col = li.groupValues[1].length
                    if (g.length >= 2 && (g[0] == '"' || g[0] == '\'') && g.endsWith(g[0])) { g = g.substring(1, g.length - 1); col++ }
                    if (g.isEmpty()) break
                    items.add(Item(g, j, col))
                }
            }
            for (it in items) {
                if (it.g.isEmpty() || it.g == "**" || it.g == "**/*" || it.g == "*") continue
                if (++stats.globs > MAX_GLOBS_PER_FILE) break
                if (looksIgnoredOrGenerated(ctx, normalizeRel(posixJoin(scope, it.g.replace(Regex("^\\.?/"), ""))) ?: it.g)) continue
                if (!ctx.globMatchesAny(it.g, scope, stats.budget)) {
                    emitPath(mkDiag("CD002", Severity.INFO, rel, it.line, it.col, it.col + it.g.length,
                        "${m.groupValues[1]} pattern \"${it.g}\" matches no files in the repo, so this rule never applies.", data = mapOf("kind" to "glob", "glob" to it.g)))
                }
            }
            i++
        }
    }

    // ---- CD008 size limit
    sizeFinding(rel, facts.kind, text, doc, limits)?.let { emit(it) }
    // ---- CD007 duplicated instructions inside this file
    for (d in duplicateLinesInFile(rel, doc)) emit(d)

    if (doc.longLines > 0) facts.longLines = doc.longLines
    if (doc.spansCapped) facts.spansCapped = true
    if (capped) out.add(mkDiag("CD002", Severity.INFO, rel, 0, 0, maxOf(1, doc.lines.firstOrNull()?.length ?: 0), "Stopped after $MAX_FINDINGS_PER_FILE findings; the rest of this file was not checked.", data = mapOf("kind" to "capped")))
    return FileResult(rel, out, facts, text, doc)
}

/** A one-click fix for a pinned version claim: replace just the number with the pinned major (or major.minor). */
private fun versionFix(lineText: String, startCol: Int, endCol: Int, verStr: String, src: VersionSource): Triple<Int, Int, String>? {
    val pin = src.ver ?: return null
    val claimed = verStr.split('.').size
    val newVer = pin.take(maxOf(1, minOf(claimed, pin.size))).joinToString(".")
    val idx = lineText.lastIndexOf(verStr, endCol - 1)
    if (idx < startCol || idx + verStr.length > endCol) return null
    return Triple(idx, idx + verStr.length, newVer)
}

internal fun splitOutsideBraces(t: String): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var st = 0
    for (i in t.indices) {
        when {
            t[i] == '{' -> depth++
            t[i] == '}' && depth > 0 -> depth--
            t[i] == ',' && depth == 0 -> { out.add(t.substring(st, i)); st = i + 1 }
        }
    }
    out.add(t.substring(st))
    return out
}

/* ------------------------------------------------------------------ */
/* Workspace analysis                                                  */
/* ------------------------------------------------------------------ */

fun findInstructionFiles(ctx: RepoContext): List<String> = ctx.files.filter { classifyInstructionFile(it) != null }

/** Analyze a workspace root synchronously. Runs git only for CD006 when options.stale is on. */
fun analyzeWorkspace(root: String, options: Options = Options(), context: RepoContext? = null): WorkspaceResult {
    val t0 = System.currentTimeMillis()
    val ctx = context ?: RepoContext.create(root)
    val ignore = options.ignoreRules.map { it.uppercase() }.toSet()
    val candidates = findInstructionFiles(ctx).toMutableList()
    for (abs in options.overrides.keys) {
        val r = relOf(ctx, abs) ?: continue
        if (classifyInstructionFile(r) != null && r !in candidates) candidates.add(r)
    }
    candidates.sort()
    val results = ArrayList<FileResult>()
    val files = ArrayList<String>()
    val skipped = ArrayList<SkippedFile>()
    var diags = ArrayList<Diagnostic>()
    val copied = ctx.looksLikeCopiedFolder()
    val deadline = if (options.budgetMs > 0) t0 + options.budgetMs else Long.MAX_VALUE
    for (rel in candidates) {
        if (System.currentTimeMillis() > deadline) { skipped.add(SkippedFile(rel, "not checked: the run used up its time budget")); continue }
        val abs = File(ctx.root, rel).path
        var text = options.overrides[abs]
        if (text == null) {
            val (t, reason) = ctx.readFresh(rel)
            if (reason != null) { skipped.add(SkippedFile(rel, reason)); continue }
            text = t ?: continue
        }
        if (text.length > MAX_INSTRUCTION_BYTES) { skipped.add(SkippedFile(rel, "larger than 1 MB")); continue }
        val r = try { analyzeText(ctx, rel, text, deadline, options.sizeLimits) } catch (e: Exception) { FileResult(rel, emptyList(), FileFacts(classifyInstructionFile(rel)), text, null) }
        files.add(rel)
        results.add(r)
        var ds: List<Diagnostic> = r.diagnostics
        val ps = r.facts.paths
        if (copied && ps != null && ps.missing >= 3 && ps.missing * 2 >= ps.checked) {
            ds = ds.filter { it.ruleId != "CD002" } + mkDiag("CD002", Severity.INFO, rel, 0, 0, maxOf(1, splitLines(text).firstOrNull()?.length ?: 0),
                "Not a project root: this folder is not a git repository root and has no manifest (package.json, pyproject.toml, go.mod, ...), and ${ps.missing} of ${ps.checked} referenced paths are missing. It looks like a copy of an instruction file, so path checks were skipped.",
                data = mapOf("kind" to "not-project-root"))
        }
        if (r.facts.timedOut) skipped.add(SkippedFile(rel, "only partly checked: the run used up its time budget", true))
        if (r.facts.spansCapped) skipped.add(SkippedFile(rel, "more than $MAX_SPANS_PER_FILE code spans or links; the rest were not checked", true))
        if (r.facts.longLines > 0) skipped.add(SkippedFile(rel, "${r.facts.longLines} line(s) longer than $MAX_LINE_CHARS characters were not checked", true))
        diags.addAll(ds)
    }
    // sparse checkout without git to ask: drop path findings (precision over recall)
    if (ctx.sparse) diags = ArrayList(diags.filter { it.data["sparse"] != true })
    if ("CD005" !in ignore) diags.addAll(crossFileConflicts(results))
    if ("CD007" !in ignore) diags.addAll(crossFileDuplicates(results))
    if (options.stale && "CD006" !in ignore && ctx.gitRoot != null) diags.addAll(Git.staleness(ctx, results, options))
    val filtered = diags.filter { it.ruleId !in ignore && (it.ruleId != "CD006" || options.stale) }
    return WorkspaceResult(ctx.root, files, skipped, sortDiags(filtered), System.currentTimeMillis() - t0)
}

internal fun relOf(ctx: RepoContext, abs: String): String? {
    val root = File(ctx.root).absoluteFile.normalize().toPath()
    val p = File(abs).absoluteFile.normalize().toPath()
    if (!p.startsWith(root)) return null
    val r = root.relativize(p).toString().replace(File.separatorChar, '/')
    return if (r.isEmpty() || r.startsWith("..")) null else r
}

internal fun sortDiags(diags: List<Diagnostic>): List<Diagnostic> =
    diags.sortedWith(compareBy<Diagnostic> { it.file }.thenBy { it.line }.thenBy { it.column }.thenBy { it.ruleId })

internal fun crossFileConflicts(results: List<FileResult>): List<Diagnostic> {
    val out = ArrayList<Diagnostic>()
    val groups = LinkedHashMap<String, MutableList<FileResult>>()
    for (r in results) {
        val k = r.facts.kind
        if (k == null || k == "SKILL.md" || k == "copilot-scoped") continue
        groups.getOrPut(scopeDirOf(r.rel)) { ArrayList() }.add(r)
    }
    for (list in groups.values) {
        for (a in list.indices) for (b in list.indices) {
            if (a == b) continue
            val aR = list[a]
            val bR = list[b]
            for ((verb, cmdsB) in bR.facts.verbs) {
                val cmdsA = aR.facts.verbs[verb]
                if (cmdsA.isNullOrEmpty() || cmdsB.isEmpty()) continue
                val keysA = cmdsA.map { it.key }.toSet()
                if (cmdsB.any { it.key in keysA }) continue
                val first = cmdsB[0]
                val verbName = verb.split('\u0000')[0]
                out.add(mkDiag("CD005", Severity.INFO, bR.rel, first.line, first.col, first.endCol,
                    "${baseName(bR.rel)} says `${first.display}` to $verbName, but ${aR.rel} says `${cmdsA[0].display}`. Agents get different instructions.",
                    related = listOf(Related(aR.rel, cmdsA[0].line, cmdsA[0].col, cmdsA[0].endCol, "${aR.rel}: `${cmdsA[0].display}`")),
                    data = mapOf("kind" to "conflict", "verb" to verbName)))
            }
        }
    }
    val seen = HashSet<String>()
    return out.filter { seen.add(it.file + ":" + it.line + ":" + it.column) }
}

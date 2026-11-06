package zovo.contextdrift.engine

internal val SHELL_LANGS = setOf("", "sh", "bash", "shell", "zsh", "fish", "console", "shell-session", "sh-session", "shellsession", "terminal", "cmd", "bat", "powershell", "ps", "ps1", "pwsh", "ksh", "command", "commands", "cli")
internal val PROMPT_LANGS = setOf("console", "shell-session", "sh-session", "shellsession", "terminal")

internal class Span(
    val text: String, val line: Int, val col: Int, val endCol: Int, val heading: String,
    var lineText: String = "", var prevText: String = "",
)

internal class FenceBlock(val ch: Char, val len: Int, val lang: String, val start: Int, var end: Int, var hasPrompt: Boolean = false, var creates: Boolean = false)
internal class FenceLine(val text: String, val line: Int, val lang: String, val heading: String, val afterCreate: Boolean, val block: FenceBlock)
internal class ProseLine(val text: String, val masked: String, val line: Int, val heading: String, val prevText: String)

/** Disabled rules: null set = all rules. */
internal class Disabled {
    var fileAll = false
    val fileIds = HashSet<String>()
    var hasFile = false
    val lines = HashMap<Int, MutableSet<String>?>() // value null = all
}

internal class Doc(val lines: List<String>) {
    val inline = ArrayList<Span>()
    val fence = ArrayList<FenceLine>()
    val fences = ArrayList<FenceBlock>()
    val links = ArrayList<Span>()
    val prose = ArrayList<ProseLine>()
    var frontmatter: IntRange? = null // start..end (end = closing line)
    var longLines = 0
    var spansCapped = false
    val disabled = Disabled()
    val pendingNext = ArrayList<Pair<Int, Set<String>?>>()
}

private val DIRECTIVE_RE = Regex("context-drift-disable(-next-line|-line)?(?![\\w-])([^\\n]*)")
private val RULE_ID = Regex("CD\\d{3}")
// The destination run is atomic: giving back characters can never help, and it stops at "](".
private val LINK_RE = Regex("!?\\[((?:[^\\[\\]\\\\]|\\\\.)*)\\]\\(\\s*(<[^>\\n]*>|(?>(?:[^\\s)\\]]|\\](?!\\())+))(?:\\s+(?:\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?\\s*\\)")
private val REF_LINK = Regex("^\\s{0,3}\\[[^\\]]+\\]:\\s*(<[^>]*>|\\S+)")
private val FM_OPEN = Regex("^---\\s*$")
private val FM_CLOSE = Regex("^(---|\\.\\.\\.)\\s*$")
private val FM_COMMENT = Regex("^\\s*#")
private val FENCE_CLOSE = Regex("^\\s*(`{3,}|~{3,})\\s*$")
private val HEREDOC = Regex("<<-?\\s*['\"]?([A-Za-z_][\\w]*)['\"]?")
private val CREATES = Regex("\\b(?:mkdir|clone|init|create|new|degit|scaffold|generate|startproject|startapp)\\b")
private val PROMPT_LINE = Regex("^\\s*(?:\\$|❯)\\s")
private val FENCE_OPEN = Regex("^(\\s*)(`{3,}|~{3,})\\s*([^\\s`{]*)")
private val HEADING = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
private val NEW_ITEM = Regex("^\\s*(?:[-*+]\\s|\\d+[.)]\\s|#|\\||>)")

private fun applyDirective(doc: Doc, text: String, line: Int) {
    val m = DIRECTIVE_RE.find(text) ?: return
    val ids = RULE_ID.findAll(m.groupValues[2]).map { it.value }.toList()
    val set: Set<String>? = if (ids.isNotEmpty()) ids.toSet() else null
    when (m.groups[1]?.value) {
        "-next-line" -> doc.pendingNext.add(line to set)
        "-line" -> addDisabled(doc, line, set)
        else -> {
            if (set == null) doc.disabled.fileAll = true else doc.disabled.fileIds.addAll(set)
            doc.disabled.hasFile = true
        }
    }
}

private fun addDisabled(doc: Doc, line: Int, set: Set<String>?) {
    val lines = doc.disabled.lines
    if (lines.containsKey(line) && lines[line] == null) return
    if (set == null) { lines[line] = null; return }
    val s = lines[line] ?: HashSet()
    s.addAll(set)
    lines[line] = s
}

internal fun isDisabled(doc: Doc, ruleId: String, line: Int): Boolean {
    val d = doc.disabled
    if (d.fileAll || ruleId in d.fileIds) return true
    if (!d.lines.containsKey(line)) return false
    val l = d.lines[line]
    return l == null || ruleId in l
}

/** Backtick runs of a line, grouped by length. */
private fun backtickRuns(work: String): Map<Int, List<Int>> {
    val byLen = HashMap<Int, MutableList<Int>>()
    var k = 0
    while (k < work.length) {
        if (work[k] != '`') { k++; continue }
        var n = 0
        while (k + n < work.length && work[k + n] == '`') n++
        byLen.getOrPut(n) { ArrayList() }.add(k)
        k += n
    }
    return byLen
}

private fun firstRunAtOrAfter(arr: List<Int>?, pos: Int): Int {
    if (arr == null) return -1
    var lo = 0
    var hi = arr.size
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (arr[mid] < pos) lo = mid + 1 else hi = mid }
    return if (lo < arr.size) arr[lo] else -1
}

/** Next "<!--" at or after from that is not inside an inline code span of the raw line. */
private fun nextCommentStart(line: String, from: Int, cache: Array<IntArray?>): Int {
    var p = line.indexOf("<!--", from)
    if (p < 0 || !line.contains('`')) return p
    if (cache[0] == null) {
        val spans = ArrayList<Int>()
        val runs = backtickRuns(line)
        var k = 0
        while (k < line.length) {
            if (line[k] == '\\') { k += 2; continue }
            if (line[k] != '`') { k++; continue }
            var n = 0
            while (k + n < line.length && line[k + n] == '`') n++
            val close = firstRunAtOrAfter(runs[n], k + n)
            if (close < 0) { k += n; continue }
            spans.add(k); spans.add(close + n)
            k = close + n
        }
        cache[0] = spans.toIntArray()
    }
    val sp = cache[0]!!
    while (p >= 0) {
        var lo = 0
        var hi = sp.size / 2
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (sp[mid * 2] <= p) lo = mid + 1 else hi = mid }
        val idx = lo - 1
        p = if (idx >= 0 && p < sp[idx * 2 + 1]) line.indexOf("<!--", sp[idx * 2 + 1]) else return p
    }
    return p
}

private fun decodeLinkTarget(t: String): String = t

internal fun scanDocument(text: String): Doc {
    val lines = splitLines(text)
    val doc = Doc(lines)
    var start = 0
    if (lines.isNotEmpty() && FM_OPEN.matches(lines[0])) {
        for (i in 1 until minOf(lines.size, 400)) {
            if (FM_CLOSE.matches(lines[i])) { doc.frontmatter = 0..i; start = i + 1; break }
        }
        doc.frontmatter?.let { fm ->
            for (i in 1 until fm.last) {
                val l = lines[i]
                if (l.length <= MAX_LINE_CHARS && FM_COMMENT.containsMatchIn(l)) applyDirective(doc, l, i)
            }
        }
    }

    var fence: FenceBlock? = null
    var inComment = false
    var commentText = StringBuilder()
    var commentLine = 0
    var heading = ""
    var heredoc: String? = null
    var prevProse = ""
    for (i in start until lines.size) {
        var line = lines[i]
        if (line.length > MAX_LINE_CHARS) { doc.longLines++; line = "" }
        if (fence != null) {
            val close = FENCE_CLOSE.find(line)
            if (close != null && close.groupValues[1][0] == fence.ch && close.groupValues[1].length >= fence.len) {
                fence.end = i; fence = null; heredoc = null; continue
            }
            if (heredoc != null) { if (line.trim() == heredoc) heredoc = null; continue }
            val hd = HEREDOC.find(line)
            if (CREATES.containsMatchIn(line)) fence.creates = true
            val entry = FenceLine(line, i, fence.lang, heading, fence.creates, fence)
            if (PROMPT_LINE.containsMatchIn(line)) fence.hasPrompt = true
            doc.fence.add(entry)
            if (hd != null) heredoc = hd.groupValues[1]
            continue
        }
        val open = FENCE_OPEN.find(line)
        if (open != null && !(open.groupValues[2][0] == '`' && line.substring(open.groupValues[1].length + open.groupValues[2].length).contains('`'))) {
            val lang = open.groupValues[3].lowercase().replace(Regex("^\\{?\\.?"), "")
            fence = FenceBlock(open.groupValues[2][0], open.groupValues[2].length, lang, i, lines.size)
            doc.fences.add(fence)
            prevProse = ""
            continue
        }
        // strip html comments (keep columns); directives count only inside real comments
        val work = StringBuilder()
        var j = 0
        val spanCache = arrayOfNulls<IntArray>(1)
        while (j < line.length) {
            if (inComment) {
                val e = line.indexOf("-->", j)
                if (e < 0) {
                    commentText.append(line, j, line.length).append('\n')
                    repeat(line.length - j) { work.append(' ') }
                    j = line.length
                } else {
                    commentText.append(line, j, e)
                    repeat(e + 3 - j) { work.append(' ') }
                    j = e + 3
                    inComment = false
                    applyDirective(doc, commentText.toString(), commentLine)
                }
            } else {
                val s2 = nextCommentStart(line, j, spanCache)
                if (s2 < 0) { work.append(line, j, line.length); j = line.length }
                else {
                    work.append(line, j, s2); j = s2 + 4; work.append("    ")
                    inComment = true; commentText = StringBuilder(); commentLine = i
                }
            }
        }
        val w = work.toString()
        HEADING.find(w)?.let { heading = it.groupValues[2].trim() }
        val spanRoom = doc.inline.size < MAX_SPANS_PER_FILE
        if (!spanRoom && w.contains('`')) doc.spansCapped = true
        val runs = if (spanRoom && w.contains('`')) backtickRuns(w) else null
        val masked = StringBuilder()
        var k = 0
        var last = 0
        val spans = ArrayList<Span>()
        while (runs != null && k < w.length) {
            val c = w[k]
            if (c == '\\' && k + 1 < w.length) { k += 2; continue }
            if (c != '`') { k++; continue }
            var n = 0
            while (k + n < w.length && w[k + n] == '`') n++
            val close = firstRunAtOrAfter(runs[n], k + n)
            if (close < 0) { k += n; continue }
            var cs = k + n
            var ce = close
            var content = w.substring(cs, ce)
            if (content.length > 2 && content[0] == ' ' && content[content.length - 1] == ' ' && content.isNotBlank()) {
                cs++; ce--; content = w.substring(cs, ce)
            }
            spans.add(Span(content, i, cs, ce, heading))
            masked.append(w, last, k)
            repeat(close + n - k) { masked.append(' ') }
            k = close + n
            last = k
        }
        masked.append(w, last, w.length)
        val m = masked.toString()
        val newItem = NEW_ITEM.containsMatchIn(w)
        val prev = if (newItem) "" else prevProse
        for (sp in spans) { sp.lineText = w; sp.prevText = prev; doc.inline.add(sp) }
        if (m.contains("](")) {
            for (lm in LINK_RE.findAll(m)) {
                if (doc.links.size >= MAX_SPANS_PER_FILE) { doc.spansCapped = true; break }
                var target = lm.groupValues[2]
                var tcol = lm.range.first + lm.value.indexOf(target, lm.groupValues[1].length + 2)
                if (target.startsWith("<")) { target = target.substring(1, target.length - 1); tcol++ }
                doc.links.add(Span(decodeLinkTarget(target), i, tcol, tcol + target.length, heading, w, prev))
            }
        }
        REF_LINK.find(m)?.let { ref ->
            var target = ref.groupValues[1]
            var tcol = ref.value.lastIndexOf(target)
            if (target.startsWith("<")) { target = target.substring(1, target.length - 1); tcol++ }
            doc.links.add(Span(target, i, tcol, tcol + target.length, heading, w, prev))
        }
        doc.prose.add(ProseLine(w, m, i, heading, prev))
        prevProse = if (w.isNotBlank()) w else ""
    }
    // "-next-line" directives: the next non-blank line; above a code fence they cover the whole block
    for ((from, set) in doc.pendingNext) {
        var n = from + 1
        while (n < lines.size && lines[n].isBlank()) n++
        val f = doc.fences.firstOrNull { it.start == n }
        if (f != null) for (l in f.start..minOf(f.end, lines.size - 1)) addDisabled(doc, l, set)
        else addDisabled(doc, n, set)
    }
    return doc
}

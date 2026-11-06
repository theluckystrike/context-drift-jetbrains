package zovo.contextdrift.engine

private val VER = Regex("""^\s*v?(\d+)(?:\.(\d+|[xX*]))?(?:\.(\d+|[xX*]))?""")
private val DIGITS = Regex("""^\d+$""")

internal fun parseVer(s: String): List<Int>? {
    val m = VER.find(s) ?: return null
    val out = arrayListOf(m.groupValues[1].toBigInteger().min(Int.MAX_VALUE.toBigInteger()).toInt())
    val g2 = m.groups[2]?.value
    if (g2 != null) { if (DIGITS.matches(g2)) out.add(g2.toIntSafe()) else return out }
    val g3 = m.groups[3]?.value
    if (g3 != null && DIGITS.matches(g3)) out.add(g3.toIntSafe())
    return out
}

private fun String.toIntSafe(): Int = toBigInteger().min(Int.MAX_VALUE.toBigInteger()).toInt()

internal fun cmpVer(a: List<Int>, b: List<Int>, len: Int? = null): Int {
    val n = len ?: maxOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return if (x < y) -1 else 1
    }
    return 0
}

internal fun prefixEq(a: List<Int>, b: List<Int>): Boolean {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) if (a[i] != b[i]) return false
    return true
}

private val COMP = Regex("""^(\^|~=|~|>=|<=|>|<|===|==|=|!=)?\s*v?(.+)$""")

internal fun satisfiesComparator(v: List<Int>, comp0: String): Boolean {
    val comp = comp0.trim()
    if (comp.isEmpty() || comp == "*" || comp == "x" || comp == "X") return true
    val m = COMP.find(comp) ?: return true
    val op = m.groups[1]?.value ?: ""
    val r = parseVer(m.groupValues[2]) ?: return true
    return when (op) {
        "", "=", "==", "===" -> prefixEq(v, r)
        "!=" -> !prefixEq(v, r)
        ">=" -> cmpVer(v, r) >= 0
        "<=" -> cmpVer(v, r, r.size) <= 0
        ">" -> cmpVer(v, r, r.size) > 0
        "<" -> cmpVer(v, r) < 0
        "^" -> {
            if (cmpVer(v, r) < 0) false
            else if (r[0] > 0 || r.size == 1) v[0] == r[0]
            else if (r.getOrElse(1) { 0 } > 0 || r.size == 2) v[0] == 0 && v.getOrNull(1) == r.getOrNull(1)
            else prefixEq(v, r)
        }
        "~" -> {
            if (cmpVer(v, r) < 0) false
            else if (r.size == 1) v[0] == r[0]
            else v[0] == r[0] && v.getOrNull(1) == r.getOrNull(1)
        }
        "~=" -> {
            if (cmpVer(v, r) < 0) false
            else prefixEq(v, r.subList(0, maxOf(1, r.size - 1)))
        }
        else -> true
    }
}

private val HYPHEN_RANGE = Regex("""^v?([\d.xX*]+)\s+-\s+v?([\d.xX*]+)$""")
private val OP_SPACE = Regex("""(>=|<=|~=|==|!=|>|<|\^|~|=)\s+""")
private val COMP_SPLIT = Regex("""[\s,]+""")

internal fun satisfiesRange(v: List<Int>, range: String): Boolean = range.split("||").any { alt0 ->
    val alt = alt0.trim()
    val hy = HYPHEN_RANGE.find(alt)
    if (hy != null) {
        val lo = parseVer(hy.groupValues[1])
        val hi = parseVer(hy.groupValues[2])
        lo != null && hi != null && cmpVer(v, lo) >= 0 && cmpVer(v, hi, hi.size) <= 0
    } else {
        alt.replace(OP_SPACE, "$1").split(COMP_SPLIT).filter { it.isNotEmpty() }.all { satisfiesComparator(v, it) }
    }
}

private val NUMS = Regex("""\d+(?:\.\d+){0,2}""")

/** Is there a version with prefix claim that satisfies range? */
internal fun claimFitsRange(claim: List<Int>, range: String): Boolean {
    val cands = arrayListOf((claim + listOf(0, 0, 0)).take(3), (claim + listOf(999, 999, 999)).take(3))
    for (n in NUMS.findAll(range)) {
        val p = parseVer(n.value)
        if (p != null && prefixEq(p, claim)) cands.add((p + listOf(0, 0)).take(3))
    }
    return cands.any { satisfiesRange(it, range) }
}

internal val NODE_LTS = mapOf("argon" to 4, "boron" to 6, "carbon" to 8, "dubnium" to 10, "erbium" to 12, "fermium" to 14, "gallium" to 16, "hydrogen" to 18, "iron" to 20, "jod" to 22, "krypton" to 24)

internal fun firstLine(t: String?): String {
    for (l in splitLines(t ?: "")) {
        val s = l.replace(Regex("#.*"), "").trim()
        if (s.isNotEmpty()) return s
    }
    return ""
}

/** Version sources for a language, nearest to dir. */
internal fun versionSources(ctx: RepoContext, lang: String, dir: String): List<VersionSource> = ctx.memo("vs:$lang:$dir") {
    val out = ArrayList<VersionSource>()
    val toolVersions = ctx.nearestFile(dir, listOf(".tool-versions"))
    fun tv(name: String) {
        if (toolVersions == null) return
        val re = Regex("^\\s*" + Regex.escape(name) + "\\s+(\\S+)")
        for (l in splitLines(ctx.readRel(toolVersions) ?: "")) {
            val m = re.find(l) ?: continue
            val v = parseVer(m.groupValues[1]) ?: continue
            out.add(VersionSource("pin", m.groupValues[1], toolVersions, v))
        }
    }
    when (lang) {
        "node" -> {
            for (n in listOf(".nvmrc", ".node-version")) {
                val f = ctx.nearestFile(dir, listOf(n)) ?: continue
                var s = firstLine(ctx.readRel(f)).replace(Regex("^node/", RegexOption.IGNORE_CASE), "")
                val lts = Regex("^lts/(\\w+)", RegexOption.IGNORE_CASE).find(s)
                if (lts != null) NODE_LTS[lts.groupValues[1].lowercase()]?.let { s = it.toString() }
                val v = parseVer(s)
                if (v != null && Regex("^v?\\d").containsMatchIn(s)) out.add(VersionSource("pin", s, f, v))
            }
            for (p in ctx.pickPackages(dir).take(1)) {
                if (p.invalid) continue
                val e = (p.json["engines"] as? Map<*, *>)?.get("node")
                if (e is String && Regex("\\d").containsMatchIn(e)) out.add(VersionSource("range", e, p.rel + " engines.node"))
                val vo = (p.json["volta"] as? Map<*, *>)?.get("node")
                if (vo is String) parseVer(vo)?.let { out.add(VersionSource("pin", vo, p.rel + " volta.node", it)) }
            }
            tv("nodejs")
            tv("node")
        }
        "python" -> {
            ctx.nearestFile(dir, listOf(".python-version"))?.let { f ->
                val pins = splitLines(ctx.readRel(f) ?: "").map { it.replace(Regex("#.*"), "").trim() }.filter { Regex("^\\d").containsMatchIn(it) }
                if (pins.isNotEmpty()) out.add(VersionSource("pins", pins.joinToString(", "), f, vers = pins.mapNotNull { parseVer(it) }))
            }
            ctx.nearestFile(dir, listOf("runtime.txt"))?.let { rt ->
                val m = Regex("python-(\\d+\\.\\d+(?:\\.\\d+)?)").find(ctx.readRel(rt) ?: "")
                if (m != null) out.add(VersionSource("pin", m.groupValues[1], rt, parseVer(m.groupValues[1])))
            }
            ctx.nearestFile(dir, listOf("pyproject.toml"))?.let { pp ->
                val toml = ctx.readToml(pp)
                val rp = toml?.get("project", "requires-python")
                if (rp is String && Regex("\\d").containsMatchIn(rp)) out.add(VersionSource("range", rp, "$pp requires-python"))
                val pv = toml?.get("tool.poetry.dependencies", "python")
                if (pv is String && Regex("\\d").containsMatchIn(pv)) out.add(VersionSource("range", pv, "$pp tool.poetry.dependencies.python"))
            }
            tv("python")
        }
        "go" -> {
            ctx.nearestFile(dir, listOf("go.mod"))?.let { gm ->
                val t = ctx.readRel(gm) ?: ""
                val m = Regex("^go\\s+(\\d+\\.\\d+(?:\\.\\d+)?)\\s*$", RegexOption.MULTILINE).find(t)
                if (m != null) out.add(VersionSource("range", ">=" + m.groupValues[1], "$gm go directive"))
                val tc = Regex("^toolchain\\s+go(\\d+\\.\\d+(?:\\.\\d+)?)", RegexOption.MULTILINE).find(t)
                if (tc != null) out.add(VersionSource("pin", tc.groupValues[1], "$gm toolchain", parseVer(tc.groupValues[1])))
            }
            tv("golang")
        }
    }
    out
}

internal class Claim(val vers: List<List<Int>>, val min: Boolean)

internal fun claimConsistent(claim: Claim, src: VersionSource): Boolean = claim.vers.any { c ->
    if (src.kind == "pin" || src.kind == "pins") {
        val pins = if (src.kind == "pin") listOfNotNull(src.ver) else src.vers
        pins.any { p -> if (claim.min) cmpVer(p, c, minOf(c.size, p.size)) >= 0 else prefixEq(p, c) }
    } else claimFitsRange(c, src.value)
}

internal val LANG_LABEL = mapOf("node" to "Node", "python" to "Python", "go" to "Go")
private const val MIN_SUFFIX = """(\s*(?:\+|(?:or|and)\s+(?:newer|later|above|higher|up)|\(?(?:newer|later|or later|or newer|and up)\)?))"""

internal class VersionPattern(val lang: String, val re: Regex, val cmd: Boolean = false, val minMajor: Int = 0)

internal val VERSION_PATTERNS = listOf(
    VersionPattern("node", Regex("""\b(?:Node(?:\.?js)?|NODE(?:\.?JS)?|node(?:\.?js)(?=\W)|node(?=\s+version))(?![\w-])(?:\s*(?:v(?=\d)|version(?=\s)|@))?(?:\s*(>=|=>|≥|\^|~|>))?\s*v?(\d{1,2}(?:\.(?:\d+|x))?(?:\.(?:\d+|x))?)(?![\d.]*\w)""" + MIN_SUFFIX + "?"), minMajor = 4),
    VersionPattern("node", Regex("""\bnvm\s+(?:use|install|alias\s+default)\s+v?(\d{1,2}(?:\.\d+){0,2})\b"""), cmd = true),
    VersionPattern("python", Regex("""\b(?:c?[Pp]ython|PYTHON)(?:\s*(?:v(?=\d)|version(?=\s)))?(?:\s*(>=|=>|≥|~=|\^|>|==))?\s*([23]\.\d{1,2}(?:\.(?:\d+|x|\*))?)(?![\d.]*\w)""" + MIN_SUFFIX + "?")),
    VersionPattern("python", Regex("""\bpyenv\s+(?:install|local|global|shell)\s+([23]\.\d{1,2}(?:\.\d+)?)\b"""), cmd = true),
    VersionPattern("python", Regex("""(?:--python[= ]|\buv\s+python\s+(?:install|pin)\s+|\buv\s+venv\s+-p\s+)([23]\.\d{1,2}(?:\.\d+)?)\b"""), cmd = true),
    VersionPattern("go", Regex("""\b(?:Go|golang|Golang|GoLang|GO)(?=\s)(?:\s*(?:v(?=\d)|version(?=\s)))?(?:\s*(>=|≥))?\s*(1\.\d{1,2}(?:\.\d+)?)(?![\d.]*\w)""" + MIN_SUFFIX + "?")),
)

internal val VERSION_NOT_A_CLAIM = Regex("""\b(?:no longer|dropped|deprecated|removed|end[- ]of[- ]life|eol|unsupported|not supported|legacy|old|older|previously|formerly|was|were|used to|upgrade[ds]? from|migrat\w* from|before|until|below|less than|prior to|not|never|avoid|don'?t|doesn'?t|instead of|added in|introduced in|available (?:in|since)|since|new in|changed in|fixed in|as of|ci (?:runs|uses|pins|tests)|(?:docker|container|base) image|image|matrix|tested (?:on|with|against)|e\.g\.?|for example|such as|like)\b[^.;!?]*$""", RegexOption.IGNORE_CASE)
internal val VERSION_TAIL_NOT_A_CLAIM = Regex("""^\s*\((?:[^)]{0,60}\b(?:root|parent|workspace|monorepo)\b)|^\s*\)?\s*(?:is|are|was|were|has been|have been)?\s*(?:no longer|dropped|deprecated|removed|EOL|end[- ]of[- ]life|unsupported|not supported|too old|is too old|too new|or older|and older|and below)\b|^[^.;!?]{0,40}\b(?:too old|too new|not supported|unsupported)\b""", RegexOption.IGNORE_CASE)
internal val MULTI_TAIL = Regex("""^\s*(?:,|/|\bor\b|\band\b|&|-|–|\bto\b|\bthrough\b)\s*v?(\d{1,2}(?:\.\d+)?)(?![\d.]*\w)""", RegexOption.IGNORE_CASE)

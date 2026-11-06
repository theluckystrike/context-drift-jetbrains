package zovo.contextdrift.engine

/*
 * Linear-time glob matching. Globs come from repository content, so they are never turned into regular
 * expressions. A glob is split into path segments; each segment is matched with the two-pointer wildcard
 * algorithm, and "**" segments with a dynamic program over (pattern segment, path segment).
 */
private const val MAX_GLOB_CHARS = 512
private const val MAX_GLOB_SEGMENTS = 64
private const val MAX_BRACE_EXPANSIONS = 32

internal class GlobBudgetExceeded : RuntimeException("glob work budget exceeded") {
    override fun fillInStackTrace(): Throwable = this
}

internal class Budget(var left: Int)

internal sealed class GTok {
    object Star : GTok()
    object Any : GTok()
    data class Lit(val c: Char) : GTok()
    class Cls(val neg: Boolean, val ranges: List<Pair<Int, Int>>) : GTok()
}

internal class CompiledSegment(val toks: List<GTok>) {
    val literal = toks.all { it is GTok.Lit }
    val text: String? = if (literal) toks.joinToString("") { (it as GTok.Lit).c.toString() } else null
}

/** A path segment pattern, or null for "**". */
internal typealias Seg = CompiledSegment?

internal class CompiledGlob(val alts: List<List<Seg>>)

private fun expandBraces(glob: String, out: MutableList<String>, depth: Int): Boolean {
    if (out.size > MAX_BRACE_EXPANSIONS || depth > 8) return false
    var open = -1
    var level = 0
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        if (c == '\\') { i += 2; continue }
        if (c == '{') {
            if (level == 0) open = i
            level++
        } else if (c == '}' && level > 0) {
            level--
            if (level == 0) {
                val body = glob.substring(open + 1, i)
                val alts = ArrayList<String>()
                var l2 = 0
                var st = 0
                var j = 0
                while (j < body.length) {
                    val d = body[j]
                    if (d == '\\') { j += 2; continue }
                    if (d == '{') l2++ else if (d == '}') l2-- else if (d == ',' && l2 == 0) {
                        alts.add(body.substring(st, j)); st = j + 1
                    }
                    j++
                }
                alts.add(body.substring(minOf(st, body.length)))
                if (alts.size < 2) { i++; continue }
                val pre = glob.substring(0, open)
                val post = glob.substring(i + 1)
                for (a in alts) if (!expandBraces(pre + a + post, out, depth + 1)) return false
                return true
            }
        }
        i++
    }
    out.add(glob)
    return out.size <= MAX_BRACE_EXPANSIONS
}

private fun compileSegment(seg: String): CompiledSegment {
    val toks = ArrayList<GTok>()
    var i = 0
    while (i < seg.length) {
        val c = seg[i]
        when {
            c == '*' -> if (toks.isEmpty() || toks.last() !is GTok.Star) toks.add(GTok.Star)
            c == '?' -> toks.add(GTok.Any)
            c == '[' -> {
                val end = if (i + 2 <= seg.length) seg.indexOf(']', i + 2) else -1
                if (end < 0) { toks.add(GTok.Lit(c)); i++; continue }
                var body = seg.substring(i + 1, end)
                var neg = false
                if (body.startsWith("!") || body.startsWith("^")) { neg = true; body = body.substring(1) }
                val ranges = ArrayList<Pair<Int, Int>>()
                var k = 0
                while (k < body.length) {
                    if (k + 1 < body.length && body[k + 1] == '-' && k + 2 < body.length) {
                        ranges.add(body[k].code to body[k + 2].code); k += 3
                    } else {
                        ranges.add(body[k].code to body[k].code); k++
                    }
                }
                toks.add(GTok.Cls(neg, ranges))
                i = end
            }
            c == '\\' && i + 1 < seg.length -> { i++; toks.add(GTok.Lit(seg[i])) }
            else -> toks.add(GTok.Lit(c))
        }
        i++
    }
    return CompiledSegment(toks)
}

private fun tokMatches(tok: GTok, ch: Char): Boolean = when (tok) {
    is GTok.Lit -> tok.c == ch
    is GTok.Any -> true
    is GTok.Cls -> {
        val code = ch.code
        val hit = tok.ranges.any { code >= it.first && code <= it.second }
        if (tok.neg) !hit else hit
    }
    GTok.Star -> true
}

private fun segmentMatch(cs: CompiledSegment, s: String, budget: Budget?): Boolean {
    if (cs.literal) return cs.text == s
    val toks = cs.toks
    var ti = 0
    var si = 0
    var starTi = -1
    var starSi = 0
    while (si < s.length) {
        if (budget != null && --budget.left < 0) throw GlobBudgetExceeded()
        if (ti < toks.size && toks[ti] !is GTok.Star && tokMatches(toks[ti], s[si])) { ti++; si++ }
        else if (ti < toks.size && toks[ti] is GTok.Star) { starTi = ti++; starSi = si }
        else if (starTi >= 0) { ti = starTi + 1; si = ++starSi }
        else return false
    }
    while (ti < toks.size && toks[ti] is GTok.Star) ti++
    return ti == toks.size
}

/** Compiles a glob into alternatives of segment lists. Returns null when it is too large to check. */
internal fun compileGlob(glob: String): CompiledGlob? {
    if (glob.length > MAX_GLOB_CHARS) return null
    val alts = ArrayList<String>()
    if (!expandBraces(glob, alts, 0)) return null
    val out = ArrayList<List<Seg>>()
    for (a in alts) {
        val segs = ArrayList<Seg>()
        var lastStar = false
        for (raw in a.split('/')) {
            if (raw.isEmpty()) continue
            if (raw == "**") {
                if (!lastStar) segs.add(null)
                lastStar = true
                continue
            }
            lastStar = false
            segs.add(compileSegment(raw))
        }
        if (segs.size > MAX_GLOB_SEGMENTS) return null
        out.add(segs)
    }
    return CompiledGlob(out)
}

private fun segsMatch(psegs: List<Seg>, ssegs: List<String>, budget: Budget?): Boolean {
    val pn = psegs.size
    val sn = ssegs.size
    var next = BooleanArray(sn + 1)
    next[sn] = true
    for (p in pn - 1 downTo 0) {
        val cur = BooleanArray(sn + 1)
        val ps = psegs[p]
        for (s in sn downTo 0) {
            cur[s] = if (ps == null) next[s] || (s < sn && cur[s + 1])
            else s < sn && next[s + 1] && segmentMatch(ps, ssegs[s], budget)
        }
        next = cur
    }
    return next[0]
}

/** Does the compiled glob match the posix path p (whole path)? */
internal fun globMatch(compiled: CompiledGlob?, p: String, budget: Budget? = null): Boolean {
    if (compiled == null) return false
    val ssegs = p.split('/').filter { it.isNotEmpty() }
    for (alt in compiled.alts) if (segsMatch(alt, ssegs, budget)) return true
    return false
}

private val IS_GLOB = Regex("[*?]|\\[[^\\]]+\\]|\\{[^{}]*,[^{}]*\\}")
internal fun isGlob(s: String) = IS_GLOB.containsMatchIn(s)

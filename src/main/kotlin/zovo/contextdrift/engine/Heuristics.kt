package zovo.contextdrift.engine

/* Negation and intent heuristics, ported unchanged from engine.js. These are word rules, not a grammar. */

private val I = RegexOption.IGNORE_CASE

internal val NEG_BEFORE = Regex("""(?:\b(?:no|not|never|don'?t|doesn'?t|do not|does not|isn'?t|aren'?t|wasn'?t|weren'?t|cannot|can'?t|won'?t|avoid|instead of|rather than|no longer|deprecated|removed|former|formerly|previously|legacy|replaced|stop using|stopped using|without|unlike|versus|vs\.?|nor|old|obsolete|used to|switched from|moved from|migrated from|renamed from|upgraded? from|migrating from|forbidden|banned|prohibited)\b|~~)[^.;!?]*$""", I)

internal val NEG_AFTER = Regex("""^(?:\s*(?:[\w-]+\s+){0,3}?(?:is|was|are|were|has been|have been|got)\s+(?:now\s+)?(?:removed|deleted|deprecated|gone|renamed|replaced|obsolete|retired|dropped|forbidden|banned)\b|\s*(?:\(|—|–|-|:)?\s*(?:deprecated|removed|old|legacy|obsolete|no longer)\b|\s*(?:[\w-]+\s+){0,3}?no longer\b|\s*(?:[\w-]+\s+){0,2}?(?:doesn'?t|does not|won'?t|will not)\s+(?:exist|work)\b|\s*~~)""", I)

internal val CREATE_BEFORE = Regex("""\b(?:create|creates|created|creating|add|adds|adding|generate[sd]?|generating|write|writes|written|writing|emit|emits|touch|mkdir|scaffold|will be|would be|e[._]g[._]?|eg|i[._]e[._]?|for example|for instance|such as|examples?|look for|check for|search for|detect|whether|any of|optionally|optional|if (?:you )?(?:have|need|want)|if present|if it exists|save (?:it )?(?:to|as)|rename(?:d)? (?:it )?to|move(?:d)? (?:it )?to|copy (?:it )?to|produces?|goes to|lands in|named|called|make sure|ensure|ensures|expects?|may|might|could|would|regenerate[sd]?|regenerating|rebuild|exports?|retained in|re-?create[sd]?|codegen|branch(?:es)?|sibling|defaults? to|for a starter with|naming|ending in|ends? (?:with|in)|end in|emitted|suffix(?:ed)?|prefix(?:ed)?|stored|saved|persisted|cached|remembered|logged|organi[sz]ed by|by category|rewrites?|overwrites?|replays? from|reads? back|submodules?|subpaths?|wildcard)\b[^.;!?]*$""", I)

private val CREATE_NEAR = Regex("""\b(?:new|like|into|either|output|outputs|locally)\b""", I)
private val SENTENCE_SPLIT = Regex("""[.;!?](?:\s|$)""")
private val WS = Regex("""\s+""")

internal fun createdBefore(b: String): Boolean {
    if (CREATE_BEFORE.containsMatchIn(b)) return true
    val words = b.split(SENTENCE_SPLIT).last().trim().split(WS)
    return CREATE_NEAR.containsMatchIn(words.takeLast(4).joinToString(" "))
}

internal val CREATE_AFTER = Regex("""^(?:[^.;!?]*?\b(?:will be|is|are|gets|get|be)\s+(?:auto-?)?(?:created|generated|written|produced|built|emitted|downloaded|installed)\b|\s*\(?\s*(?:generated|created|gitignored|ignored|local(?:ly)?|optional|if present|if it exists|if exists|not committed|untracked|when present|once created)\b|[^.;!?]*\bif (?:it |they )?(?:exists?|present)\b|[^.;!?]*\b(?:does ?n[o']t exist yet|not exist yet|to be created)\b|[^.;!?]*\b(?:folder|directory|dir|file)?\s*exists\b|[^.;!?]*\bgenerated\b|[^.;!?]*\b(?:git )?submodule\b|[^.;!?]*\bsubpath\b|\s*(?:alias|aliases)\b|\s*\(\s*(?:state|cache|runtime|data|log|logs|output|tmp|temp|user|home|config)\s+(?:dir|directory|folder|file)\b)""", I)

internal val EXAMPLE_HEADING = Regex("""\b(?:examples?|samples?|templates?|placeholders?|illustrations?|format|output)\b""", I)

internal val ELSEWHERE = Regex("""\b(?:(?:inside|in|from|within|under)\s+(?:its|the|each|that|a|every|their|your)\s+(?:own\s+)?(?:package|workspace|app|project|sub-?project|module|crate|playground|library)(?:\s+(?:dir(?:ectory)?|folder))?|per[- ](?:package|app|project|workspace)|(?:from|at|in)\s+(?:the\s+)?(?:workspace|monorepo|parent|outer|top-level)\s+root|outside\s+(?:this|the)\s+repo|upstream|(?:from|in)\s+the\s+(?:library|other\s+repo|parent\s+repo)|(?:separate|nested|other|another)\s+(?:nested\s+)?(?:repo|repository|package|project)|(?:library|upstream|template|vendor)'s)\b""", I)

internal const val CONTEXT_CHARS = 200

/** The current sentence before col (at most 200 chars), plus the previous line of the same paragraph. */
internal fun contextBefore(lineText: String, col: Int, prevText: String?): String {
    var start = maxOf(0, col - CONTEXT_CHARS)
    if (start > 0) {
        var ticks = 0
        for (i in 0 until start) if (lineText[i] == '`') ticks++
        if (ticks % 2 == 1) {
            val nb = lineText.indexOf('`', start)
            start = if (nb in 0 until col) nb + 1 else start
        }
    }
    var s = lineText.substring(minOf(start, lineText.length), minOf(col, lineText.length))
    if (!prevText.isNullOrEmpty() && s.length == col && s.length < CONTEXT_CHARS) {
        s = prevText.takeLast(CONTEXT_CHARS - s.length) + " " + s
    }
    val cut = maxOf(s.lastIndexOf(". "), s.lastIndexOf("; "), s.lastIndexOf("! "), s.lastIndexOf("? "))
    return (if (cut >= 0) s.substring(cut + 2) else s).replace(Regex("`+$"), "")
}

internal fun contextAfter(lineText: String, endCol: Int): String {
    if (endCol >= lineText.length) return ""
    return lineText.substring(endCol, minOf(lineText.length, endCol + CONTEXT_CHARS)).replace(Regex("^`+"), "")
}

private val SPAN_RE = Regex("`[^`]*`")
private val INNER_DOT = Regex("""\.(?=[^\s.])""")
/** Drops code spans, and turns dots inside words and numbers into "_" so they do not end a clause. */
internal fun stripSpans(t: String): String = SPAN_RE.replace(t, " ").replace(INNER_DOT, "_")

private val CLAUSE_PIVOT = Regex("""\b(?:until|unless|before|once|but|otherwise|then)\b""", I)
private val NEG_FLIP = Regex("""\b(?:without|forget(?:ting)?|fail(?:ing)?\s+to|skip(?:ping)?|omit(?:ting)?|ignor(?:e|ing))\b""", I)
private val CONTINUES = Regex("""^\s*(?:or|and|nor|which|that|who|where|whose|e_g|i_e|eg|ie|for example|for instance|such as|like|including|especially|etc|neither|not)\b""", I)
private val IMPERATIVE = Regex("""^\s*(?:(?:always|just|instead|then|also|first|only|please)\s+)?(?:run|use|call|invoke|execute|regenerate|rebuild|try|prefer|start|launch|type)\b""", I)
private val BARE_NEG = Regex("""^\s*(?:please\s+)?(?:do not|don'?t|never|not|no)(?:\s+ever)?\s*$""", I)
private val INTRO_ALT = Regex("""^\s*(?:instead of|unlike|rather than|as opposed to|compared (?:to|with))\b[^,]*$""", I)
private val FLIP_DIRECT = Regex("""^\s*(?:(?:first|also|always|ever)\s+)?(?:(?:to\s+)?(?:run|running|call|calling|invoke|invoking|execute|executing|use|using)\s+)?(?:(?:the|a)\s+)?$""", I)
private val AND_RE = Regex("""\band\b""", I)
private val TRAILING_WS = Regex("""\s+$""")

/** The clause that governs a span: the text after the last clause break before it. */
internal fun governingClause(b: String): String {
    var cut = lastEnd(CLAUSE_PIVOT, b)
    val comma = b.lastIndexOf(',')
    if (comma > cut) {
        val rest = b.substring(comma + 1)
        val head = b.substring(maxOf(cut, 0)).split(',')[0]
        if (!CONTINUES.containsMatchIn(rest) && (wordCount(rest) >= 2 || (IMPERATIVE.containsMatchIn(rest) && !BARE_NEG.containsMatchIn(head)))) cut = comma + 1
        else if (wordCount(rest) == 0 && INTRO_ALT.containsMatchIn(b.substring(maxOf(cut, 0), comma))) cut = comma + 1
    }
    val and = lastEnd(AND_RE, b)
    if (and > cut && !CONTINUES.containsMatchIn(b.substring(and))) {
        val rest = b.substring(and)
        if (wordCount(rest) >= 3 || (IMPERATIVE.containsMatchIn(rest) && wordCount(b.substring(maxOf(cut, 0), maxOf(maxOf(cut, 0), and - 3))) >= 4)) cut = and
    }
    return if (cut > 0) b.substring(cut) else b
}

/** True when the clause before a span negates it (a negation plus a flip word that governs the span is a requirement). */
internal fun negatedBefore(b: String): Boolean {
    val t = governingClause(b)
    if (!NEG_BEFORE.containsMatchIn(t)) return false
    var flip: MatchResult? = null
    for (m in NEG_FLIP.findAll(t)) flip = m
    if (flip != null && NEG_BEFORE.containsMatchIn(t.substring(0, flip.range.first).replace(TRAILING_WS, " x"))) {
        return !FLIP_DIRECT.containsMatchIn(t.substring(flip.range.last + 1))
    }
    return true
}

internal fun negated(lineText: String?, col: Int, endCol: Int, prevText: String?): Boolean {
    if (lineText.isNullOrEmpty()) return false
    val b = stripSpans(contextBefore(lineText, col, prevText))
    if (negatedBefore(b)) return true
    return NEG_AFTER.containsMatchIn(stripSpans(contextAfter(lineText, endCol)))
}

private val SENT_STOP = Regex("""[.;!?](?:\s|$)""")

/** Sentence around a span, for ELSEWHERE checks. */
internal fun sentenceAround(lineText: String, col: Int, endCol: Int, prevText: String?): String {
    val after = contextAfter(lineText, endCol)
    val stop = SENT_STOP.find(after)?.range?.first ?: -1
    return stripSpans(contextBefore(lineText, col, prevText) + " " + lineText.substring(minOf(col, lineText.length), minOf(endCol, lineText.length)) + " " + (if (stop >= 0) after.substring(0, stop) else after))
}

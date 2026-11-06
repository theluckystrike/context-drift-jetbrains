package zovo.contextdrift.engine

/** Tolerant TOML subset: table name -> key -> value (String, List<String>, or raw String). */
internal class TomlLite(val tables: LinkedHashMap<String, LinkedHashMap<String, Any>>) {
    fun get(table: String, key: String): Any? = tables[table]?.get(key)
}

private val ARR_TABLE = Regex("^\\[\\[\\s*([^\\]]+?)\\s*\\]\\]")
private val TABLE = Regex("^\\[\\s*([^\\]]+?)\\s*\\]")
private val QUOTED_ITEM = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)'")
private val STRING_VAL = Regex("^\"((?:[^\"\\\\]|\\\\.)*)\"|^'([^']*)'")
private val KEY_OK = Regex("^[A-Za-z0-9_.\\- \"']+$")
private val KEY_CHAR = Regex("[A-Za-z0-9_.\\- ]")
private val HASH_COMMENT = Regex("\\s#")

internal fun parseTomlLite(text: String): TomlLite {
    val tables = LinkedHashMap<String, LinkedHashMap<String, Any>>()
    var cur = ""
    tables[""] = LinkedHashMap()
    val lines = splitLines(text)
    var i = -1
    while (++i < lines.size) {
        val line = lines[i]
        if (line.length > MAX_LINE_CHARS) continue
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        val am = ARR_TABLE.find(trimmed)
        if (am != null) {
            val k = am.groupValues[1].replace(QUOTES, "") + "[]#" + i
            tables[k] = LinkedHashMap()
            cur = k
            continue
        }
        val tm = TABLE.find(trimmed)
        if (tm != null) {
            cur = tm.groupValues[1].replace(DOT_SPACES, ".").replace(QUOTES, "")
            tables.getOrPut(cur) { LinkedHashMap() }
            continue
        }
        val kv = splitTomlKey(trimmed) ?: continue
        val key = kv.first
        var v = kv.second
        if (v.startsWith("\"\"\"") || v.startsWith("'''")) {
            val q = v.substring(0, 3)
            var body = v.substring(3)
            while (!body.contains(q) && i + 1 < lines.size && body.length < MAX_MANIFEST_BYTES) body += "\n" + lines[++i]
            val e = body.indexOf(q)
            setTomlKey(tables, cur, key, if (e >= 0) body.substring(0, e) else body)
            continue
        }
        if (v.startsWith("[")) {
            var depth = bracketDepth(v)
            while (depth > 0 && i + 1 < lines.size) {
                val nx = lines[++i]
                if (nx.length > MAX_LINE_CHARS) continue
                v += "\n" + nx
                depth += bracketDepth(nx)
            }
            val body = v.split('\n').joinToString("\n") { stripTomlTrailingComment(it) }
            val items = QUOTED_ITEM.findAll(body).map { if (it.groups[1] != null) it.groupValues[1] else it.groupValues[2] }.toList()
            setTomlKey(tables, cur, key, items)
            continue
        }
        val sm = STRING_VAL.find(v)
        if (sm != null) {
            setTomlKey(tables, cur, key, if (sm.groups[1] != null) sm.groupValues[1] else sm.groupValues[2])
            continue
        }
        val hc = HASH_COMMENT.find(v)?.range?.first ?: -1
        setTomlKey(tables, cur, key, (if (hc >= 0) v.substring(0, hc) else v).trim())
    }
    return TomlLite(tables)
}

private val QUOTES = Regex("[\"']")
private val DOT_SPACES = Regex("\\s*\\.\\s*")

private fun splitTomlKey(t: String): Pair<String, String>? {
    var q: Char? = null
    for (i in t.indices) {
        val c = t[i]
        if (q != null) { if (c == q) q = null; continue }
        if (c == '"' || c == '\'') { q = c; continue }
        if (c == '=') {
            val rawKey = t.substring(0, i).trim()
            if (rawKey.isEmpty() || !KEY_OK.matches(rawKey)) return null
            return rawKey.replace(QUOTES, "").trim() to t.substring(i + 1).trim()
        }
        if (!KEY_CHAR.matches(c.toString())) return null
    }
    return null
}

private fun stripTomlTrailingComment(line: String): String {
    val lastQuote = maxOf(line.lastIndexOf('"'), line.lastIndexOf('\''))
    val h = line.indexOf('#', lastQuote + 1)
    return if (h >= 0) line.substring(0, h) else line
}

private fun bracketDepth(s: String): Int {
    var d = 0
    var q: Char? = null
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (q != null) {
            if (c == '\\' && q == '"') i++ else if (c == q) q = null
            i++
            continue
        }
        if (c == '"' || c == '\'') q = c
        else if (c == '#') break
        else if (c == '[' || c == '{') d++
        else if (c == ']' || c == '}') d--
        i++
    }
    return d
}

private fun setTomlKey(tables: LinkedHashMap<String, LinkedHashMap<String, Any>>, cur: String, key: String, v: Any) {
    val parts = key.split('.').map { it.trim() }.toMutableList()
    val k = parts.removeAt(parts.size - 1)
    val t = if (parts.isNotEmpty()) (if (cur.isNotEmpty()) "$cur." else "") + parts.joinToString(".") else cur
    tables.getOrPut(t) { LinkedHashMap() }[k] = v
}

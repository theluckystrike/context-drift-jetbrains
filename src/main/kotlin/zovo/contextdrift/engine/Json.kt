package zovo.contextdrift.engine

/** A small strict JSON reader (objects keep key order). Throws IllegalArgumentException on bad input. */
internal object Json {
    fun parse(text: String): Any? {
        val p = P(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("trailing data")
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }
        fun fail(): Nothing = throw IllegalArgumentException("bad json at $i")
        fun value(depth: Int): Any? {
            if (depth > 200) fail()
            if (i >= s.length) fail()
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) fail(); i += w.length; return v }
        fun num(): Any {
            val st = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            if (st == i) fail()
            return s.substring(st, i).toDoubleOrNull() ?: fail()
        }
        fun str(): String {
            i++
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail()
                val c = s[i++]
                when {
                    c == '"' -> return b.toString()
                    c == '\\' -> {
                        if (i >= s.length) fail()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000c')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> { if (i + 4 > s.length) fail(); b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> fail()
                        }
                    }
                    c.code < 0x20 -> fail()
                    else -> b.append(c)
                }
            }
        }
        fun arr(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value(depth + 1)); ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                fail()
            }
        }
        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail()
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail()
                i++
                ws()
                out[k] = value(depth + 1)
                ws()
                if (i >= s.length) fail()
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                fail()
            }
        }
    }
}

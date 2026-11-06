package zovo.contextdrift.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The port must report exactly what the VS Code engine reports. cases.json holds 168 repos from the VS Code
 * extension's own test suites (two independent review rounds, the false-positive regressions and the fixtures),
 * with the JS engine's real output on each (exported by out/JB1-scripts/export-cases.js).
 * CD007 and CD008 are new in this port and are left out of the comparison; the CD004 quick fix is new too.
 */
class ParityTest {
    @Suppress("UNCHECKED_CAST")
    private val cases: List<Map<String, Any?>> by lazy {
        val text = javaClass.getResourceAsStream("/parity/cases.json")!!.bufferedReader().readText()
        Json.parse(text) as List<Map<String, Any?>>
    }

    private fun key(rule: Any?, sev: Any?, file: Any?, line: Any?, col: Any?, end: Any?, msg: Any?, fix: Any?) =
        "$rule $sev $file ${num(line)}:${num(col)}-${num(end)} $msg" + (if (fix != null) " => $fix" else "")

    private fun num(v: Any?) = when (v) { is Double -> v.toInt().toString(); else -> v.toString() }

    @Test
    fun portMatchesTheJsEngineOnEveryCase() {
        val failures = ArrayList<String>()
        var compared = 0
        for (c in cases) {
            @Suppress("UNCHECKED_CAST")
            val files = (c["files"] as Map<String, Any?>).mapValues { it.value.toString() }
            @Suppress("UNCHECKED_CAST")
            val js = (c["js"] as List<Map<String, Any?>>).map {
                key(it["ruleId"], it["severity"], it["file"], it["line"], it["column"], it["endColumn"], it["message"], it["fix"])
            }.sorted()
            val kt = TestRepos.run(files).diagnostics.filter { it.ruleId != "CD007" && it.ruleId != "CD008" }.map {
                val fix = if (it.ruleId == "CD004") null else it.fix?.newText
                key(it.ruleId, it.severity.label, it.file, it.line, it.column, it.endColumn, it.message, fix)
            }.sorted()
            compared += js.size
            if (js != kt) failures.add("${c["suite"]}/${c["name"]}\n  js: $js\n  kt: $kt")
        }
        assertEquals("parity failures (${failures.size} of ${cases.size} cases):\n" + failures.joinToString("\n"), 0, failures.size)
        println("parity: ${cases.size} cases, $compared JS findings, all equal")
    }
}

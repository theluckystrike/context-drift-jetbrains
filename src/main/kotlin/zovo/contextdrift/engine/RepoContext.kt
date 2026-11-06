package zovo.contextdrift.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

internal class PackageJson(
    val dir: String, val rel: String, val invalid: Boolean,
    val json: Map<String, Any?> = emptyMap(), val scripts: Map<String, Any?> = emptyMap(), val deps: Set<String> = emptySet(),
)

internal class MakeFacts(val targets: MutableSet<String> = LinkedHashSet(), var uncertain: Boolean = false)
internal class JustFacts(val recipes: MutableSet<String> = LinkedHashSet(), val modules: MutableSet<String> = LinkedHashSet(), var uncertain: Boolean = false)
internal class TaskFacts(val tasks: MutableSet<String> = LinkedHashSet(), val patterns: MutableList<CompiledGlob> = ArrayList(), var uncertain: Boolean = false)
internal class PyFacts(val pyprojects: List<String>, val scripts: Set<String>, val deps: Set<String>)
internal class CargoFacts(val manifests: List<String>, val packages: Set<String>, val bins: Set<String>, val aliases: Set<String>, val hasAliasTable: Boolean)
internal class PmInfo(val pm: String, val source: String)
internal class IgnoreRule(val base: String, val g: CompiledGlob, val anchored: Boolean, val dirOnly: Boolean)

/** A version source: kind is pin, pins or range. */
internal class VersionSource(val kind: String, val value: String, val source: String, val ver: List<Int>? = null, val vers: List<List<Int>> = emptyList())

/**
 * A cached view of the repository at root: the file list (walked once), and manifest facts read on demand.
 * Never runs git. Reads only regular files inside the root (a symlink that leaves the root is not followed).
 */
class RepoContext private constructor(
    val root: String,
    internal val realRoot: String,
    val files: List<String>,
    internal val fileSet: Set<String>,
    internal val dirs: Set<String>,
    val truncated: Boolean,
    internal val byBase: Map<String, List<String>>,
    val gitRoot: String?,
    internal val gitDir: String?,
    val sparse: Boolean,
) {
    private val cache = HashMap<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    internal fun <T> memo(key: String, fn: () -> T): T {
        if (cache.containsKey(key)) return cache[key] as T
        val v = fn()
        cache[key] = v
        return v
    }

    internal fun cacheHas(key: String) = cache.containsKey(key)
    internal fun cachePut(key: String, v: Any?) { cache[key] = v }
    internal fun cacheGet(key: String) = cache[key]

    /** Drops cached manifest facts (package.json, Makefile, ...) but keeps the file list. */
    fun invalidateManifests() = synchronized(cache) { cache.clear() }

    companion object {
        fun create(rootPath: String): RepoContext {
            val root = File(rootPath).absoluteFile.normalize().path
            val w = walk(root)
            val byBase = HashMap<String, MutableList<String>>()
            for (f in w.files) byBase.getOrPut(f.substring(f.lastIndexOf('/') + 1)) { ArrayList() }.add(f)
            val realRoot = try { File(root).toPath().toRealPath().toString() } catch (_: Exception) { root }
            val git = gitInfo(root)
            return RepoContext(root, realRoot, w.files, w.files.toHashSet(), w.dirs, w.truncated, byBase, git.first, git.second, git.third)
        }

        private class Walk(val files: List<String>, val dirs: Set<String>, val truncated: Boolean)

        private fun walk(root: String): Walk {
            val files = ArrayList<String>()
            val dirs = LinkedHashSet<String>().apply { add("") }
            var count = 0
            var truncated = false
            val stack = ArrayDeque<String>().apply { addLast("") }
            while (stack.isNotEmpty() && !truncated) {
                val rel = stack.removeLast()
                val dir = if (rel.isEmpty()) File(root) else File(root, rel)
                val ents = dir.listFiles() ?: continue
                ents.sortBy { it.name }
                if (rel.isNotEmpty() && ents.any { it.name == ".git" }) continue // nested repo or submodule: its own root
                for (e in ents) {
                    if (++count > MAX_WALK_ENTRIES) { truncated = true; break }
                    val r = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                    val p = e.toPath()
                    if (Files.isSymbolicLink(p)) {
                        try {
                            if (Files.isRegularFile(p)) files.add(r) else if (Files.isDirectory(p)) dirs.add(r) // never descend into symlinks
                        } catch (_: Exception) { /* dangling */ }
                        continue
                    }
                    if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                        if (e.name in IGNORED_DIRS) continue
                        dirs.add(r)
                        stack.addLast(r)
                    } else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) files.add(r)
                }
            }
            return Walk(files, dirs, truncated)
        }

        /** Finds the enclosing git repository without running git: (root, gitDir, sparse). */
        private fun gitInfo(root: String): Triple<String?, String?, Boolean> {
            var d: File? = File(root)
            var k = 0
            while (d != null && k++ < 64) {
                val dotgit = File(d, ".git")
                if (dotgit.exists()) {
                    var gd: File? = dotgit
                    if (dotgit.isFile) {
                        gd = try {
                            val m = Regex("^gitdir:\\s*(.+)$", RegexOption.MULTILINE).find(dotgit.readText().take(4096))
                            if (m != null) File(d, m.groupValues[1].trim()).let { if (File(m.groupValues[1].trim()).isAbsolute) File(m.groupValues[1].trim()) else it }.normalize() else null
                        } catch (_: Exception) { null }
                    }
                    var sparse = false
                    if (gd != null) {
                        val gdirs = arrayListOf(gd)
                        try {
                            val c = File(gd, "commondir").readText().trim()
                            if (c.isNotEmpty()) gdirs.add(File(gd, c).normalize())
                        } catch (_: Exception) { /* not a worktree */ }
                        for (g in gdirs) {
                            if (File(g, "info/sparse-checkout").exists()) sparse = true
                            for (cf in listOf("config", "config.worktree")) {
                                try {
                                    val t = File(g, cf).readText().take(256 * 1024)
                                    if (Regex("^[ \\t]*sparsecheckout[ \\t]*=[ \\t]*true[ \\t]*$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).containsMatchIn(t)) sparse = true
                                } catch (_: Exception) { /* none */ }
                            }
                        }
                    }
                    return Triple(d.path, gd?.path, sparse)
                }
                d = d.parentFile
            }
            return Triple(null, null, false)
        }
    }

    internal fun abs(rel: String): Path = Paths.get(root, rel)

    /** True when abs (after resolving symlinks) is inside the workspace root. */
    internal fun insideRoot(abs: Path): Boolean {
        val real = try { abs.toRealPath().toString() } catch (_: Exception) { return false }
        val r = realRoot
        return real == r || real.startsWith(if (r.endsWith(File.separator)) r else r + File.separator)
    }

    internal fun readRel(rel: String, maxBytes: Int = MAX_MANIFEST_BYTES): String? = memo("read:$rel") {
        try {
            val p = abs(rel)
            if (!Files.isRegularFile(p) || Files.size(p) > maxBytes) return@memo null
            if (!insideRoot(p)) return@memo null
            val t = String(Files.readAllBytes(p), Charsets.UTF_8)
            if (isBinaryText(t)) null else stripBom(t)
        } catch (_: Exception) { null }
    }

    /** Reads an instruction file: the text, or a skip reason. */
    internal fun readFresh(rel: String): Pair<String?, String?> {
        return try {
            val p = abs(rel)
            if (!Files.isRegularFile(p)) return null to null
            if (!insideRoot(p)) return null to "symlink to a file outside the workspace"
            if (Files.size(p) > MAX_INSTRUCTION_BYTES) return null to "larger than 1 MB"
            val t = String(Files.readAllBytes(p), Charsets.UTF_8)
            if (isBinaryText(t)) null to "binary file" else stripBom(t) to null
        } catch (_: Exception) { null to null }
    }

    internal fun hasFile(rel: String?) = rel != null && rel in fileSet
    internal fun existsRel(rel: String?): Boolean {
        if (rel == null) return false
        if (rel in fileSet || rel in dirs) return true
        return try { Files.exists(abs(rel)) } catch (_: Exception) { false }
    }
    internal fun isDirRel(rel: String?): Boolean {
        if (rel == null) return false
        if (rel in dirs) return true
        return try { Files.isDirectory(abs(rel)) } catch (_: Exception) { false }
    }
    internal fun filesNamed(vararg names: String): List<String> = names.flatMap { byBase[it] ?: emptyList() }

    /* ---------- package.json ---------- */

    internal fun readPackage(dirRel: String): PackageJson? = memo("pkg:$dirRel") {
        val rel = if (dirRel.isNotEmpty()) "$dirRel/package.json" else "package.json"
        if (!hasFile(rel)) return@memo null
        val t = readRel(rel) ?: return@memo PackageJson(dirRel, rel, true)
        try {
            @Suppress("UNCHECKED_CAST")
            val j = Json.parse(t) as? Map<String, Any?> ?: return@memo PackageJson(dirRel, rel, true)
            @Suppress("UNCHECKED_CAST")
            val scripts = (j["scripts"] as? Map<String, Any?>) ?: emptyMap()
            val deps = LinkedHashSet<String>()
            for (k in listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")) {
                (j[k] as? Map<*, *>)?.keys?.forEach { deps.add(it.toString()) }
            }
            PackageJson(dirRel, rel, false, j, scripts, deps)
        } catch (_: Exception) { PackageJson(dirRel, rel, true) }
    }

    internal fun allPackages(): List<PackageJson> = memo("allpkgs") { filesNamed("package.json").mapNotNull { readPackage(posixDirname(it)) } }

    internal fun pickPackages(dir: String): List<PackageJson> {
        val res = ArrayList<PackageJson>()
        for (d in ancestors(dir)) { val p = readPackage(d); if (p != null) { res.add(p); break } }
        val rootPkg = readPackage("")
        if (rootPkg != null && rootPkg !in res) res.add(rootPkg)
        return res
    }

    internal fun allDeps(): Set<String> = memo("alldeps") {
        val s = HashSet<String>()
        for (p in allPackages()) if (!p.invalid) for (d in p.deps) { s.add(d); s.add(d.substring(d.lastIndexOf('/') + 1)) }
        s
    }

    /** Scripts of package.json files above the workspace root, up to the git root. */
    internal fun outsideAncestorPackages(): List<Set<String>> = memo("outsidepkgs") {
        val out = ArrayList<Set<String>>()
        val gr = gitRoot ?: return@memo out
        val top = File(gr).absoluteFile.normalize()
        var d = File(root).absoluteFile.normalize()
        var k = 0
        while (k++ < 16 && d != top) {
            val p = d.parentFile ?: break
            d = p
            try {
                val f = File(d, "package.json")
                if (!f.isFile || f.length() > MAX_MANIFEST_BYTES) continue
                val j = Json.parse(f.readText()) as? Map<*, *>
                val sc = j?.get("scripts") as? Map<*, *>
                if (sc != null) out.add(sc.keys.map { it.toString() }.toSet())
            } catch (_: Exception) { /* none or invalid */ }
        }
        out
    }

    /** `yarn vue-cli-service`: a binary whose name differs from its package, or one in node_modules/.bin. */
    internal fun binFromDependency(dir: String, name: String): Boolean {
        fun flat(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")
        val n = flat(name)
        for (d in allDeps()) {
            val f = flat(d)
            if (f == n || (f.length >= 6 && n.startsWith(f)) || (n.length >= 6 && f.startsWith(n))) return true
        }
        for (a in ancestors(dir)) {
            val binRel = (if (a.isNotEmpty()) "$a/" else "") + "node_modules/.bin/" + name
            if (Files.exists(abs(binRel))) return true
        }
        return false
    }

    internal fun repoPackageManager(dir: String): PmInfo? = memo("pm:$dir") {
        for (d in ancestors(dir)) {
            val pkg = readPackage(d)
            fun at(n: String) = hasFile(if (d.isNotEmpty()) "$d/$n" else n)
            val pmField = if (pkg != null && !pkg.invalid) pkg.json["packageManager"] as? String else null
            if (pmField != null) {
                val name = pmField.split('@')[0].trim().lowercase()
                if (name in listOf("npm", "pnpm", "yarn", "bun")) return@memo PmInfo(name, pkg!!.rel + " \"packageManager\"")
            }
            val kinds = LinkedHashMap<String, String>()
            if (at("package-lock.json")) kinds["npm"] = "package-lock.json" else if (at("npm-shrinkwrap.json")) kinds["npm"] = "npm-shrinkwrap.json"
            if (at("pnpm-lock.yaml")) kinds["pnpm"] = "pnpm-lock.yaml"
            if (at("yarn.lock")) kinds["yarn"] = "yarn.lock"
            if (at("bun.lock")) kinds["bun"] = "bun.lock" else if (at("bun.lockb")) kinds["bun"] = "bun.lockb"
            if (kinds.size == 1) {
                val (pm, f) = kinds.entries.first().toPair()
                return@memo PmInfo(pm, if (d.isNotEmpty()) "$d/$f" else f)
            }
            if (kinds.size > 1) return@memo null
        }
        null
    }

    /* ---------- Makefile ---------- */

    internal fun parseMakefile(rel: String, depth: Int): MakeFacts = memo("make:$rel") {
        val res = MakeFacts()
        val t = readRel(rel) ?: run { res.uncertain = true; return@memo res }
        val lines = splitLines(t)
        var inDefine = false
        var i = -1
        while (++i < lines.size) {
            var line = lines[i]
            while (line.endsWith("\\") && i + 1 < lines.size && line.length <= MAX_LINE_CHARS) line = line.dropLast(1) + " " + lines[++i]
            if (line.length > MAX_LINE_CHARS) { res.uncertain = true; continue }
            if (inDefine) {
                if (Regex("^\\s*endef\\b").containsMatchIn(line)) inDefine = false
                else if (!line.startsWith("\t") && splitMakeRule(line) != null) res.uncertain = true
                continue
            }
            if (Regex("^\\s*define\\b").containsMatchIn(line)) { inDefine = true; continue }
            if (line.startsWith("\t")) continue
            val noComment = line.replace(Regex("(^|[^\\\\])#.*$"), "$1")
            if (Regex("^\\s*\\$\\((?:eval|call|foreach)\\b").containsMatchIn(noComment) || Regex("\\$\\(eval\\b").containsMatchIn(noComment)) { res.uncertain = true; continue }
            val inc = Regex("^\\s*-?(?:s?include)\\s+(.+)$").find(noComment)
            if (inc != null) {
                if (depth >= 4) { res.uncertain = true; continue }
                for (f in inc.groupValues[1].trim().split(Regex("\\s+"))) {
                    if (Regex("[$*?%]").containsMatchIn(f)) { res.uncertain = true; continue }
                    val incRel = normalizeRel(posixJoin(posixDirname(rel), f))
                    if (incRel == null || !hasFile(incRel)) { res.uncertain = true; continue }
                    val sub = parseMakefile(incRel, depth + 1)
                    res.targets.addAll(sub.targets)
                    if (sub.uncertain) res.uncertain = true
                }
                continue
            }
            val head = splitMakeRule(noComment) ?: continue
            if (Regex("^\\s*(?:export|override|ifeq|ifneq|ifdef|ifndef|else|endif|vpath)\\b").containsMatchIn(head)) continue
            for (tg in head.trim().split(Regex("\\s+"))) {
                if (tg.isEmpty()) continue
                if (tg.contains('%') || tg.contains('$')) { res.uncertain = true; continue }
                if (Regex("^\\.[A-Z_]+$").matches(tg)) continue
                res.targets.add(tg)
            }
        }
        res
    }

    internal fun makefileIn(dirRel: String): String? {
        for (n in MAKEFILE_NAMES) { val rel = if (dirRel.isNotEmpty()) "$dirRel/$n" else n; if (hasFile(rel)) return rel }
        return null
    }

    /* ---------- justfile ---------- */

    internal fun justfileIn(dirRel: String): String? {
        for (n in JUSTFILE_NAMES) { val rel = if (dirRel.isNotEmpty()) "$dirRel/$n" else n; if (hasFile(rel)) return rel }
        return null
    }

    internal fun parseJustfile(rel: String, depth: Int, seen: MutableSet<String> = HashSet()): JustFacts = memo("just:$rel") {
        val res = JustFacts()
        seen.add(rel)
        val t = readRel(rel) ?: run { res.uncertain = true; return@memo res }
        for (line in splitLines(t)) {
            if (line.length > MAX_LINE_CHARS) { res.uncertain = true; continue }
            if (line.isEmpty() || line[0].isWhitespace() || line.startsWith("#") || line.startsWith("[")) continue
            var m = Regex("^alias\\s+([A-Za-z_][\\w-]*)\\s*:=").find(line)
            if (m != null) { res.recipes.add(m.groupValues[1]); continue }
            m = Regex("^mod\\??\\s+([A-Za-z_][\\w-]*)").find(line)
            if (m != null) { res.modules.add(m.groupValues[1]); continue }
            m = Regex("^import\\??\\s+['\"]([^'\"]+)['\"]").find(line)
            if (m != null) {
                val incRel = normalizeRel(posixJoin(posixDirname(rel), m.groupValues[1]))
                if (depth < 4 && incRel != null && hasFile(incRel) && incRel !in seen) {
                    val sub = parseJustfile(incRel, depth + 1, seen)
                    res.recipes.addAll(sub.recipes); res.modules.addAll(sub.modules)
                    if (sub.uncertain) res.uncertain = true
                } else res.uncertain = true
                continue
            }
            if (Regex("^(?:set|export|alias|import|mod)\\s").containsMatchIn(line)) continue
            m = Regex("^@?([A-Za-z_][\\w-]*)").find(line)
            if (m != null) {
                val rest = line.substring(m.value.length)
                val c = rest.indexOf(':')
                if (c >= 0 && rest.getOrNull(c + 1) != '=' && (c == 0 || rest[0].isWhitespace())) res.recipes.add(m.groupValues[1])
            }
        }
        res
    }

    /* ---------- Taskfile ---------- */

    internal fun taskfileIn(dirRel: String): String? {
        for (n in TASKFILE_NAMES) { val rel = if (dirRel.isNotEmpty()) "$dirRel/$n" else n; if (hasFile(rel)) return rel }
        return null
    }

    internal fun parseTaskfile(rel: String): TaskFacts = memo("task:$rel") {
        val res = TaskFacts()
        val t = readRel(rel) ?: run { res.uncertain = true; return@memo res }
        var inTasks = false
        var indent = -1
        for (line in splitLines(t)) {
            if (line.length > MAX_LINE_CHARS) continue
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                inTasks = Regex("^tasks\\s*:").containsMatchIn(line)
                if (Regex("^includes\\s*:").containsMatchIn(line)) res.uncertain = true
                indent = -1
                continue
            }
            if (!inTasks || line.isBlank() || Regex("^\\s*#").containsMatchIn(line)) continue
            val m = parseTaskKey(line) ?: continue
            if (indent < 0) indent = m.first
            if (m.first != indent) continue
            if (m.second.contains('*')) {
                val g = compileGlob(m.second.replace(':', '/'))
                if (g != null) res.patterns.add(g) else res.uncertain = true
            } else res.tasks.add(m.second)
        }
        res
    }

    private fun parseTaskKey(line: String): Pair<Int, String>? {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        if (i == 0 || i >= line.length) return null
        val indent = i
        val name: String
        val q = line[i]
        if (q == '"' || q == '\'') {
            val end = line.indexOf(q, i + 1)
            if (end < 0) return null
            name = line.substring(i + 1, end)
            val rest = line.substring(end + 1).trimStart()
            if (rest.isEmpty() || rest[0] != ':' || (rest.length > 1 && !rest[1].isWhitespace())) return null
        } else {
            var c = -1
            for (k in i until line.length) {
                val ch = line[k]
                if (ch == '"' || ch == '\'' || ch == '#') return null
                if (ch == ':' && (k + 1 == line.length || line[k + 1] == ' ' || line[k + 1] == '\t')) { c = k; break }
            }
            if (c < 0) return null
            name = line.substring(i, c).trimEnd()
        }
        if (name.isEmpty() || name[0].isWhitespace()) return null
        return indent to name
    }

    /* ---------- TOML-based facts ---------- */

    internal fun readToml(rel: String): TomlLite? = memo("toml:$rel") {
        val t = readRel(rel) ?: return@memo null
        try { parseTomlLite(t) } catch (_: Exception) { null }
    }

    internal fun pythonFacts(): PyFacts = memo("py") {
        val scripts = LinkedHashSet<String>()
        val deps = LinkedHashSet<String>()
        val pyprojects = filesNamed("pyproject.toml")
        for (rel in pyprojects) {
            val toml = readToml(rel) ?: continue
            for ((name, tbl) in toml.tables) {
                if (name in setOf("project.scripts", "project.gui-scripts", "tool.poetry.scripts", "tool.pdm.scripts")) scripts.addAll(tbl.keys)
                if (name == "tool.poetry.dependencies" || name == "tool.poetry.dev-dependencies" || Regex("^tool\\.poetry\\.group\\.[^.]+\\.dependencies$").matches(name)) {
                    for (k in tbl.keys) deps.add(normPyName(k))
                }
                if (name == "project.optional-dependencies" || name == "dependency-groups" || name == "tool.pdm.dev-dependencies") {
                    for (v in tbl.values) if (v is List<*>) for (s in v) pep508Name(s.toString())?.let { deps.add(it) }
                }
            }
            for ((table, key) in listOf("project" to "dependencies", "tool.uv" to "dev-dependencies")) {
                val v = toml.get(table, key)
                if (v is List<*>) for (s in v) pep508Name(s.toString())?.let { deps.add(it) }
            }
            val pname = (toml.get("project", "name") ?: toml.get("tool.poetry", "name"))
            if (pname is String) { scripts.add(pname); deps.add(normPyName(pname)) }
        }
        for (f in files) {
            val b = f.substring(f.lastIndexOf('/') + 1)
            if (!Regex("^requirements.*\\.(txt|in)$").matches(b)) continue
            val t = readRel(f) ?: continue
            for (line in splitLines(t)) pep508Name(line.replace(Regex("#.*"), ""))?.let { deps.add(it) }
        }
        PyFacts(pyprojects, scripts, deps)
    }

    internal fun cargoFacts(): CargoFacts = memo("cargo") {
        val packages = LinkedHashSet<String>()
        val bins = LinkedHashSet<String>()
        val aliases = LinkedHashSet<String>()
        var hasAliasTable = false
        val manifests = filesNamed("Cargo.toml")
        for (rel in manifests) {
            val toml = readToml(rel) ?: continue
            for ((name, tbl) in toml.tables) {
                val n = tbl["name"]
                if (name == "package" && n is String) { packages.add(n); bins.add(n) }
                if (name.startsWith("bin[]") && n is String) bins.add(n)
            }
        }
        for (f in files) {
            val m = Regex("(?:^|/)src/bin/([^/]+)\\.rs$").find(f) ?: Regex("(?:^|/)src/bin/([^/]+)/main\\.rs$").find(f)
            if (m != null) bins.add(m.groupValues[1])
            if (Regex("(?:^|/)\\.cargo/config(?:\\.toml)?$").containsMatchIn(f)) {
                val tbl = readToml(f)?.tables?.get("alias")
                if (tbl != null) { hasAliasTable = true; aliases.addAll(tbl.keys) }
            }
        }
        CargoFacts(manifests, packages, bins, aliases, hasAliasTable)
    }

    /* ---------- .gitignore ---------- */

    internal fun gitignoreRules(): List<IgnoreRule> = memo("gitignore") {
        val rules = ArrayList<IgnoreRule>()
        val gis: MutableList<String?> = filesNamed(".gitignore").toMutableList()
        if (gitDir != null) gis.add(null)
        outer@ for (gi in gis) {
            val base = if (gi == null) "" else posixDirname(gi)
            var t: String?
            if (gi == null) {
                t = try {
                    val f = File(gitDir, "info/exclude")
                    if (f.isFile && f.length() <= MAX_MANIFEST_BYTES) f.readText() else null
                } catch (_: Exception) { null }
                if (gitRoot != null && File(gitRoot).absoluteFile.normalize() != File(root).absoluteFile.normalize()) t = null
            } else t = readRel(gi, 256 * 1024)
            if (t == null) continue
            for (raw in splitLines(t)) {
                if (rules.size >= MAX_GITIGNORE_RULES) break@outer
                if (raw.length > 512) continue
                var line = raw.trimEnd()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
                var dirOnly = false
                if (line.endsWith("/")) { dirOnly = true; line = line.dropLast(1) }
                val anchored = line.replace(Regex("/\\*\\*$"), "").contains('/')
                line = line.removePrefix("/")
                val g = compileGlob(line) ?: continue
                rules.add(IgnoreRule(base, g, anchored, dirOnly))
            }
        }
        rules
    }

    internal fun isGitignored(rel: String): Boolean {
        val rules = gitignoreRules()
        if (rules.isEmpty()) return false
        return memo("ign:$rel") {
            val budget = Budget(200_000)
            try {
                for (r in rules) {
                    if (r.base.isNotEmpty() && !(rel == r.base || rel.startsWith(r.base + "/"))) continue
                    val local = if (r.base.isNotEmpty()) rel.substring(minOf(rel.length, r.base.length + 1)) else rel
                    val lp = local.split('/').filter { it.isNotEmpty() }
                    for (i in 1..lp.size) {
                        if (r.anchored) { if (globMatch(r.g, lp.subList(0, i).joinToString("/"), budget)) return@memo true }
                        else if (globMatch(r.g, lp[i - 1], budget)) return@memo true
                    }
                }
                false
            } catch (_: GlobBudgetExceeded) { true }
        }
    }

    internal fun basenameGitignored(name: String): Boolean {
        val budget = Budget(200_000)
        return try { gitignoreRules().any { !it.anchored && globMatch(it.g, name, budget) } } catch (_: GlobBudgetExceeded) { false }
    }

    internal fun nearestExistingParent(rel: String?): String {
        var d = posixDirname(rel ?: "")
        while (d.isNotEmpty() && !isDirRel(d)) d = posixDirname(d)
        return d
    }

    /** Does the glob match any file (or folder) of the repo? Past the work budget, it counts as matching. */
    internal fun globMatchesAny(glob: String, baseDir: String, budget: Budget?): Boolean {
        var g = glob.replace('\\', '/').removePrefix("./")
        var anchoredBase = baseDir
        if (g.startsWith("/")) { g = g.substring(1); anchoredBase = "" }
        val dirOnly = g.endsWith("/")
        if (dirOnly) g = g.dropLast(1)
        val key = "glob:$anchoredBase\u0000$g" + (if (dirOnly) "/" else "")
        if (cacheHas(key)) return cacheGet(key) as Boolean
        val compiled = compileGlob(g) ?: return true
        val hasSlash = g.contains('/')
        val last = g.substring(g.lastIndexOf('/') + 1)
        val alsoDirs = !dirOnly && !Regex("\\.[A-Za-z0-9*?]+$").containsMatchIn(last)
        val parts = g.split('/')
        val lead = ArrayList<String>()
        for (seg in parts) { if (seg.isEmpty() || Regex("[*?\\[\\]{}\\\\]").containsMatchIn(seg)) break; lead.add(seg) }
        if (lead.size == parts.size) lead.removeAt(lead.size - 1)
        val prefix = lead.joinToString("/")
        fun test(pool: Collection<String>, base: String, needSlash: Boolean): Boolean {
            for (f in pool) {
                if (budget != null && --budget.left < 0) throw GlobBudgetExceeded()
                var local = f
                if (base.isNotEmpty()) { if (!f.startsWith("$base/")) continue; local = f.substring(base.length + 1) }
                if (needSlash) {
                    if (prefix.isNotEmpty() && !(local == prefix || local.startsWith("$prefix/"))) continue
                    if (globMatch(compiled, local, budget)) return true
                } else if (globMatch(compiled, local.substring(local.lastIndexOf('/') + 1), budget)) return true
            }
            return false
        }
        var hit = false
        try {
            val pools = if (dirOnly) listOf(dirs) else if (alsoDirs) listOf(files, dirs) else listOf(files)
            for (pool in pools) {
                if (test(pool, anchoredBase, hasSlash)) { hit = true; break }
                if (anchoredBase.isNotEmpty() && hasSlash && test(pool, "", true)) { hit = true; break }
            }
        } catch (_: GlobBudgetExceeded) { hit = true }
        cachePut(key, hit)
        return hit
    }

    internal fun submodulePaths(): List<String> = memo("submodules") {
        val out = ArrayList<String>()
        for (gm in filesNamed(".gitmodules")) {
            val base = posixDirname(gm)
            for (line in splitLines(readRel(gm) ?: "")) {
                val m = Regex("^\\s*path\\s*=(.*)$").find(if (line.length <= MAX_LINE_CHARS) line else "")
                val v = m?.groupValues?.get(1)?.trim()
                if (!v.isNullOrEmpty()) normalizeRel(posixJoin(base, v))?.let { out.add(it) }
            }
        }
        out
    }

    internal fun nearestFile(dir: String, names: List<String>): String? {
        for (d in ancestors(dir)) for (n in names) { val rel = if (d.isNotEmpty()) "$d/$n" else n; if (hasFile(rel)) return rel }
        return null
    }

    /** No manifest anywhere, and the folder is not a git repository root: a folder that holds a copied instruction file. */
    internal fun looksLikeCopiedFolder(): Boolean = memo("copied") {
        (gitRoot == null || File(gitRoot).absoluteFile.normalize() != File(root).absoluteFile.normalize()) &&
            MANIFEST_NAMES.none { (byBase[it] ?: emptyList()).isNotEmpty() } &&
            files.none { Regex("\\.csproj$|\\.sln$").containsMatchIn(it) }
    }
}

internal val MAKEFILE_NAMES = listOf("GNUmakefile", "makefile", "Makefile")
internal val JUSTFILE_NAMES = listOf("justfile", "Justfile", ".justfile", "JUSTFILE")
internal val TASKFILE_NAMES = listOf("Taskfile.yml", "Taskfile.yaml", "taskfile.yml", "taskfile.yaml", "Taskfile.dist.yml", "Taskfile.dist.yaml")
internal val MANIFEST_NAMES = listOf("package.json", "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile", "Cargo.toml", "go.mod", "pom.xml", "build.gradle", "build.gradle.kts", "Gemfile", "composer.json", "deno.json", "deno.jsonc", "mix.exs", "pubspec.yaml", "CMakeLists.txt", "Package.swift", "Makefile", "makefile", "GNUmakefile", "justfile", "Justfile", "Taskfile.yml", "Taskfile.yaml", "flake.nix", "BUILD.bazel", "WORKSPACE", "meson.build", "Dockerfile")

/** "a b: deps" -> "a b", or null. Linear scan: the first ':' with no '=' or '#' before it, not ":=" or "::=". */
internal fun splitMakeRule(line: String): String? {
    if (line.isEmpty() || Regex("^[\\s:=#]").containsMatchIn(line)) return null
    val idx = line.indexOf(':')
    if (idx <= 0) return null
    var head = line.substring(0, idx)
    if (head.contains('=') || head.contains('#')) return null
    var after = idx + 1
    if (line.getOrNull(after) == ':') after++
    if (line.getOrNull(after) == '=' || line.getOrNull(after) == ':') return null
    head = head.replace(Regex("&\\s*$"), "")
    return if (head.isNotBlank()) head else null
}

internal fun normPyName(n: String) = n.lowercase().replace(Regex("[-_.]+"), "-")
internal fun pep508Name(s: String): String? = Regex("^\\s*([A-Za-z0-9][A-Za-z0-9._-]*)").find(s)?.let { normPyName(it.groupValues[1]) }

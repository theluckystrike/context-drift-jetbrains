package zovo.contextdrift.engine

/* CD001 command drift, CD003 package-manager drift and the verb facts behind CD005, ported from engine.js. */

internal class VerbFact(val key: String, val display: String, val line: Int, val col: Int, val endCol: Int)

internal class FileFacts(val kind: String?) {
    val verbs = LinkedHashMap<String, MutableList<VerbFact>>()
    var dir: String? = null
    var paths: PathStats? = null
    var timedOut = false
    var capped = false
    var longLines = 0
    var spansCapped = false
}

internal class CmdSource(
    val text: String, val line: Int, val col: Int, val lineText: String, val prevText: String, val endCol: Int,
    val inline: Boolean, val afterCreate: Boolean = false,
) { var elsewhere = false }

internal fun addVerb(facts: FileFacts, verb: String?, key: String, display: String, line: Int, col: Int, endCol: Int) {
    if (verb == null) return
    val k = verb + "\u0000" + (facts.dir ?: "")
    val arr = facts.verbs.getOrPut(k) { ArrayList() }
    if (arr.none { it.key == key }) arr.add(VerbFact(key, display, line, col, endCol))
}

private fun parseFlags(toks: List<Tok>, i0: Int, valueFlags: Set<String>): Pair<Int, List<String>> {
    var i = i0
    val flags = ArrayList<String>()
    while (i < toks.size && toks[i].v.startsWith("-") && toks[i].v != "--") {
        val f = toks[i].v
        flags.add(f.split('=')[0])
        i++
        if (f in valueFlags && i < toks.size) i++
    }
    return i to flags
}

internal typealias Emit = (Diagnostic) -> Unit

internal fun checkCommand(ctx: RepoContext, rel: String, src: CmdSource, toks: List<Tok>, dir: String, emit: Emit, facts: FileFacts) {
    val cmd = toks[0].v
    if (cmd == "npx" || cmd == "bunx" || cmd == "pnpx") {
        var i = 1
        while (i < toks.size && toks[i].v.startsWith("-")) i++
        if (i < toks.size) recordToolVerb(facts, toks.subList(i, toks.size), src)
        return
    }
    when (cmd) {
        "npm", "pnpm", "yarn", "bun" -> checkJsPm(ctx, rel, src, toks, dir, emit, facts)
        "make", "gmake" -> checkMake(ctx, rel, src, toks, dir, emit, facts)
        "just" -> checkJust(ctx, rel, src, toks, dir, emit, facts)
        "task", "go-task" -> checkTask(ctx, rel, src, toks, dir, emit, facts)
        "cargo" -> checkCargo(ctx, rel, src, toks, emit, facts)
        "uv", "poetry", "pdm", "rye" -> checkPyRun(ctx, rel, src, toks, emit, facts)
        else -> recordToolVerb(facts, toks, src)
    }
}

internal fun recordToolVerb(facts: FileFacts, toks: List<Tok>, src: CmdSource) {
    val c = toks[0].v
    val sub = toks.getOrNull(1)?.v ?: ""
    fun at(verb: String?, key: String) = addVerb(facts, verb, key, toks.take(3).joinToString(" ") { it.v }, src.line, toks[0].s, toks[minOf(toks.size, 3) - 1].e)
    when {
        c == "go" -> when (sub) { "test" -> at("test", "go:test"); "build" -> at("build", "go:build"); "vet" -> at("lint", "go:vet"); "fmt" -> at("format", "go:fmt") }
        c == "gofmt" -> at("format", "gofmt")
        c == "golangci-lint" -> at("lint", "golangci-lint")
        c == "pytest" || c == "py.test" -> at("test", "pytest")
        (c == "python" || c == "python3") && sub == "-m" && toks.size > 2 -> when (toks[2].v) {
            "pytest" -> at("test", "pytest")
            "unittest" -> at("test", "unittest")
            "mypy" -> at("typecheck", "mypy")
            "black" -> at("format", "black")
            "ruff" -> recordToolVerb(facts, toks.subList(2, toks.size), src)
        }
        c == "ruff" -> if (sub == "check") at("lint", "ruff:check") else if (sub == "format") at("format", "ruff:format")
        c == "black" -> at("format", "black")
        c == "mypy" || c == "pyright" -> at("typecheck", c)
        c == "eslint" || c == "oxlint" || c == "flake8" || c == "pylint" -> at("lint", c)
        c == "prettier" -> at("format", "prettier")
        c == "biome" -> if (sub == "lint" || sub == "check") at("lint", "biome:$sub") else if (sub == "format") at("format", "biome:format")
        c == "vitest" || c == "jest" || c == "mocha" || c == "ava" -> at("test", c)
        c == "tsc" -> at("typecheck", "tsc")
        c == "node" && sub == "--test" -> at("test", "node:test")
        c == "deno" && sub in listOf("test", "lint", "fmt") -> at(VERB_SYNONYMS[sub], "deno:$sub")
    }
}

private val PROJECT_SUBS = setOf("install", "i", "ci", "add", "remove", "rm", "uninstall", "un", "run", "run-script", "test", "t", "start", "update", "up", "upgrade", "exec", "workspace", "workspaces", "dedupe", "link")
private val VERSION_SUFFIX = Regex("@[^@/]*$")
private val DOT_SLASH = Regex("[./]")

private fun checkJsPm(ctx: RepoContext, rel: String, src: CmdSource, toks: List<Tok>, dir: String, emit: Emit, facts: FileFacts) {
    val pm = toks[0].v
    val valueFlags = when (pm) { "npm" -> NPM_VALUE_FLAGS; "pnpm" -> PNPM_VALUE_FLAGS; "yarn" -> YARN_VALUE_FLAGS; else -> BUN_VALUE_FLAGS }
    val (i, flags) = parseFlags(toks, 1, valueFlags)
    var anyPkg = flags.any { it in ANY_PKG_FLAGS }
    val global = "-g" in flags || "--global" in flags
    val subTok = toks.getOrNull(i)
    val sub = subTok?.v ?: ""
    var scriptTok: Tok? = null
    var strict = false
    var form = "other"
    if (pm == "yarn" && sub == "workspace") {
        val after = parseFlags(toks, i + 2, valueFlags)
        scriptTok = toks.getOrNull(after.first)
        anyPkg = true
        form = "bare"
        if (scriptTok != null && scriptTok.v in YARN_BUILTINS && scriptTok.v !in listOf("run", "test", "start")) scriptTok = null
        if (scriptTok != null && scriptTok.v == "run") scriptTok = toks.getOrNull(after.first + 1)
    } else if (pm == "yarn" && sub == "workspaces") {
        if (toks.getOrNull(i + 1)?.v == "run") { scriptTok = toks.getOrNull(i + 2); anyPkg = true; form = "run" }
    } else if ((pm == "npm" && sub in NPM_RUN) || (pm != "npm" && sub == "run")) {
        val after = parseFlags(toks, i + 1, valueFlags)
        if ("--if-present" in after.second) return
        if (after.second.any { it in ANY_PKG_FLAGS }) anyPkg = true
        scriptTok = toks.getOrNull(after.first)
        strict = pm == "npm" || pm == "pnpm"
        form = "run"
        if (scriptTok != null) for (t in toks.drop(after.first + 1)) {
            if (t.v == "--") break
            if (t.v.split('=')[0] in ANY_PKG_FLAGS) anyPkg = true
            if (t.v == "--if-present") return
        }
        if (pm == "bun" && scriptTok != null && DOT_SLASH.containsMatchIn(scriptTok.v)) scriptTok = null
    } else if (sub in listOf("test", "t", "tst") && pm != "bun") {
        if (pm == "yarn" && sub != "test") return
        scriptTok = Tok("test", subTok!!.s, subTok.e)
        strict = true
        form = "lifecycle"
        for (t in toks.drop(i + 1)) { if (t.v == "--") break; if (t.v.split('=')[0] in ANY_PKG_FLAGS) anyPkg = true }
    } else if (sub == "start" && pm != "bun") {
        scriptTok = subTok
        strict = true
        form = "lifecycle"
    } else if (pm != "npm" && sub.isNotEmpty() && !sub.startsWith("-")) {
        val builtins = when (pm) { "pnpm" -> PNPM_BUILTINS; "yarn" -> YARN_BUILTINS; else -> BUN_BUILTINS }
        if (sub !in builtins) {
            if (pm == "bun" && DOT_SLASH.containsMatchIn(sub)) scriptTok = null
            else { scriptTok = subTok; form = "bare" }
        }
    }

    // CD003: package-manager drift
    val pmInfo = ctx.repoPackageManager(dir)
    if (pmInfo != null && pmInfo.pm != pm && !global) {
        val projectSub = if (pm == "yarn" && sub.isEmpty()) "install" else sub
        val isProject = projectSub in PROJECT_SUBS || form == "bare"
        val args = toks.drop(i + 1).filter { !it.v.startsWith("-") }.map { it.v.replace(VERSION_SUFFIX, "") }
        val ownNames = ctx.allPackages().filter { !it.invalid && it.json["name"] is String }.map { it.json["name"] as String }.toSet()
        val consumerInstall = projectSub in listOf("install", "i", "add") && args.isNotEmpty() && args.all { it in ownNames }
        if (isProject && !consumerInstall && !(projectSub == "link" && global)) {
            val hasPkgArgs = toks.drop(i + 1).any { !it.v.startsWith("-") }
            val newText = when {
                form == "run" || form == "lifecycle" -> pmInfo.pm
                form == "bare" -> if (pmInfo.pm == "npm") "npm run" else pmInfo.pm
                (projectSub == "install" || projectSub == "i") && !hasPkgArgs -> pmInfo.pm
                else -> null
            }
            val fix = newText?.let { Fix("Replace `$pm` with `$it`", src.line, toks[0].s, src.line, toks[0].e, it) }
            emit(mkDiag("CD003", Severity.WARNING, rel, src.line, toks[0].s, toks[0].e,
                "`$pm` is used here, but the repo uses ${pmInfo.pm} (${pmInfo.source}).", fix = fix, data = mapOf("kind" to "pm", "expected" to pmInfo.pm, "used" to pm)))
        }
    }

    if (scriptTok == null || global) return
    val script = scriptTok.v
    if (!isLiteralName(script) || script.contains('/')) return
    addVerb(facts, VERB_SYNONYMS[script], "script:$script", "$pm ${if (form == "run") "run " else ""}$script", src.line, toks[0].s, scriptTok.e)

    var pkgs = if (anyPkg) ctx.allPackages() else ctx.pickPackages(dir)
    var fallbackAll = false
    if (pkgs.isEmpty()) { pkgs = ctx.allPackages(); fallbackAll = true }
    if (pkgs.isEmpty()) return
    if (pkgs.any { it.invalid }) return
    if (pkgs.any { it.scripts.containsKey(script) }) return
    if (script == "start" && pkgs.any { ctx.hasFile(if (it.dir.isNotEmpty()) it.dir + "/server.js" else "server.js") }) return
    if (!strict || form != "run") {
        if (script in KNOWN_JS_BINS || script in ctx.allDeps()) return
        if (!script.contains(':') && ctx.binFromDependency(dir, script)) return
    }
    if (!anyPkg && !fallbackAll) {
        if (ctx.allPackages().any { !it.invalid && it.scripts.containsKey(script) }) return
    }
    if (ctx.outsideAncestorPackages().any { script in it }) return
    val hard = strict && form == "run" && !anyPkg && !fallbackAll
    val names = LinkedHashSet<String>()
    for (p in pkgs) names.addAll(p.scripts.keys)
    val sugg = closest(script, names)
    val where = pkgs.take(3).joinToString(", ") { it.rel }
    val msg = "Script \"$script\" is not defined in $where" + when {
        sugg != null -> ". Did you mean \"$sugg\"?"
        names.isNotEmpty() -> ". Available: ${names.take(8).joinToString(", ")}${if (names.size > 8) ", …" else ""}."
        else -> " (it has no scripts)."
    }
    val fix = if (sugg != null && form != "lifecycle") Fix("Replace with \"$sugg\"", src.line, scriptTok.s, src.line, scriptTok.e, sugg) else null
    emit(mkDiag("CD001", if (hard) Severity.ERROR else Severity.WARNING, rel, src.line, scriptTok.s, scriptTok.e, msg, fix = fix,
        data = mapOf("kind" to "script", "manifest" to pkgs[0].rel, "script" to script, "anyPkg" to (anyPkg || fallbackAll))))
}

private val MAKE_VALUE_FLAGS = setOf("-C", "--directory", "-f", "--file", "--makefile", "-I", "--include-dir", "-o", "--old-file", "-W", "--what-if", "--assume-new", "-l", "--load-average", "-O", "--output-sync", "--eval")
private const val MAX_MAKE_TARGETS = 32
private val CAP_WORD = Regex("^[A-Z][a-z]")
private val ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
private val NUM = Regex("^\\d+$")

private fun checkMake(ctx: RepoContext, rel: String, src: CmdSource, toks: List<Tok>, dir: String, emit: Emit, facts: FileFacts) {
    var mdir = dir
    var mfile: String? = null
    val targets = ArrayList<Tok>()
    var i = 1
    while (i < toks.size) {
        val v = toks[i].v
        if (i > 1 && toks[i].s - toks[i - 1].e >= 2) break
        if (v == "-" || v == "—" || v == "–" || v == "#" || v == "|") break
        if (targets.isNotEmpty() && CAP_WORD.containsMatchIn(v)) break
        if (v == "-C" || v == "--directory") {
            val d = toks.getOrNull(++i) ?: return
            val n = normalizeRel(posixJoin(dir, d.v))
            if (n == null || !isLiteralPath(d.v)) return
            mdir = if (ctx.isDirRel(n)) n else normalizeRel(d.v) ?: return
            if (!ctx.isDirRel(mdir)) return
            i++; continue
        }
        if (v.startsWith("-C") && v.length > 2) {
            val n = normalizeRel(posixJoin(dir, v.substring(2)))
            if (n == null || !ctx.isDirRel(n)) return
            mdir = n; i++; continue
        }
        if (v == "-f" || v == "--file" || v == "--makefile") { val f = toks.getOrNull(++i) ?: return; mfile = f.v; i++; continue }
        if (v.startsWith("--directory=")) {
            val n = normalizeRel(posixJoin(dir, v.substring(12)))
            if (n == null || !ctx.isDirRel(n)) return
            mdir = n; i++; continue
        }
        if (v.startsWith("--file=") || v.startsWith("--makefile=")) { mfile = v.split('=')[1]; i++; continue }
        if (v.startsWith("-")) {
            if (v in MAKE_VALUE_FLAGS) i++ else if (v == "-j" && i + 1 < toks.size && NUM.matches(toks[i + 1].v)) i++
            i++; continue
        }
        if (ASSIGN.containsMatchIn(v)) { i++; continue }
        targets.add(toks[i])
        if (targets.size >= MAX_MAKE_TARGETS) break
        i++
    }
    if (targets.isEmpty()) return
    var makefiles = ArrayList<String>()
    if (mfile != null) {
        if (!isLiteralPath(mfile)) return
        val f = normalizeRel(posixJoin(mdir, mfile)) ?: return
        if (!ctx.hasFile(f)) {
            emit(mkDiag("CD001", Severity.WARNING, rel, src.line, toks[0].s, toks.last().e, "`make -f $mfile`: $f does not exist.", data = mapOf("kind" to "make")))
            return
        }
        makefiles = arrayListOf(f)
    } else {
        val near = ancestors(mdir).firstNotNullOfOrNull { ctx.makefileIn(it) }
        val rootMk = ctx.makefileIn("")
        if (dir == mdir) {
            if (near != null) makefiles.add(near)
            if (rootMk != null && rootMk !in makefiles) makefiles.add(rootMk)
        } else ctx.makefileIn(mdir)?.let { makefiles.add(it) }
    }
    for (t in targets) {
        if (!isLiteralName(t.v)) continue
        addVerb(facts, VERB_SYNONYMS[t.v], "make:${t.v}", "make ${t.v}", src.line, toks[0].s, t.e)
    }
    if (makefiles.isEmpty()) {
        val any = ctx.filesNamed(*MAKEFILE_NAMES.toTypedArray())
        val generated = ctx.filesNamed("configure", "configure.ac", "Makefile.am", "Makefile.in", "CMakeLists.txt", "meson.build", "Makefile.PL", "premake5.lua").isNotEmpty()
        if (generated || any.isNotEmpty()) return
        val otherRunner = ctx.filesNamed(*(JUSTFILE_NAMES + TASKFILE_NAMES + listOf("package.json", "pyproject.toml", "Cargo.toml", "go.mod")).toTypedArray()).isNotEmpty()
        if (!otherRunner) return
        val t = targets[0]
        emit(mkDiag("CD001", Severity.WARNING, rel, src.line, toks[0].s, t.e, "`make ${t.v}`: the repo has no Makefile.", data = mapOf("kind" to "make")))
        return
    }
    val parsed = makefiles.map { ctx.parseMakefile(it, 0) }
    if (parsed.any { it.uncertain }) return
    for (t in targets) {
        if (!isLiteralName(t.v)) continue
        if (parsed.any { t.v in it.targets }) continue
        if (ctx.existsRel(posixJoin(mdir, t.v) ?: t.v)) continue
        if (listOf("c", "cc", "cpp", "f", "p", "s", "y", "l", "go").any { e -> ctx.hasFile(posixJoin(mdir, t.v + "." + e) ?: "") }) continue
        if (subMakefilesDefine(ctx, mdir, t.v)) continue
        val all = LinkedHashSet<String>()
        for (p in parsed) all.addAll(p.targets)
        val sugg = closest(t.v, all)
        val hard = posixDirname(makefiles[0]) == mdir
        val fix = sugg?.let { Fix("Replace with \"$it\"", src.line, t.s, src.line, t.e, it) }
        emit(mkDiag("CD001", if (hard) Severity.ERROR else Severity.WARNING, rel, src.line, t.s, t.e,
            "`make ${t.v}`: no target \"${t.v}\" in ${makefiles.joinToString(", ")}" + (if (sugg != null) ". Did you mean \"$sugg\"?" else "."),
            fix = fix, data = mapOf("kind" to "make", "manifest" to makefiles[0], "target" to t.v)))
    }
}

private fun subMakefilesDefine(ctx: RepoContext, dir: String, target: String): Boolean {
    var n = 0
    for (f in ctx.filesNamed(*MAKEFILE_NAMES.toTypedArray())) {
        if (dir.isNotEmpty() && !f.startsWith("$dir/")) continue
        if (posixDirname(f) == dir) continue
        if (++n > 50) return false
        val p = ctx.parseMakefile(f, 0)
        if (target in p.targets || p.uncertain) return true
    }
    return false
}

private val JUST_STOP = setOf("--list", "-l", "--summary", "--evaluate", "--choose", "--show", "-s", "--dump", "--fmt", "--init", "--edit", "-e", "--help", "--version")
private val JUST_BAD = Regex("::|/")
private val JUST_ASSIGN = Regex("^[A-Za-z_][\\w-]*=")

private fun checkJust(ctx: RepoContext, rel: String, src: CmdSource, toks: List<Tok>, dir: String, emit: Emit, facts: FileFacts) {
    var i = 1
    var jfile: String? = null
    while (i < toks.size && toks[i].v.startsWith("-")) {
        val v = toks[i].v
        if (v == "-f" || v == "--justfile") { jfile = toks.getOrNull(i + 1)?.v; i += 2; continue }
        if (v == "-d" || v == "--working-directory" || v == "--set") { i += 2; continue }
        if (v in JUST_STOP) return
        i++
    }
    val t = toks.getOrNull(i) ?: return
    if (!isLiteralName(t.v) || JUST_BAD.containsMatchIn(t.v) || JUST_ASSIGN.containsMatchIn(t.v)) return
    if (i > 1 && t.s - toks[i - 1].e >= 2) return
    addVerb(facts, VERB_SYNONYMS[t.v], "just:${t.v}", "just ${t.v}", src.line, toks[0].s, t.e)
    val files = ArrayList<String>()
    if (jfile != null) {
        if (!isLiteralPath(jfile)) return
        val f = normalizeRel(posixJoin(dir, jfile))
        if (f == null || !ctx.hasFile(f)) return
        files.add(f)
    } else {
        ancestors(dir).firstNotNullOfOrNull { ctx.justfileIn(it) }?.let { files.add(it) }
        ctx.justfileIn("")?.let { if (it !in files) files.add(it) }
    }
    if (files.isEmpty()) {
        if (ctx.filesNamed(*JUSTFILE_NAMES.toTypedArray()).isNotEmpty()) return
        val otherRunner = ctx.filesNamed(*(MAKEFILE_NAMES + TASKFILE_NAMES + listOf("package.json", "pyproject.toml", "Cargo.toml", "go.mod")).toTypedArray()).isNotEmpty()
        if (!otherRunner) return
        emit(mkDiag("CD001", Severity.WARNING, rel, src.line, toks[0].s, t.e, "`just ${t.v}`: the repo has no justfile.", data = mapOf("kind" to "just")))
        return
    }
    val parsed = files.map { ctx.parseJustfile(it, 0) }
    if (parsed.any { it.uncertain }) return
    if (parsed.any { t.v in it.recipes || t.v in it.modules }) return
    val all = LinkedHashSet<String>()
    for (p in parsed) all.addAll(p.recipes)
    val sugg = closest(t.v, all)
    val fix = sugg?.let { Fix("Replace with \"$it\"", src.line, t.s, src.line, t.e, it) }
    emit(mkDiag("CD001", Severity.WARNING, rel, src.line, t.s, t.e,
        "`just ${t.v}`: no recipe \"${t.v}\" in ${files.joinToString(", ")}" + (if (sugg != null) ". Did you mean \"$sugg\"?" else "."),
        fix = fix, data = mapOf("kind" to "just", "manifest" to files[0], "target" to t.v)))
}

private val TASK_STOP = setOf("--list", "-l", "--list-all", "-a", "--init", "-i", "--help", "-h", "--version", "--summary")
private val TASK_VALUE = setOf("-d", "--dir", "-t", "--taskfile", "-o", "--output", "-c", "--concurrency", "-I", "--interval")

private fun checkTask(ctx: RepoContext, rel: String, src: CmdSource, toks: List<Tok>, dir: String, emit: Emit, facts: FileFacts) {
    var i = 1
    while (i < toks.size && toks[i].v.startsWith("-")) {
        val v = toks[i].v
        if (v in TASK_STOP) return
        if (v in TASK_VALUE) i++
        i++
    }
    val t = toks.getOrNull(i) ?: return
    if (!isLiteralName(t.v) || t.v.contains('=')) return
    val near = ancestors(dir).firstNotNullOfOrNull { ctx.taskfileIn(it) } ?: return
    addVerb(facts, VERB_SYNONYMS[t.v], "task:${t.v}", "task ${t.v}", src.line, toks[0].s, t.e)
    val p = ctx.parseTaskfile(near)
    if (p.uncertain && t.v.contains(':')) return
    if (t.v in p.tasks || p.patterns.any { g -> globMatch(g, t.v.replace(':', '/')) }) return
    if (p.uncertain) return
    val sugg = closest(t.v, p.tasks)
    emit(mkDiag("CD001", Severity.WARNING, rel, src.line, t.s, t.e,
        "`task ${t.v}`: no task \"${t.v}\" in $near" + (if (sugg != null) ". Did you mean \"$sugg\"?" else "."),
        data = mapOf("kind" to "task", "manifest" to near, "target" to t.v)))
}

private val CARGO_SUB = Regex("^[a-z][\\w-]*$")
private val GLOB_CHARS = Regex("[*?]")

private fun checkCargo(ctx: RepoContext, rel: String, src: CmdSource, toks: List<Tok>, emit: Emit, facts: FileFacts) {
    var i = 1
    while (i < toks.size && toks[i].v.startsWith("-")) i++
    if (i < toks.size && toks[i].v.startsWith("+")) { i++; while (i < toks.size && toks[i].v.startsWith("-")) i++ }
    val sub = toks.getOrNull(i) ?: return
    val f2 = ctx.cargoFacts()
    if (f2.manifests.isEmpty()) return
    val s = sub.v
    val vmap = mapOf("test" to "test", "t" to "test", "build" to "build", "b" to "build", "fmt" to "format", "clippy" to "lint", "nextest" to "test")
    vmap[s]?.let { addVerb(facts, it, "cargo:" + (if (s == "t") "test" else if (s == "b") "build" else s), "cargo $s", src.line, toks[0].s, sub.e) }
    if (s !in CARGO_BUILTINS && s !in f2.aliases && isLiteralName(s) && CARGO_SUB.matches(s)) {
        if (f2.hasAliasTable || s == "xtask") {
            emit(mkDiag("CD001", Severity.WARNING, rel, src.line, sub.s, sub.e,
                "`cargo $s`: not a built-in command and not an alias in .cargo/config.toml" + (if (f2.aliases.isNotEmpty()) " (aliases: ${f2.aliases.joinToString(", ")})" else "") + ". If it is an installed plugin (cargo-$s), ignore this.",
                data = mapOf("kind" to "cargo-alias")))
        }
        return
    }
    var j = i + 1
    while (j < toks.size) {
        val v = toks[j].v
        if (v == "--") break
        var name: String? = null
        var tok: Tok? = null
        var what: String? = null
        if ((v == "-p" || v == "--package") && j + 1 < toks.size) { tok = toks[j + 1]; name = tok.v; what = "package"; j++ }
        else if (v.startsWith("--package=")) { tok = toks[j]; name = v.substring(10); what = "package" }
        else if (v.startsWith("-p") && v.length > 2 && !v.startsWith("--")) { tok = toks[j]; name = v.substring(2); what = "package" }
        else if (v == "--bin" && j + 1 < toks.size) { tok = toks[j + 1]; name = tok.v; what = "bin"; j++ }
        j++
        if (name == null || tok == null || !isLiteralName(name) || GLOB_CHARS.containsMatchIn(name)) continue
        name = name.replace(Regex("@.*$"), "")
        if (what == "package" && f2.packages.isNotEmpty() && name !in f2.packages) {
            val sugg = closest(name, f2.packages)
            emit(mkDiag("CD001", Severity.WARNING, rel, src.line, tok.s, tok.e,
                "`cargo $s -p $name`: no crate named \"$name\" in the workspace" + (if (sugg != null) ". Did you mean \"$sugg\"?" else "."), data = mapOf("kind" to "cargo-package")))
        }
        if (what == "bin" && f2.bins.isNotEmpty() && name !in f2.bins) {
            emit(mkDiag("CD001", Severity.WARNING, rel, src.line, tok.s, tok.e, "`cargo $s --bin $name`: no binary target \"$name\".", data = mapOf("kind" to "cargo-bin")))
        }
    }
}

private val UV_VALUE_FLAGS = setOf("--with", "--with-editable", "--with-requirements", "--extra", "--group", "--only-group", "--no-group", "--package", "--project", "--directory", "--python", "-p", "--env-file", "--index", "--index-url", "--extra-index-url", "--isolated-not", "-C", "--config-setting", "--script-not")
private val PY_BIN_ALIASES = mapOf(
    "pip-tools" to listOf("pip-compile", "pip-sync"), "jupyterlab" to listOf("jupyter-lab", "jupyter"), "notebook" to listOf("jupyter-notebook", "jupyter"),
    "django" to listOf("django-admin"), "sphinx" to listOf("sphinx-build", "sphinx-apidoc", "sphinx-quickstart"), "mkdocs-material" to listOf("mkdocs"),
    "python-semantic-release" to listOf("semantic-release"), "build" to listOf("pyproject-build"), "jupyter-core" to listOf("jupyter"),
    "nbconvert" to listOf("jupyter-nbconvert"), "datamodel-code-generator" to listOf("datamodel-codegen"), "langgraph-cli" to listOf("langgraph"),
    "huggingface-hub" to listOf("huggingface-cli", "hf"), "grpcio-tools" to listOf("python-grpc-tools-protoc"), "setuptools" to listOf("easy_install"),
)
private val PY_PATHISH = Regex("[./\\\\]")

private fun checkPyRun(ctx: RepoContext, rel: String, src: CmdSource, toks: List<Tok>, emit: Emit, facts: FileFacts) {
    val tool = toks[0].v
    var i = 1
    while (i < toks.size && toks[i].v.startsWith("-")) { if (toks[i].v in UV_VALUE_FLAGS) i++; i++ }
    if (toks.getOrNull(i)?.v != "run") return
    i++
    while (i < toks.size && toks[i].v.startsWith("-")) {
        val v = toks[i].v
        if (v == "-m" || v == "--module" || v == "--script" || v == "-s") return
        if (v == "--") { i++; break }
        if (v in UV_VALUE_FLAGS) i++
        i++
    }
    val t = toks.getOrNull(i) ?: return
    recordToolVerb(facts, toks.subList(i, toks.size), src)
    val x = t.v
    if (!isLiteralName(x) || PY_PATHISH.containsMatchIn(x)) return
    val py = ctx.pythonFacts()
    if (py.pyprojects.isEmpty()) return
    if (x in py.scripts || x in PY_KNOWN_BINS || normPyName(x) in py.deps) return
    for (d in py.deps) if (x.startsWith(d) || d.startsWith(normPyName(x))) return
    for (d in py.deps) if (x in (PY_BIN_ALIASES[d] ?: emptyList())) return
    for (v in listOf(".venv/bin/", "venv/bin/", ".venv/Scripts/")) if (java.nio.file.Files.exists(ctx.abs(v + x))) return
    emit(mkDiag("CD001", Severity.WARNING, rel, src.line, t.s, t.e,
        "`$tool run $x`: \"$x\" is not a script in pyproject.toml ([project.scripts]/[tool.poetry.scripts]) and not a declared dependency.",
        data = mapOf("kind" to "py-script", "manifest" to py.pyprojects[0])))
}

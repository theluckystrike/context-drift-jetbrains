package zovo.contextdrift.engine

import java.net.URLDecoder

/* CD002 path drift, ported from engine.js. */

private val OUTPUT_DIRS = setOf("out", "tmp", "temp", "logs", "log", ".env", "reports", "artifacts", "outputs", "output", "generated", "lib-cjs", "cjs", "esm", "storybook-static", "_site", "playwright-report", "test-results", "htmlcov", "blob-report")
private val GENERATED_SEG = Regex("generated", RegexOption.IGNORE_CASE)

/** Build-output, scratch and gitignored paths are never claims about the source tree. Works on globs too. */
internal fun looksIgnoredOrGenerated(ctx: RepoContext, rel: String?): Boolean {
    if (rel.isNullOrEmpty()) return false
    val segs = rel.split('/').filter { it.isNotEmpty() }
    if (segs.isEmpty()) return false
    if (segs[0] in OUTPUT_DIRS) return true
    if (segs.any { it in IGNORED_DIRS }) return true
    if (segs.drop(1).any { it == "cjs" || it == "esm" || it == "lib-cjs" || it == "out" }) return true
    if (segs.any { GENERATED_SEG.containsMatchIn(it) }) return true
    return ctx.isGitignored(rel)
}

private val PATH_EXTS = ("md mdx mdc markdown txt json jsonc json5 yaml yml toml ini cfg conf js cjs mjs jsx ts cts mts tsx py pyi ipynb rb go rs java kt kts swift " +
    "c h cc cpp cxx hpp hh m mm cs fs php sh bash zsh fish ps1 bat sql graphql gql proto html htm css scss sass less vue svelte astro xml gradle lock " +
    "mk tf tfvars hcl csv tsv prisma zig ex exs erl hrl dart lua r jl scala sc clj cljs ml mli nix cmake bzl bazel star rst adoc svg png jpg jpeg gif webp ico " +
    "wasm env example sample tpl tmpl j2 jinja hbs ejs pug liquid njk pem crt plist entitlements xcconfig pbxproj storyboard xib sol move cairo nim v cr hs elm rkt " +
    "mdx http rest bru vsix gradle properties dockerfile containerfile service desktop patch diff spec snap").split(Regex("\\s+")).toSet()
private val BARE_NAMES = setOf("Makefile", "GNUmakefile", "Dockerfile", "Containerfile", "Justfile", "justfile", "Procfile", "Gemfile", "Rakefile", "Vagrantfile", "Brewfile", "CODEOWNERS", "Taskfile", "Earthfile", "Tiltfile", "Pipfile", "BUILD", "WORKSPACE")
private val KNOWN_DOTFILES = setOf(".nvmrc", ".node-version", ".python-version", ".tool-versions", ".editorconfig", ".prettierrc", ".eslintrc", ".npmrc", ".yarnrc", ".gitattributes", ".dockerignore", ".prettierignore", ".eslintignore", ".babelrc", ".swcrc", ".ruby-version", ".terraform-version", ".mise.toml", ".envrc", ".pre-commit-config.yaml", ".golangci.yml", ".golangci.yaml", ".markdownlint.json", ".lintstagedrc", ".czrc", ".releaserc", ".mocharc", ".c8rc", ".nycrc", ".stylelintrc", ".browserslistrc", ".huskyrc", ".commitlintrc")
private val BUILD_OUTPUT_NAMES = setOf("main.js", "bundle.js", "app.js", "styles.css", "output.css", "main.css", "index.css", "bundle.css", "manifest.json", "data.json", "sw.js", "service-worker.js", "worker.js", "server.js", "app.py", "out.json", "report.json", "results.json", "output.json", "log.txt")
private val GENERIC_FILE_MENTIONS = setOf("CLAUDE.md", "CLAUDE.local.md", "AGENTS.md", "AGENT.md", "GEMINI.md", "SKILL.md", ".cursorrules", ".windsurfrules", ".clinerules", "copilot-instructions.md", "settings.json", "settings.local.json", "llms.txt", "llms-full.txt", "MEMORY.md", "mcp.json", ".mcp.json", "config.toml", "config.json", "config.yaml", "config.yml", "index.html", "index.js", "index.ts", "main.py", "main.go", "main.rs", "lib.rs", "mod.rs", "__init__.py", "conftest.py", "README.md", "CHANGELOG.md", "CONTRIBUTING.md", "LICENSE", "LICENSE.md", "TODO.md", "NOTES.md", "PLAN.md", "SPEC.md", "STATUS.json", "DONE", "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lockb", "bun.lock", "Cargo.lock", "poetry.lock", "uv.lock", "go.sum", "npm-shrinkwrap.json", "Gemfile.lock", "composer.lock", "Pipfile.lock", "tsconfig.json", "jsconfig.json", "vite.config.ts", "vite.config.js", "next.config.js", "next.config.mjs", "next.config.ts", ".env", ".env.local", ".env.example", "docker-compose.yml", "docker-compose.yaml", "compose.yaml", "compose.yml")

internal class PathCandidate(
    val path: String, val clean: String, val segs: List<String>, val last: String, val ext: String,
    val hasSlash: Boolean, val trailing: Boolean, val glob: Boolean, val explicitRel: Boolean, val rootRel: Boolean,
)

private val IC = RegexOption.IGNORE_CASE
private val WS_ANY = Regex("\\s")
private val WIN_REL = Regex("""^(?:\.{1,2}\\)?[\w@.+-]+(?:\\[\w@.+-]+)+\\?$""")
private val SCHEME = Regex("^[a-z][a-z0-9+.-]*:", IC)
private val DRIVE = Regex("""^[A-Za-z]:[\\/]""")
private val FILE_LINE = Regex("""^(.+?\.[A-Za-z0-9]+):\d+(?::\d+)?$""")
private val QUERY_HASH = Regex("[?#].*$")
private val LINE_SUFFIX = Regex(""":\d+(?::\d+)?$""")
private val GH_LINE = Regex("""#L\d+(?:-L?\d+)?$""")
private val PYTEST_ID = Regex("""::[\w.\[\]-]+$""")
private val MD_ANCHOR = Regex("""(\.[A-Za-z0-9]+)#[\w.-]+$""")
private val TS_SYMBOL = Regex("""(\.[A-Za-z0-9]{1,8}):[A-Za-z_$][\w$.]*$""")
private val BRACE_ALT = Regex("""\{[^}]*,[^}]*\}""")
private val CODE_CHARS = Regex("""[<>{}$%|`"'(),;=!^&]""")
private val PLACEHOLDER_WORDS = Regex("""\.\.\.|\bpath/to\b|\b(?:your|my|some|example|sample|foo|bar|xxx|yyy|NNN|YYYY|MM-DD)\b""", IC)
private val PLACEHOLDER_PREFIX = Regex("""(?:^|[^A-Za-z])(?:your|my|some|example|sample|new|other)[_-]""", IC)
private val PLACEHOLDER_NNN = Regex("""(?:^|[^A-Za-z])(?:NNN|XXX|YYYY|N{2,})(?:[_-]|$)""")
private val PLACEHOLDER_MY = Regex("""(?:^|[^A-Za-z])my(?:command|app|project|module|service|file|package|tool|component|feature|plugin|lib|crate|func|handler|test|script|repo|class|widget|page|route|model|view|api|skill|agent)s?\b""", IC)
private val NAMING = Regex("""(?:^|/)(?:camelCase|PascalCase|kebab-case|snake_case|SCREAMING_SNAKE_CASE|lowercase|UPPERCASE)\.\w+$""")
private val GENERIC_NAME = Regex("""(?:^|/)(?:name|filename|file|component|module|feature|thing|x|xyz|abc|foo|bar|my|your|some|example|sample)\.(?:[a-z0-9]+\.)?[a-z0-9]+$""", IC)
private val EXT_TAIL = Regex("""\.[A-Za-z0-9]{1,8}$""")
private val DOTS = Regex("""^(?:\.{1,2})$""")
private val EXT_RE = Regex("""\.([A-Za-z0-9]+)$""")
private val DOMAIN = Regex("""^[\w-]+(?:\.[\w-]+)*\.(?:com|org|net|io|dev|ai|app|co|sh|me|so|xyz|gg|ly|edu|gov|cloud|page|site|tech|run)$""", IC)
private val VERSIONISH = Regex("""^\d+(?:\.\d+)*$""")
private val V_PREFIX = Regex("""^v\d""")
private val DATEISH = Regex("""^[\d/.-]+$""")
private val ONLY_EXTS = Regex("""^(?:\.[A-Za-z0-9]{1,4})+$""")
private val SUFFIX_PATTERN = Regex("""^\.[a-z][\w-]*\.[a-z0-9]+$""")
private val DOTFILE_EXT = Regex("""\.(json|js|cjs|mjs|ya?ml|toml)$""")
private val ENV_DOT = Regex("""^\.env\.""")
private val STAR_DOT = Regex("""^\*\*?\.""")
private val BRACKETS = Regex("""\[[^\]]*\]""")
private val HOSTNAME = Regex("""^[a-z0-9-]+(?:\.[a-z0-9-]+){2,}$""")
private val HOST_TLD = Regex("""\.(?:md|sh|rs|py|cc|ml|so|ws|ps|io|ai|dev)$""")

internal fun classifyPathCandidate(raw: String, isLink: Boolean): PathCandidate? {
    var p = raw.trim()
    if (p.isEmpty() || p.length > 260 || WS_ANY.containsMatchIn(p)) return null
    if (!isLink && !p.contains('/') && WIN_REL.matches(p)) p = p.replace('\\', '/')
    if (SCHEME.containsMatchIn(p) && !DRIVE.containsMatchIn(p)) {
        val lc = FILE_LINE.find(p) ?: return null
        p = lc.groupValues[1]
    }
    if (isLink) {
        if (p.startsWith("#") || p.startsWith("//") || p.startsWith("mailto:")) return null
        p = p.replace(QUERY_HASH, "")
        p = try { decodeUriComponent(p) } catch (_: Exception) { p }
        if (p.isEmpty()) return null
    } else {
        p = p.replace(LINE_SUFFIX, "").replace(GH_LINE, "")
        p = p.replace(PYTEST_ID, "")
        p = p.replace(MD_ANCHOR, "$1")
        p = p.replace(TS_SYMBOL, "$1")
    }
    if (p.startsWith("~") || DRIVE.containsMatchIn(p) || p.startsWith("\\\\")) return null
    if (p.startsWith("/") && !isLink) return null
    if (p.startsWith("@") || p.startsWith("-") || p.startsWith("$") || p.startsWith("%")) return null
    if (CODE_CHARS.containsMatchIn(p.replace(BRACE_ALT, ""))) return null
    if (PLACEHOLDER_WORDS.containsMatchIn(p)) return null
    if (PLACEHOLDER_PREFIX.containsMatchIn(p)) return null
    if (PLACEHOLDER_NNN.containsMatchIn(p)) return null
    if (PLACEHOLDER_MY.containsMatchIn(p)) return null
    if (NAMING.containsMatchIn(p)) return null
    if (GENERIC_NAME.containsMatchIn(p)) return null
    if (isLink && p.startsWith("/") && !EXT_TAIL.containsMatchIn(p.removeSuffix("/"))) return null
    if (DOTS.matches(p)) return null
    val glob = isGlob(p)
    val clean = p.removePrefix("./")
    val segs = clean.split('/').filter { it.isNotEmpty() }
    if (segs.isEmpty()) return null
    val last = segs.last()
    val ext = EXT_RE.find(last)?.groupValues?.get(1) ?: ""
    val hasSlash = clean.contains('/')
    val trailing = p.endsWith("/")
    if (DOMAIN.matches(segs[0]) && hasSlash) return null
    if (VERSIONISH.matches(clean) || V_PREFIX.containsMatchIn(clean)) return null
    if (DATEISH.matches(clean)) return null
    if (ONLY_EXTS.matches(clean)) return null
    if (!clean.contains('/') && SUFFIX_PATTERN.matches(clean) && clean.replace(DOTFILE_EXT, "") !in KNOWN_DOTFILES && !ENV_DOT.containsMatchIn(clean)) return null
    if (glob) {
        val fileGlob = hasSlash || STAR_DOT.containsMatchIn(last) || (ext.isNotEmpty() && ext in PATH_EXTS)
        if (!fileGlob) return null
        if (!hasSlash && BRACKETS.containsMatchIn(p)) return null
    }
    if (!hasSlash && HOSTNAME.matches(clean) && HOST_TLD.containsMatchIn(clean)) return null
    return PathCandidate(p, clean, segs, last, ext, hasSlash, trailing, glob, p.startsWith("./") || p.startsWith("../"), p.startsWith("/"))
}

/** JS decodeURIComponent: percent-decodes UTF-8 and throws on malformed input; "+" stays "+". */
private fun decodeUriComponent(s: String): String {
    if (!s.contains('%')) return s
    if (Regex("%(?![0-9A-Fa-f]{2})").containsMatchIn(s)) throw IllegalArgumentException("malformed")
    return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
}

internal val SKILL_BUNDLE_PATH = Regex("""^\.?/?(?:scripts|references|reference|assets|templates|examples|resources)/\S+$""")
private val RUNTIME_FILE = Regex("""(?:^|[-_.])(?:state|session|sessions|cache|history|receipts?|tokens?|auth|credentials?|secrets?|pid|queue|checkpoint|progress)(?:[-_.][\w-]*)?\.(?:json|jsonl|ya?ml|db|sqlite3?|log|lock|txt|toml|pid)$""", IC)
private val KNOWN_REPO_DOTDIRS = setOf(".github", ".gitlab", ".vscode", ".cursor", ".husky", ".devcontainer", ".circleci", ".changeset", ".storybook", ".config", ".cargo", ".claude", ".agents", ".windsurf", ".clinerules", ".idea", ".gitea", ".forgejo", ".buildkite", ".azure-pipelines", ".yarn", ".mvn", ".ddev", ".docker", ".k8s", ".helm", ".zed")
private val MODULE_EXTS = listOf("ts", "tsx", "js", "jsx", "mjs", "cjs", "mts", "cts", "vue", "svelte", "astro", "py", "go", "rs", "rb", "java", "kt", "swift", "dart", "ex", "php", "cs", "md", "mdx", "json", "sh")

private fun existsAsModule(ctx: RepoContext, rel: String?): Boolean {
    if (rel.isNullOrEmpty()) return false
    for (e in MODULE_EXTS) if (ctx.hasFile("$rel.$e")) return true
    if (ctx.isDirRel(rel)) return true
    for (e in MODULE_EXTS) if (ctx.hasFile("$rel/index.$e")) return true
    return false
}

/** Folders a SKILL.md may resolve bundle paths against: its own folder and the enclosing plugin root. */
internal class SkillRoots(val all: List<String>, val bundle: List<String>)

internal fun skillRoots(ctx: RepoContext, instrDir: String): SkillRoots {
    val out = arrayListOf(instrDir)
    for (d in ancestors(instrDir).drop(1)) {
        fun at(n: String) = if (d.isNotEmpty()) "$d/$n" else n
        if (ctx.isDirRel(at(".claude-plugin")) || ctx.isDirRel(at(".codex-plugin")) || ctx.hasFile(at("plugin.json"))) { out.add(d); break }
    }
    val parent = posixDirname(instrDir)
    if (parent.substring(parent.lastIndexOf('/') + 1) == "skills") { val pr = posixDirname(parent); if (pr !in out) out.add(pr) }
    val bundle = out.toList()
    if ("" !in out) out.add("")
    return SkillRoots(out, bundle)
}

internal class PathStats(var checked: Int = 0, var missing: Int = 0, var globs: Int = 0, val budget: Budget = Budget(GLOB_WORK_PER_FILE))

private val URL_BEFORE = Regex("""\]\(https?:|https?://""", IC)
private val URL_PREV_END = Regex("""https?://\S*$""")
private val RELEASES = Regex("""\breleases/(?:latest|download)\b""")
private val IMPORT_BEFORE = Regex("""\b(?:from|import|require)\s*\(?\s*$""", IC)
private val SLUG_BEFORE = Regex("""`([\w.-]+)/([\w.-]+)`[^`]{0,40}$""")
private val EXT_ANY = Regex("""\.[a-z0-9]+$""", IC)
private val SLUG_JS = Regex("""^[\w-]+\.js$""", IC)
private val CAP_JS = Regex("""^[A-Z]\w*\.js$""")
private val ENV_FILE = Regex("""^\.env(\.|$)""")
private val ONE_WORD = Regex("""^[a-z]{1,10}\.[a-z]{1,5}$""")
private val HOME_ABS_SPAN = Regex("""`(?:~|/|[^`\s]*node_modules/)[^`\s]*`""")
private val SENT_END = Regex("[.;!?]")
private val GLOB_CHAR = Regex("[*?{]")
private val STAR_TAIL = Regex("""\*\*/?$|/\*/?$""")
private val LAST_HAS_EXT = Regex("""\.[A-Za-z0-9]+$""")
private val WILD_FIRST = Regex("[*?\\[{]")
private val TOOL_DOTDIR = Regex("""^\.[\w-]+(?:/|$)""")

internal fun checkPathCandidate(
    ctx: RepoContext, rel: String, instrDir: String, s: Span, isLink: Boolean,
    emit: (Diagnostic) -> Unit, skill: SkillRoots?, stats: PathStats?,
) {
    val c = classifyPathCandidate(s.text, isLink) ?: return
    if (c.glob && stats != null && stats.globs >= MAX_GLOBS_PER_FILE) return
    if (c.glob && (c.path.startsWith("#") || !c.hasSlash)) return
    val localOnly = skill != null
    if (s.heading.isNotEmpty() && EXAMPLE_HEADING.containsMatchIn(s.heading)) return
    val lineText = s.lineText
    if (negated(lineText, s.col, s.endCol, s.prevText)) return
    if (!isLink) {
        val b = stripSpans(contextBefore(lineText, s.col, s.prevText))
        if (createdBefore(b)) return
    }
    if (CREATE_AFTER.containsMatchIn(contextAfter(lineText, s.endCol)) || CREATE_AFTER.containsMatchIn(stripSpans(contextAfter(lineText, s.endCol)))) return
    if (!isLink && ELSEWHERE.containsMatchIn(sentenceAround(lineText, s.col, s.endCol, s.prevText))) return

    val lowerExt = c.ext.lowercase()
    val knownExt = c.ext.isNotEmpty() && c.ext in PATH_EXTS && c.ext == lowerExt
    val isDotfileName = !c.hasSlash && c.clean.replace(DOTFILE_EXT, "") in KNOWN_DOTFILES
    val bareKnown = !c.hasSlash && c.clean in BARE_NAMES
    val kind = when {
        c.glob -> "glob"
        c.hasSlash || c.explicitRel || isLink -> "path"
        knownExt || isDotfileName || bareKnown -> "bare"
        else -> return
    }

    if (!isLink && (URL_BEFORE.containsMatchIn(lineText.substring(0, minOf(s.col, lineText.length))) || (s.col < 3 && URL_PREV_END.containsMatchIn(s.prevText)))) return
    if (!isLink && RELEASES.containsMatchIn(c.clean)) return
    if (!isLink && c.explicitRel && c.ext.isEmpty() && IMPORT_BEFORE.containsMatchIn(stripSpans(contextBefore(lineText, s.col, s.prevText)))) return
    if (!isLink) {
        val before = contextBefore(lineText, s.col, s.prevText)
        val slug = SLUG_BEFORE.find(before)
        if (slug != null && !EXT_ANY.containsMatchIn(slug.groupValues[2]) && !ctx.isDirRel(slug.groupValues[1]) && !ctx.isDirRel(posixJoin(instrDir, slug.groupValues[1]) ?: "")) return
    }
    if (localOnly && c.ext.isEmpty() && !c.trailing && !isLink) return
    if (localOnly && !isLink && !skill!!.bundle.any { b -> ctx.isDirRel(posixJoin(b, c.segs[0]) ?: c.segs[0]) }) return
    val bases = if (localOnly) skill!!.all else listOf(instrDir)
    if (kind == "path" && !c.explicitRel && !c.trailing && !knownExt && !c.glob && !c.rootRel) {
        val first = c.segs[0]
        val firstExists = ctx.isDirRel(first) || bases.any { b -> ctx.isDirRel(posixJoin(b, first) ?: "") }
        if (!firstExists) return
    }
    if (kind == "path" && !isLink && !c.explicitRel && c.segs.size == 2 && SLUG_JS.matches(c.last) &&
        !ctx.isDirRel(c.segs[0]) && !ctx.isDirRel(posixJoin(instrDir, c.segs[0]) ?: "")) return
    if (kind == "bare") {
        if (c.clean in GENERIC_FILE_MENTIONS) return
        if (CAP_JS.matches(c.clean)) return
        if (ENV_FILE.containsMatchIn(c.clean)) return
        if (c.clean in BUILD_OUTPUT_NAMES || ctx.basenameGitignored(c.clean)) return
        if (ONE_WORD.matches(c.clean)) return
    }
    if ((kind == "bare" || kind == "path") && RUNTIME_FILE.containsMatchIn(c.last)) return

    if (!isLink && !c.rootRel) {
        val b = contextBefore(lineText, s.col, s.prevText)
        var lastM: MatchResult? = null
        for (m in HOME_ABS_SPAN.findAll(b)) lastM = m
        if (lastM != null && !SENT_END.containsMatchIn(stripSpans(b.substring(lastM.range.last + 1)))) return
    }
    var rcol = s.col + maxOf(0, s.text.indexOf(c.path))
    if (rcol == s.col && s.text.indexOf(c.path) < 0) rcol = s.col + maxOf(0, s.text.length - s.text.trimStart().length)
    val rEnd = rcol + minOf(c.path.length, if (s.endCol - rcol > 0) s.endCol - rcol else c.path.length)
    stats?.let { it.checked++ }

    if (kind == "glob") {
        if (stats != null && ++stats.globs > MAX_GLOBS_PER_FILE) return
        val gbases = if (localOnly) bases else LinkedHashSet(bases + ancestors(instrDir)).toList()
        for (b in gbases) if (ctx.existsRel(normalizeRel(posixJoin(b, c.clean)) ?: "")) return
        if (ctx.existsRel(normalizeRel(c.clean) ?: "")) return
        val wi = GLOB_CHAR.find(c.clean)?.range?.first ?: -1
        val litDir = (if (wi >= 0) c.clean.substring(0, wi) else c.clean.dropLast(1)).replace(Regex("/[^/]*$"), "")
        if (litDir.isNotEmpty() && STAR_TAIL.containsMatchIn(c.clean) && gbases.any { b -> ctx.isDirRel(normalizeRel(posixJoin(b, litDir)) ?: "") }) return
        val firstSeg = c.segs[0]
        val lastHasExt = LAST_HAS_EXT.containsMatchIn(c.last)
        val firstLiteral = !WILD_FIRST.containsMatchIn(firstSeg)
        if (!lastHasExt && firstLiteral && !gbases.any { b -> ctx.isDirRel(posixJoin(b, firstSeg) ?: firstSeg) }) return
        if (!lastHasExt && !firstLiteral && firstSeg != "**") return
        if (firstLiteral && !gbases.any { b -> ctx.isDirRel(posixJoin(b, firstSeg) ?: firstSeg) }) {
            val parents = ArrayList<String>()
            for (d in ctx.dirs) if (d.endsWith("/$firstSeg")) { parents.add(posixDirname(d)); if (parents.size >= 20) break }
            if (parents.isEmpty()) return
            val budget0 = stats?.budget ?: Budget(GLOB_WORK_PER_FILE)
            if (parents.any { p -> ctx.globMatchesAny(c.clean, p, budget0) }) return
        }
        val globPath = c.clean.removeSuffix("/")
        if (looksIgnoredOrGenerated(ctx, globPath) || bases.any { b -> looksIgnoredOrGenerated(ctx, normalizeRel(posixJoin(b, globPath)) ?: "") }) return
        val budget = stats?.budget ?: Budget(GLOB_WORK_PER_FILE)
        if (ctx.globMatchesAny(c.clean, "", budget) || gbases.any { b -> ctx.globMatchesAny(c.clean, b, budget) }) return
        emit(mkDiag("CD002", Severity.INFO, rel, s.line, rcol, rEnd, "Glob \"${c.path}\" matches no files in the repo.", data = mapOf("kind" to "glob", "glob" to c.path)))
        return
    }

    val target = c.clean.trimEnd('/')
    if (!c.rootRel && posixJoin(instrDir, target) == null) return
    if (TOOL_DOTDIR.containsMatchIn(target) && target.split('/')[0] !in KNOWN_REPO_DOTDIRS && (target.contains('/') || c.ext.isEmpty())) return
    val cands = ArrayList<String?>()
    if (c.rootRel) cands.add(normalizeRel(target))
    else {
        for (b in bases) cands.add(normalizeRel(posixJoin(b, target)))
        if (!localOnly && (!isLink || !c.explicitRel)) for (a in ancestors(instrDir).drop(1)) cands.add(normalizeRel(posixJoin(a, target)))
        if (!localOnly && !isLink) for (a in ancestors(instrDir)) cands.add(normalizeRel(posixJoin(posixJoin(a, "src") ?: "src", target)))
    }
    val valid = cands.filterNotNull().filter { it.isNotEmpty() }
    if (valid.isEmpty()) return
    val subs = ctx.submodulePaths()
    if (subs.isNotEmpty() && valid.any { x -> subs.any { sp -> x == sp || x.startsWith("$sp/") } }) return
    for (x in valid) if (if (c.trailing) ctx.isDirRel(x) else ctx.existsRel(x)) return
    if (!c.trailing && c.ext.isEmpty()) for (x in valid) if (existsAsModule(ctx, x)) return
    for (x in valid) if (looksIgnoredOrGenerated(ctx, x)) return
    if (looksIgnoredOrGenerated(ctx, target)) return
    val suffix = "/$target"
    if (kind == "bare") {
        if ((ctx.byBase[target] ?: emptyList()).isNotEmpty()) return
        if (target in ctx.dirs) return
        for (d in ctx.dirs) if (d.endsWith(suffix)) return
    } else if (!c.explicitRel && !isLink && !localOnly) {
        for (f in ctx.files) if (f.endsWith(suffix)) return
        for (d in ctx.dirs) if (d.endsWith(suffix)) return
        if (c.ext.isEmpty() && !c.trailing) for (f in ctx.files) { val i = f.lastIndexOf('.'); if (i > 0 && f.substring(0, i).endsWith(suffix)) return }
    }
    val sameBase = (ctx.byBase[c.last] ?: emptyList()).filter { !looksIgnoredOrGenerated(ctx, it) }
    val suggestion = if (sameBase.size == 1 && !c.trailing) sameBase[0] else null
    val fromDirSet = c.explicitRel || isLink
    val fixText = if (suggestion != null && !localOnly) relativeFrom(if (fromDirSet) instrDir else "", suggestion, c.explicitRel) else null
    val what = if (c.trailing) "Directory" else if (kind == "bare") "File" else "Path"
    val msg = "$what \"${c.path}\" does not exist in the repo" + (if (fixText != null) ". Did you mean \"$fixText\"?" else ".")
    val fix = fixText?.let { Fix("Replace with \"$it\"", s.line, rcol, s.line, rEnd, it) }
    emit(mkDiag("CD002", Severity.WARNING, rel, s.line, rcol, rEnd, msg, fix = fix,
        data = mapOf("kind" to "path", "path" to c.path, "resolved" to valid[0], "parent" to ctx.nearestExistingParent(valid[0]), "bare" to (kind == "bare"))))
}

internal fun relativeFrom(fromDir: String, target: String, keepDot: Boolean): String {
    if (fromDir.isEmpty()) return (if (keepDot) "./" else "") + target
    val a = fromDir.split('/')
    val b = target.split('/')
    var i = 0
    while (i < a.size && i < b.size - 1 && a[i] == b[i]) i++
    val up = a.size - i
    val r = "../".repeat(up) + b.drop(i).joinToString("/")
    return if (up == 0 && keepDot) "./$r" else r
}

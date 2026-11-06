package zovo.contextdrift.engine

internal class Tok(val v: String, val s: Int, val e: Int)
internal class Segment(val toks: List<Tok>, val dynamic: Boolean)

/** Splits a shell-ish line into segments of tokens with absolute columns. */
internal fun tokenizeShell(str: String, base: Int): List<Segment> {
    val segments = ArrayList<Segment>()
    var toks = ArrayList<Tok>()
    var dynamic = false
    fun push() { if (toks.isNotEmpty()) segments.add(Segment(toks, dynamic)); toks = ArrayList(); dynamic = false }
    var i = 0
    while (i < str.length) {
        val c = str[i]
        if (c == ' ' || c == '\t') { i++; continue }
        if (c == '#') break
        if (c == ';' || c == '|' || c == '&' || c == '\n' || c == '(' || c == ')') { push(); i++; continue }
        val s = i
        val v = StringBuilder()
        while (i < str.length) {
            val d = str[i]
            if (d == ' ' || d == '\t' || d == ';' || d == '|' || d == '&' || d == '\n' || d == ')') break
            if (d == '(' && v.isEmpty()) break
            if (d == '"' || d == '\'') {
                val end = str.indexOf(d, i + 1)
                if (end < 0) { v.append(str, i + 1, str.length); i = str.length; break }
                val inner = str.substring(i + 1, end)
                v.append(inner)
                if (d == '"' && (inner.contains("$(") || inner.contains('`'))) dynamic = true
                i = end + 1
                continue
            }
            if (d == '$' && i + 1 < str.length && str[i + 1] == '(') dynamic = true
            if (d == '`') dynamic = true
            if (d == '\\' && i + 1 < str.length) { v.append(str[i + 1]); i += 2; continue }
            v.append(d)
            i++
        }
        if (i > s) toks.add(Tok(v.toString(), base + s, base + i)) else i++
    }
    push()
    return segments
}

private val PREFIX_CMDS = setOf("sudo", "time", "nohup", "exec", "command", "env", "cross-env", "dotenv", "caffeinate", "timeout", "nice", "unbuffer", "xvfb-run", "op")
private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
private val TIMEOUT_ARG = Regex("^-|^\\d")

internal fun stripPrefixes(toks: List<Tok>): List<Tok> {
    var i = 0
    while (i < toks.size) {
        val v = toks[i].v
        if (ENV_ASSIGN.containsMatchIn(v)) { i++; continue }
        if (v in PREFIX_CMDS) {
            i++
            if (v == "timeout") { while (i < toks.size && TIMEOUT_ARG.containsMatchIn(toks[i].v)) i++ }
            else if (v == "op") {
                if (i < toks.size && toks[i].v == "run") i++
                while (i < toks.size && toks[i].v.startsWith("-")) i++
            } else while (i < toks.size && toks[i].v.startsWith("-") && toks[i].v != "--") i++
            if (i < toks.size && toks[i].v == "--") i++
            continue
        }
        break
    }
    return toks.subList(minOf(i, toks.size), toks.size)
}

private val NOT_LITERAL = Regex("""[<>{}$*?\[\]|`'"]|\.\.\.""")
private val PLACEHOLDER_NAME = Regex("""^(?:x+|foo|bar|baz|name|script|target|recipe|task|cmd|command|your-?\w*|my-?\w*|<.*)$""", RegexOption.IGNORE_CASE)
internal fun isLiteralName(s: String?): Boolean = !s.isNullOrEmpty() && !NOT_LITERAL.containsMatchIn(s) && !PLACEHOLDER_NAME.matches(s)

private val NOT_LITERAL_PATH = Regex("""[<>{}$*?|`]|\.\.\.|\bpath/to\b|\b(?:your|my|some|example|sample|foo|bar|xxx|project-name|app-name|repo-name)\b""", RegexOption.IGNORE_CASE)
internal fun isLiteralPath(p: String): Boolean = !NOT_LITERAL_PATH.containsMatchIn(p)

internal val NPM_RUN = setOf("run", "run-script", "rum", "urn")
internal val NPM_VALUE_FLAGS = setOf("-w", "--workspace", "--prefix", "-C", "--loglevel", "--userconfig", "--cache", "--registry")
internal val PNPM_VALUE_FLAGS = setOf("-F", "--filter", "-C", "--dir", "--loglevel", "--reporter", "--workspace-concurrency", "--filter-prod", "--test-pattern", "--changed-files-ignore-pattern", "--resume-from", "--cwd")
internal val YARN_VALUE_FLAGS = setOf("--cwd", "--modules-folder", "--cache-folder", "--mutex", "--network-timeout")
internal val BUN_VALUE_FLAGS = setOf("--cwd", "-c", "--config", "--filter", "-F", "--env-file", "--preload", "-r", "--tsconfig-override")
internal val ANY_PKG_FLAGS = setOf("-w", "--workspace", "--workspaces", "-ws", "--prefix", "-C", "--dir", "-F", "--filter", "-r", "--recursive", "--cwd", "--filter-prod", "--include-workspace-root", "--workspace-root")

internal val PNPM_BUILTINS = setOf("add", "install", "i", "update", "up", "upgrade", "remove", "rm", "un", "uninstall", "link", "ln", "unlink", "import", "rebuild", "rb", "prune", "fetch", "patch", "patch-commit", "patch-remove", "audit", "list", "ls", "ll", "la", "outdated", "why", "licenses", "exec", "dlx", "create", "init", "publish", "pack", "store", "root", "bin", "setup", "env", "deploy", "config", "c", "get", "set", "doctor", "server", "recursive", "help", "self-update", "cat-file", "cat-index", "find-hash", "approve-builds", "ignored-builds", "catalog", "dedupe", "completion", "install-test", "it", "version", "view", "info", "show", "login", "logout", "whoami", "owner", "dist-tag", "docs", "home", "repo", "bugs", "search", "unpublish", "deprecate", "undeprecate", "cache", "sbom", "x", "restart", "stop", "run", "test", "t", "tst", "start", "node", "ci", "clean", "update-interactive", "add-global", "runtime", "store-path", "pkg", "fund", "ping", "access", "token", "team", "star", "stars", "profile", "hook", "edit", "explore", "query", "shrinkwrap")
internal val YARN_BUILTINS = setOf("add", "install", "remove", "upgrade", "up", "init", "info", "why", "workspaces", "workspace", "dlx", "exec", "node", "bin", "cache", "config", "constraints", "dedupe", "explain", "npm", "pack", "patch", "patch-commit", "plugin", "rebuild", "set", "unplug", "version", "global", "link", "unlink", "login", "logout", "publish", "audit", "autoclean", "check", "create", "generate-lock-entry", "import", "licenses", "list", "outdated", "owner", "policies", "tag", "team", "upgrade-interactive", "help", "run", "test", "start", "stage", "search", "sdks", "tsc-not", "refresh", "remove-unused")
internal val BUN_BUILTINS = setOf("run", "test", "x", "repl", "exec", "init", "create", "c", "install", "i", "add", "a", "remove", "rm", "update", "outdated", "link", "unlink", "pm", "build", "upgrade", "publish", "audit", "info", "patch", "patch-commit", "why", "completions", "help", "feedback", "discord", "getcompletes", "ci")
internal val KNOWN_JS_BINS = setOf("tsc", "tsserver", "node", "eslint", "prettier", "jest", "vitest", "mocha", "ava", "tap", "c8", "nyc", "playwright", "cypress", "webpack", "rollup", "vite", "esbuild", "tsup", "tsx", "ts-node", "swc", "babel", "next", "nuxi", "nuxt", "astro", "remix", "svelte-kit", "svelte-check", "vue-tsc", "ng", "nest", "expo", "react-native", "turbo", "nx", "lerna", "changeset", "prisma", "drizzle-kit", "wrangler", "vercel", "netlify", "biome", "oxlint", "stylelint", "husky", "lint-staged", "concurrently", "rimraf", "serve", "http-server", "electron", "electron-builder", "tauri", "storybook", "sb", "typedoc", "jsdoc", "knip", "depcheck", "syncpack", "eslint_d", "tsc-alias", "vsce", "ovsx", "semantic-release", "release-it", "np", "graphql-codegen", "openapi-typescript", "orval", "size-limit", "madge", "commitlint", "cz", "sort-package-json", "pkg", "nodemon", "pm2", "zx", "shx", "cpy", "del-cli", "npm-run-all", "run-p", "run-s", "wireit", "react-scripts", "gatsby", "docusaurus", "vitepress", "vuepress", "eleventy", "hexo", "ts-jest", "tsd", "attw", "publint", "api-extractor", "lefthook", "markdownlint", "markdownlint-cli2", "cspell", "jscodeshift", "quasar", "ionic", "cap", "workbox", "mkdirp", "json", "dotenv", "env-cmd", "kysely", "knex", "sequelize", "typeorm", "mikro-orm", "supabase", "firebase", "convex", "sst", "cdk", "serverless", "sls", "pulumi", "cdktf", "turbowatch", "tsdown", "unbuild", "rolldown", "rspack", "rsbuild", "parcel", "snowpack", "jiti", "bunchee", "microbundle", "plop", "hygen", "degit", "patch-package", "only-allow", "npx")
internal val PY_KNOWN_BINS = setOf("python", "python3", "pip", "pip3", "pytest", "py.test", "ruff", "mypy", "black", "isort", "flake8", "pylint", "coverage", "tox", "nox", "pre-commit", "ipython", "jupyter", "jupyter-lab", "sphinx-build", "sphinx-autobuild", "mkdocs", "uvicorn", "gunicorn", "hypercorn", "alembic", "django-admin", "celery", "pyright", "basedpyright", "maturin", "fastapi", "flask", "streamlit", "gradio", "dagster", "airflow", "scrapy", "locust", "bandit", "safety", "pip-audit", "twine", "build", "hatch", "pdm", "poetry", "uv", "pyinstaller", "cython", "nuitka", "behave", "ward", "hypothesis", "pydocstyle", "autopep8", "yapf", "codespell", "interrogate", "vulture", "radon", "xenon", "deptry", "pyupgrade", "ty", "pyrefly", "marimo", "dvc", "mlflow", "huggingface-cli", "modal", "ansible", "ansible-playbook", "invoke", "inv", "doit", "scons", "sqlfluff", "dbt", "datasette", "litestar", "granian", "daphne", "textual", "pyside6-uic", "manage.py", "semgrep", "cookiecutter", "copier", "bumpversion", "bump-my-version", "towncrier", "commitizen", "cz", "mike", "griffe", "pdoc", "nbqa", "jupytext", "papermill")
internal val CARGO_BUILTINS = setOf("build", "b", "check", "c", "clean", "doc", "d", "new", "init", "add", "remove", "rm", "run", "r", "test", "t", "bench", "update", "search", "publish", "install", "uninstall", "fmt", "clippy", "fix", "tree", "metadata", "vendor", "package", "login", "logout", "owner", "yank", "help", "version", "generate-lockfile", "locate-project", "pkgid", "report", "rustc", "rustdoc", "fetch", "verify-project", "config", "info", "read-manifest", "git-checkout", "miri", "nextest", "llvm-cov", "audit", "deny", "watch", "expand", "machete", "insta", "make", "binstall", "edit", "udeps", "outdated", "criterion", "tarpaulin", "dist", "release", "hack", "about", "sweep", "mutants", "flamegraph", "leptos", "tauri", "shuttle", "lambda", "component", "ndk", "apk", "mobile", "web", "wasi", "xwin", "zigbuild", "cross", "semver-checks", "msrv", "bloat", "asm", "show-asm", "geiger", "sort", "upgrade", "set-version", "workspaces", "ws", "smart-release", "changelog", "public-api", "llvm-lines", "fuzz", "kani", "pgrx", "sqlx", "leptosfmt", "dylint", "careful", "auditable", "cyclonedx", "license", "minimal-versions", "xtask-not", "chef", "contracts", "stylus", "risczero", "shear", "autoinherit", "run-script", "cmd", "script", "generate", "zisk", "pgo", "bundle", "deb", "rpm", "wix", "aur", "appimage", "ui", "modules", "docs-rs", "rdme", "readme", "update-installed", "cache", "local-registry", "vet", "crev", "supply-chain", "acl", "all-features", "clean-all", "wasm", "binutils", "objdump", "nm", "size", "strip", "cov", "show")

internal val COMMANDS_OF_INTEREST = setOf("cd", "pushd", "npm", "pnpm", "yarn", "bun", "npx", "bunx", "pnpx", "make", "gmake", "just", "task", "go-task", "cargo", "uv", "poetry", "pdm", "rye",
    "go", "gofmt", "golangci-lint", "pytest", "py.test", "python", "python3", "ruff", "black", "mypy", "pyright", "eslint", "oxlint", "flake8", "pylint", "prettier", "biome", "vitest", "jest", "mocha", "ava", "tsc", "node", "deno")

internal val VERB_SYNONYMS = mapOf("test" to "test", "tests" to "test", "build" to "build", "lint" to "lint", "format" to "format", "fmt" to "format", "dev" to "dev", "typecheck" to "typecheck", "type-check" to "typecheck", "types" to "typecheck", "tsc" to "typecheck")

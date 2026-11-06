package zovo.contextdrift.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zovo.contextdrift.engine.TestRepos.pkg
import zovo.contextdrift.engine.TestRepos.run

/** One or more tests per rule, plus the helpers the quick fixes rely on. */
class RulesTest {
    private val npm = mapOf("package-lock.json" to "{}")
    private fun tuples(r: WorkspaceResult, rule: String? = null) =
        r.diagnostics.filter { rule == null || it.ruleId == rule }.map { "${it.ruleId} ${it.severity.label} ${it.line}:${it.column}-${it.endColumn}" }

    /* ---------- CD001 command drift ---------- */

    @Test fun cd001NpmRunTypoIsAnErrorWithAFix() {
        val r = run(mapOf("AGENTS.md" to "Build: `npm run buidl`.\n", "package.json" to pkg("build", "test")) + npm)
        val d = r.diagnostics.single { it.ruleId == "CD001" }
        assertEquals(Severity.ERROR, d.severity)
        assertEquals("Script \"buidl\" is not defined in package.json. Did you mean \"build\"?", d.message)
        assertEquals("build", d.fix?.newText)
        assertEquals(0 to 16, d.line to d.column)
    }

    @Test fun cd001MakeJustTaskAndCargo() {
        val r = run(mapOf(
            "AGENTS.md" to "Run `make lnt`, `just docs`, `task publish` and `cargo test -p clli`.\n",
            "Makefile" to "lint:\n\t@true\n", "justfile" to "build:\n  echo\n", "Taskfile.yml" to "version: '3'\ntasks:\n  build:\n    cmds: [echo]\n",
            "Cargo.toml" to "[workspace]\nmembers=[\"crates/cli\"]\n", "crates/cli/Cargo.toml" to "[package]\nname = \"cli\"\n",
        ))
        val msgs = r.diagnostics.filter { it.ruleId == "CD001" }.map { it.message }
        assertTrue(msgs.toString(), msgs.any { it.startsWith("`make lnt`: no target \"lnt\" in Makefile. Did you mean \"lint\"?") })
        assertTrue(msgs.toString(), msgs.any { it.startsWith("`just docs`") })
        assertTrue(msgs.toString(), msgs.any { it.startsWith("`task publish`") })
        assertTrue(msgs.toString(), msgs.any { it.contains("no crate named \"clli\"") })
    }

    @Test fun cd001NegatedAndPlaceholderCommandsAreSkipped() {
        val r = run(mapOf("AGENTS.md" to "Don't run `npm run eject`.\nRun `npm run <script>`.\n- Never use `npm run legacy` (removed).\n", "package.json" to pkg("build")) + npm)
        assertEquals(emptyList<String>(), tuples(r, "CD001"))
    }

    @Test fun cd001SkillFilesAreNotCheckedForCommands() {
        val r = run(mapOf("skills/x/SKILL.md" to "Run `npm run whatever`.\n", "package.json" to pkg("build")) + npm)
        assertEquals(emptyList<String>(), tuples(r, "CD001"))
    }

    /* ---------- CD002 path drift ---------- */

    @Test fun cd002MovedFileSuggestsTheNewPath() {
        val r = run(mapOf("AGENTS.md" to "Entry point: `src/server/main.ts`\n", "src/main.ts" to "x", "package.json" to pkg("build")) + npm)
        val d = r.diagnostics.single { it.ruleId == "CD002" }
        assertEquals("Path \"src/server/main.ts\" does not exist in the repo. Did you mean \"src/main.ts\"?", d.message)
        assertEquals("src/main.ts", d.fix?.newText)
    }

    @Test fun cd002CdIntoMissingFolderAndGlobNote() {
        val r = run(mapOf("AGENTS.md" to "Run `cd packages/api && npm test`.\nSpecs: `src/**/*.spec.ts`\n", "src/a.ts" to "x", "packages/web/x.ts" to "x", "package.json" to pkg("test")) + npm)
        assertEquals(listOf("CD002 warning 0:8-20", "CD002 info 1:8-24"), tuples(r, "CD002"))
    }

    @Test fun cd002GitignoredExampleAndUrlPathsAreSkipped() {
        val r = run(mapOf(
            "AGENTS.md" to "Logs go to `tmp/run.log`. Cache: `cache/data.bin`.\n## Example\n`src/never.ts`\nSee https://x.dev/a and `docs/b.md`.\n",
            ".gitignore" to "cache/\n", "src/a.ts" to "x", "docs/a.md" to "x", "package.json" to pkg(),
        ))
        assertEquals(emptyList<String>(), tuples(r, "CD002"))
    }

    @Test fun cd002CursorGlobThatMatchesNothing() {
        val r = run(mapOf(".cursor/rules/vue.mdc" to "---\nglobs: src/**/*.vue\nalwaysApply: false\n---\nUse the composition API.\n", "src/a.ts" to "x", "package.json" to pkg()))
        val d = r.diagnostics.single { it.ruleId == "CD002" }
        assertEquals(Severity.INFO, d.severity)
        assertEquals("globs pattern \"src/**/*.vue\" matches no files in the repo, so this rule never applies.", d.message)
    }

    /* ---------- CD003 package manager ---------- */

    @Test fun cd003WrongPackageManagerWithFix() {
        val r = run(mapOf("AGENTS.md" to "Install with `pnpm install`. We use yarn for everything.\n", "package.json" to pkg("build")) + npm)
        val ds = r.diagnostics.filter { it.ruleId == "CD003" }
        assertEquals(2, ds.size)
        assertEquals("npm", ds[0].fix?.newText)
        assertEquals("This file says to use yarn, but the repo uses npm (package-lock.json).", ds[1].message)
    }

    @Test fun cd003GlobalInstallIsFine() {
        val r = run(mapOf("AGENTS.md" to "Install the CLI with `pnpm -g add tool`.\n", "package.json" to pkg("build")) + npm)
        assertEquals(emptyList<String>(), tuples(r, "CD003"))
    }

    /* ---------- CD004 versions ---------- */

    @Test fun cd004NodeVersionWithPinFix() {
        val r = run(mapOf("AGENTS.md" to "- Requires Node 18.\n", ".nvmrc" to "20.11.0\n", "package.json" to pkg()))
        val d = r.diagnostics.single { it.ruleId == "CD004" }
        assertEquals("Version claim \"Node 18\" does not match the repo: .nvmrc says 20.11.0.", d.message)
        assertEquals("20", d.fix?.newText)
        assertEquals(16 to 18, d.fix!!.column to d.fix!!.endColumn)
    }

    @Test fun cd004RangesAndHistoryAreUnderstood() {
        val r = run(mapOf(
            "CLAUDE.md" to "Python 3.12 or newer. We dropped Python 3.8. CI runs Python 3.14.\n",
            "pyproject.toml" to "[project]\nname = \"a\"\nrequires-python = \">=3.11\"\n",
        ))
        assertEquals(emptyList<String>(), tuples(r, "CD004"))
        val bad = run(mapOf("CLAUDE.md" to "Use Python 3.10.\n", "pyproject.toml" to "[project]\nname = \"a\"\nrequires-python = \">=3.11\"\n"))
        assertEquals(1, bad.diagnostics.count { it.ruleId == "CD004" })
    }

    @Test fun versionHelpers() {
        assertTrue(satisfiesRange(listOf(20, 1, 0), ">=18 <21"))
        assertFalse(satisfiesRange(listOf(22, 0, 0), ">=18 <21"))
        assertTrue(satisfiesRange(listOf(3, 12, 0), "~=3.11"))
        assertTrue(claimFitsRange(listOf(20), "^20.1"))
        assertEquals(listOf(1, 22), parseVer("1.22"))
    }

    /* ---------- CD005 conflicts ---------- */

    @Test fun cd005ConflictBetweenAgentsAndClaude() {
        val r = run(mapOf("AGENTS.md" to "Test: `make test`\n", "CLAUDE.md" to "Test: `npm test`\n", "Makefile" to "test:\n\t@true\n", "package.json" to pkg("test")) + npm)
        val ds = r.diagnostics.filter { it.ruleId == "CD005" }
        assertEquals(2, ds.size)
        assertTrue(ds[0].message.contains("Agents get different instructions"))
        assertEquals("CLAUDE.md", ds[0].related.single().file)
    }

    /* ---------- CD006 stale (git) ---------- */

    @Test fun cd006IsOffByDefaultAndSilentWithoutGit() {
        val r = run(mapOf("AGENTS.md" to "Hello.\n"), Options(stale = true))
        assertEquals(emptyList<String>(), tuples(r, "CD006"))
    }

    @Test fun cd006ReportsAnOldFileInABusyRepo() {
        if (Git.run(".", "--version") == null) return // no git on this machine
        TestRepos.withRepo(mapOf("AGENTS.md" to "Hello there.\n")) { dir ->
            fun git(vararg a: String, date: String) {
                val pb = ProcessBuilder(listOf("git") + a).directory(dir)
                pb.environment().putAll(mapOf("GIT_AUTHOR_DATE" to date, "GIT_COMMITTER_DATE" to date, "GIT_AUTHOR_NAME" to "t", "GIT_AUTHOR_EMAIL" to "t@example.invalid", "GIT_COMMITTER_NAME" to "t", "GIT_COMMITTER_EMAIL" to "t@example.invalid"))
                assertEquals(0, pb.start().waitFor())
            }
            git("init", "-q", date = "2025-01-01T00:00:00Z")
            git("add", "AGENTS.md", date = "2025-01-01T00:00:00Z")
            git("commit", "-q", "-m", "a", date = "2025-01-01T00:00:00Z")
            for (i in 1..3) git("commit", "-q", "--allow-empty", "-m", "c$i", date = "2026-06-0${i}T00:00:00Z")
            val now = java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
            val r = analyzeWorkspace(dir.path, Options(stale = true, staleCommits = 3, now = now))
            val d = r.diagnostics.single { it.ruleId == "CD006" }
            assertTrue(d.message, d.message.startsWith("Last changed 669 days ago (2025-01-01); the repo has 3 commits since."))
            assertEquals(emptyList<String>(), tuples(analyzeWorkspace(dir.path, Options(stale = false, staleCommits = 3, now = now)), "CD006"))
        }
    }

    /* ---------- CD007 duplicated instructions ---------- */

    @Test fun cd007DuplicateLineInOneFileWithRemoveFix() {
        val r = run(mapOf("AGENTS.md" to "# Rules\n- Run the tests before you commit.\n- Keep functions small.\n* run the tests before you  commit\n- Keep functions small.\n"))
        val ds = r.diagnostics.filter { it.ruleId == "CD007" }
        assertEquals(1, ds.size) // "Keep functions small" has 3 words: below the 5-word minimum
        assertEquals(3, ds[0].line)
        assertEquals("This instruction repeats line 2. The agent reads it twice, and a later edit to one copy will make them disagree.", ds[0].message)
        assertEquals(Fix("Remove this duplicate line", 3, 0, 4, 0, ""), ds[0].fix)
    }

    @Test fun cd007AcrossFilesOnlyForTheSameAgent() {
        val line = "Always run the linter before you open a pull request.\n"
        val r = run(mapOf("CLAUDE.md" to line, "pkg/CLAUDE.md" to "Intro.\n$line", "AGENTS.md" to line, "GEMINI.md" to "Other text entirely in this file.\n"))
        val ds = r.diagnostics.filter { it.ruleId == "CD007" }
        assertEquals(listOf("pkg/CLAUDE.md:1"), ds.map { "${it.file}:${it.line}" })
        assertEquals(Severity.INFO, ds[0].severity)
        assertTrue(ds[0].message.contains("Claude Code loads both files"))
    }

    @Test fun cd007CodeFencesAndTablesAreNotInstructions() {
        val r = run(mapOf("AGENTS.md" to "```bash\nnpm run build and then npm run test\nnpm run build and then npm run test\n```\n| a | b c d e f |\n| a | b c d e f |\n"))
        assertEquals(emptyList<String>(), tuples(r, "CD007"))
    }

    /* ---------- CD008 size limits ---------- */

    @Test fun cd008AgentsMdOverCodexLimit() {
        val body = (1..700).joinToString("\n") { "- Instruction number $it says something about the code base." }
        val r = run(mapOf("AGENTS.md" to body + "\n"))
        val d = r.diagnostics.single { it.ruleId == "CD008" }
        assertEquals(Severity.WARNING, d.severity)
        assertTrue(d.message, d.message.startsWith("AGENTS.md is "))
        // the marked line is where byte 32,768 falls
        val before = body.lines().take(d.line).sumOf { it.length + 1 }
        assertTrue(before <= 32 * 1024 && before + body.lines()[d.line].length + 1 > 32 * 1024)
    }

    @Test fun cd008LimitsPerAgentAndCustomLimits() {
        val big = "x".repeat(5000)
        val r = run(mapOf(".github/copilot-instructions.md" to big, "CLAUDE.md" to big, ".windsurfrules" to "y".repeat(7000)))
        assertEquals(listOf(".github/copilot-instructions.md info", ".windsurfrules warning"), r.diagnostics.filter { it.ruleId == "CD008" }.map { "${it.file} ${it.severity.label}" })
        val r2 = run(mapOf("CLAUDE.md" to big), Options(sizeLimits = SizeLimits(claudeMdChars = 1000)))
        assertEquals(1, r2.diagnostics.count { it.ruleId == "CD008" })
    }

    /* ---------- suppression, ignore and helpers ---------- */

    @Test fun suppressionDirectives() {
        val r = run(mapOf(
            "AGENTS.md" to "<!-- context-drift-disable-next-line CD001 -->\nRun `npm run nope`.\n<!-- context-drift-disable-next-line -->\n```bash\nnpm run nope2\n```\n`npm run nope3` <!-- context-drift-disable-line CD001 -->\n",
            "package.json" to pkg("build")) + npm)
        assertEquals(emptyList<String>(), tuples(r, "CD001"))
        val r2 = run(mapOf("AGENTS.md" to "<!-- context-drift-disable CD001 -->\nRun `npm run nope`.\n", "package.json" to pkg("build")) + npm)
        assertEquals(emptyList<String>(), tuples(r2, "CD001"))
    }

    @Test fun ignoreRulesOption() {
        val r = run(mapOf("AGENTS.md" to "Run `npm run nope` and see `src/gone.ts`.\n", "src/a.ts" to "", "package.json" to pkg("build")) + npm, Options(ignoreRules = setOf("CD001")))
        assertEquals(listOf("CD002"), r.diagnostics.map { it.ruleId })
    }

    @Test fun directiveInsertionPlacement() {
        val text = "---\nglobs: a/*.ts\n---\nText\n```bash\n  npm run x\n```\n  - item `x`\n"
        assertEquals(DirectiveInsertion(1, "# context-drift-disable-next-line CD002\n", false), directiveInsertion(text, 1, "CD002"))
        assertEquals(DirectiveInsertion(4, "<!-- context-drift-disable-next-line CD001 -->\n", true), directiveInsertion(text, 5, "CD001"))
        assertEquals(DirectiveInsertion(7, "  <!-- context-drift-disable-next-line CD002 -->\n", false), directiveInsertion(text, 7, "CD002"))
    }

    @Test fun instructionFileKinds() {
        assertEquals("AGENTS.md", classifyInstructionFile("a/b/AGENTS.md"))
        assertEquals("cursor-rule", classifyInstructionFile(".cursor/rules/x/y.mdc"))
        assertEquals("copilot-scoped", classifyInstructionFile(".github/instructions/py.instructions.md"))
        assertEquals("windsurf-rule", classifyInstructionFile(".windsurf/rules/a.md"))
        assertNull(classifyInstructionFile("README.md"))
        assertEquals("", scopeDirOf(".github/copilot-instructions.md"))
    }

    @Test fun globMatcher() {
        val g = compileGlob("src/**/*.{ts,tsx}")
        assertNotNull(g)
        assertTrue(globMatch(g, "src/a/b/c.tsx"))
        assertTrue(globMatch(g, "src/c.ts"))
        assertFalse(globMatch(g, "lib/c.ts"))
        assertTrue(globMatch(compileGlob("[a-c]?.md"), "bx.md"))
    }

    @Test fun pathologicalInputFinishesQuickly() {
        val t0 = System.currentTimeMillis()
        val nasty = "[a](".repeat(20000) + "\n" + "`" + "a".repeat(3000) + "\n" + "Node ".repeat(2000) + "\n"
        run(mapOf("AGENTS.md" to nasty, "package.json" to pkg()))
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 10_000)
    }
}

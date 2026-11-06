package zovo.contextdrift.engine

/*
 * Context Drift engine, ported from the VS Code extension's engine.js (zovo.context-drift 0.1.8).
 * Pure Kotlin with no IntelliJ dependency, so the unit tests run without an IDE.
 * Positions are 0-based; columns are UTF-16 code units (the same as Kotlin strings and IntelliJ offsets).
 */

enum class Severity(val label: String) { ERROR("error"), WARNING("warning"), INFO("info") }

data class Rule(val id: String, val name: String, val defaultSeverity: Severity, val description: String, val defaultOn: Boolean = true)

object Rules {
    val CD001 = Rule("CD001", "command-drift", Severity.ERROR,
        "A documented command does not exist in the repo (npm/pnpm/yarn/bun scripts, make, just, task, cargo, uv/poetry).")
    val CD002 = Rule("CD002", "path-drift", Severity.WARNING,
        "A referenced relative path does not exist (globs that match nothing are reported as info).")
    val CD003 = Rule("CD003", "package-manager-drift", Severity.WARNING,
        "The file uses one package manager but the lockfile or packageManager field says another.")
    val CD004 = Rule("CD004", "version-drift", Severity.WARNING,
        "A Node, Python or Go version claim contradicts .nvmrc, engines, .python-version, requires-python or go.mod.")
    val CD005 = Rule("CD005", "cross-file-conflict", Severity.INFO,
        "Two instruction files in the same scope give different commands for the same verb (test, build, lint, format, dev, typecheck).")
    val CD006 = Rule("CD006", "stale-file", Severity.INFO,
        "The instruction file has not changed for a long time while the repo kept moving (git log, off by default).", defaultOn = false)
    val CD007 = Rule("CD007", "duplicated-rule", Severity.WARNING,
        "The same instruction appears twice in one file, or in two instruction files that agents load together.")
    val CD008 = Rule("CD008", "size-limit", Severity.WARNING,
        "The instruction file is larger than what the agent that reads it loads or recommends.")
    val ALL = listOf(CD001, CD002, CD003, CD004, CD005, CD006, CD007, CD008)
    fun byId(id: String): Rule? = ALL.firstOrNull { it.id == id }
}

/** One text replacement on a single line. */
data class Fix(val title: String, val line: Int, val column: Int, val endLine: Int, val endColumn: Int, val newText: String)

data class Related(val file: String, val line: Int, val column: Int, val endColumn: Int, val message: String)

data class Diagnostic(
    val ruleId: String,
    val severity: Severity,
    val file: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val message: String,
    val fix: Fix? = null,
    val related: List<Related> = emptyList(),
    val data: Map<String, Any?> = emptyMap(),
) {
    val absKey get() = "$ruleId:$line:$column"
}

data class SkippedFile(val file: String, val reason: String, val partial: Boolean = false)

data class WorkspaceResult(
    val root: String,
    val files: List<String>,
    val skipped: List<SkippedFile>,
    val diagnostics: List<Diagnostic>,
    val elapsedMs: Long,
)

data class SizeLimits(
    /** Codex reads at most project_doc_max_bytes of AGENTS.md (32 KiB by default). */
    val agentsMdBytes: Int = 32 * 1024,
    /** Claude Code warns when a memory file is over 40,000 characters. */
    val claudeMdChars: Int = 40_000,
    /** GitHub Copilot code review reads the first 4,000 characters of an instruction file. */
    val copilotChars: Int = 4_000,
    /** Windsurf limits each rule file to 6,000 characters. */
    val windsurfChars: Int = 6_000,
    /** Cursor recommends rules under 500 lines. */
    val cursorLines: Int = 500,
    /** Any other instruction file. */
    val otherChars: Int = 40_000,
)

data class Options(
    val ignoreRules: Set<String> = emptySet(),
    val overrides: Map<String, String> = emptyMap(), // abs path -> unsaved text
    val stale: Boolean = false,
    val staleDays: Int = 180,
    val staleCommits: Int = 50,
    val budgetMs: Long = 20_000,
    val sizeLimits: SizeLimits = SizeLimits(),
    val now: Long = System.currentTimeMillis(),
)

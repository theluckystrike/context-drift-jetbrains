package zovo.contextdrift.ide

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

/** Settings | Tools | Context Drift. Per-rule on/off and severity also live in Settings | Editor | Inspections | Context Drift. */
class DriftConfigurable(private val project: Project) : BoundConfigurable("Context Drift") {
    private val s get() = DriftSettings.get(project).state

    override fun createPanel(): DialogPanel = panel {
        row {
            checkBox("Check agent instruction files").bindSelected(s::enabled)
                .comment("AGENTS.md, CLAUDE.md, GEMINI.md, SKILL.md, Cursor, Copilot, Windsurf and Cline rules")
        }
        row("Ignore rules:") {
            textField().columns(30)
                .bindText({ s.ignoreRules.joinToString(", ") }, { v -> s.ignoreRules = v.split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toMutableList() })
                .comment("Rule ids to skip, for example CD005, CD007")
        }
        group("Staleness (CD006, read-only git log)") {
            row { checkBox("Check staleness with git").bindSelected(s::gitStaleness) }
            row("Older than (days):") { intTextField(1..100_000).bindIntText(s::staleDays) }
            row("With at least this many newer commits:") { intTextField(1..1_000_000).bindIntText(s::staleCommits) }
        }
        group("Size limits (CD008)") {
            row("AGENTS.md (KiB):") { intTextField(1..10_000).bindIntText(s::agentsMdKiB).comment("Codex project_doc_max_bytes, 32 KiB by default") }
            row("CLAUDE.md (characters):") { intTextField(1..10_000_000).bindIntText(s::claudeMdChars).comment("Claude Code warns above 40,000") }
            row("Copilot instructions (characters):") { intTextField(1..10_000_000).bindIntText(s::copilotChars).comment("Copilot code review reads the first 4,000") }
            row(".windsurfrules (characters):") { intTextField(1..10_000_000).bindIntText(s::windsurfChars).comment("Windsurf reads 6,000") }
            row("Cursor rules and SKILL.md (lines):") { intTextField(1..1_000_000).bindIntText(s::ruleLines).comment("Recommended maximum: 500") }
        }
        row { comment("Context Drift is free and runs offline. If it saves you time, <a href=\"$SPONSORS_URL\">sponsor it on GitHub</a>.") }
    }

    override fun apply() {
        super.apply()
        DriftService.get(project).invalidateResults()
    }
}

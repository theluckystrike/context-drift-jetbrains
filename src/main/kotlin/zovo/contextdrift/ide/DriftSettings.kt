package zovo.contextdrift.ide

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import zovo.contextdrift.engine.Options
import zovo.contextdrift.engine.SizeLimits

/** Project settings, stored in .idea/contextDrift.xml so a team can share them. */
@State(name = "ContextDriftSettings", storages = [Storage("contextDrift.xml")])
class DriftSettings : PersistentStateComponent<DriftSettings.State> {
    class State {
        var enabled: Boolean = true
        var ignoreRules: MutableList<String> = ArrayList()
        var gitStaleness: Boolean = false
        var staleDays: Int = 180
        var staleCommits: Int = 50
        var agentsMdKiB: Int = 32
        var claudeMdChars: Int = 40_000
        var copilotChars: Int = 4_000
        var windsurfChars: Int = 6_000
        var ruleLines: Int = 500
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    fun toOptions(overrides: Map<String, String>): Options = Options(
        ignoreRules = state.ignoreRules.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet(),
        overrides = overrides,
        stale = state.gitStaleness,
        staleDays = state.staleDays.coerceAtLeast(1),
        staleCommits = state.staleCommits.coerceAtLeast(1),
        budgetMs = 10_000,
        sizeLimits = SizeLimits(
            agentsMdBytes = state.agentsMdKiB.coerceAtLeast(1) * 1024,
            claudeMdChars = state.claudeMdChars.coerceAtLeast(1),
            copilotChars = state.copilotChars.coerceAtLeast(1),
            windsurfChars = state.windsurfChars.coerceAtLeast(1),
            cursorLines = state.ruleLines.coerceAtLeast(1),
        ),
    )

    fun ignoreRule(id: String) {
        if (state.ignoreRules.none { it.equals(id, ignoreCase = true) }) state.ignoreRules.add(id)
    }

    companion object {
        fun get(project: Project): DriftSettings = project.getService(DriftSettings::class.java)
    }
}

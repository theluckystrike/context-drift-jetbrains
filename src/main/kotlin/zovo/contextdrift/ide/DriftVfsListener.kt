package zovo.contextdrift.ide

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import zovo.contextdrift.engine.classifyInstructionFile

private val MANIFEST_BASENAME = Regex("""^(?:package\.json|[Mm]akefile|GNUmakefile|.*\.mk|[Jj]ustfile|\.justfile|[Tt]askfile(?:\.dist)?\.ya?ml|pyproject\.toml|requirements.*\.(?:txt|in)|Cargo\.toml|config\.toml|go\.mod|\.nvmrc|\.node-version|\.python-version|\.tool-versions|runtime\.txt|package-lock\.json|npm-shrinkwrap\.json|pnpm-lock\.yaml|pnpm-workspace\.yaml|yarn\.lock|bun\.lockb?|\.gitignore|\.gitmodules)$""")

/** What a VFS event means for the cached repository view. */
internal enum class Impact { NONE, RESULTS, MANIFESTS, TREE }

internal fun impactOf(path: String, contentOnly: Boolean): Impact {
    val name = path.substring(path.lastIndexOf('/') + 1)
    if (!contentOnly) return Impact.TREE
    if (MANIFEST_BASENAME.matches(name)) return Impact.MANIFESTS
    if (classifyInstructionFile(path) != null) return Impact.RESULTS
    return Impact.NONE
}

/** Saving any other file does nothing; manifests re-read facts; created, deleted or moved files re-walk the folder. */
class DriftVfsListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        var worst = Impact.NONE
        for (e in events) {
            if (e.fileSystem.protocol != "file") continue
            val contentOnly = e is VFileContentChangeEvent || (e is VFilePropertyChangeEvent && e.propertyName != com.intellij.openapi.vfs.VirtualFile.PROP_NAME)
            val imp = impactOf(e.path, contentOnly)
            if (imp > worst) worst = imp
            if (worst == Impact.TREE) break
        }
        if (worst == Impact.NONE) return
        for (p in ProjectManager.getInstance().openProjects) {
            if (p.isDisposed) continue
            val svc = p.getServiceIfCreated(DriftService::class.java) ?: continue
            when (worst) {
                Impact.TREE -> svc.invalidateTree()
                Impact.MANIFESTS -> svc.invalidateManifests()
                Impact.RESULTS -> svc.invalidateResults()
                Impact.NONE -> {}
            }
        }
    }
}

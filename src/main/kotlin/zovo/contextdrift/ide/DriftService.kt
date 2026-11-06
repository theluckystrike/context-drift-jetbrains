package zovo.contextdrift.ide

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import zovo.contextdrift.engine.Diagnostic
import zovo.contextdrift.engine.RepoContext
import zovo.contextdrift.engine.WorkspaceResult
import zovo.contextdrift.engine.analyzeWorkspace
import zovo.contextdrift.engine.classifyInstructionFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Runs the engine for a project. The repository view (file list and manifest facts) is cached per root:
 * an instruction-file edit reuses it, a manifest change re-reads only the manifests, and only created,
 * deleted or moved files make it walk the folder again. Results are cached until the inputs change.
 */
class DriftService(private val project: Project) {
    private class RootState(@Volatile var ctx: RepoContext? = null, @Volatile var key: String? = null, @Volatile var result: WorkspaceResult? = null)

    private val roots = ConcurrentHashMap<String, RootState>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var generation = 0L

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** The folder the engine treats as the repository: the content root, else the nearest git root, else the file's folder. */
    fun rootFor(file: VirtualFile): String? {
        if (!file.isInLocalFileSystem) return null
        val cr = ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable {
            if (project.isDisposed) null else ProjectFileIndex.getInstance(project).getContentRootForFile(file)
        })
        if (cr != null && cr.isInLocalFileSystem) return cr.path
        var d: File? = File(file.path).parentFile
        while (d != null) {
            if (File(d, ".git").exists()) return d.path
            d = d.parentFile
        }
        // no git: Cursor, Copilot, Windsurf and Cline rule files belong to the folder that holds .cursor/, .github/, ...
        val path = file.path
        val scope = zovo.contextdrift.engine.scopeDirOf(path)
        return if (scope.isNotEmpty() && scope != File(path).parent) scope else File(path).parent
    }

    fun projectRoots(): List<String> {
        val out = LinkedHashSet<String>()
        ApplicationManager.getApplication().runReadAction(Runnable {
            if (!project.isDisposed) {
                for (m in com.intellij.openapi.module.ModuleManager.getInstance(project).modules) {
                    for (r in com.intellij.openapi.roots.ModuleRootManager.getInstance(m).contentRoots) if (r.isInLocalFileSystem) out.add(r.path)
                }
            }
        })
        if (out.isEmpty()) project.basePath?.let { out.add(it) }
        // nested content roots are covered by their parent
        return out.filter { r -> out.none { o -> o != r && r.startsWith("$o/") } }
    }

    private fun unsavedOverrides(root: String): Map<String, String> {
        val out = HashMap<String, String>()
        val fdm = FileDocumentManager.getInstance()
        for (doc in fdm.unsavedDocuments) {
            val f = fdm.getFile(doc) ?: continue
            if (!f.isInLocalFileSystem || !f.path.startsWith("$root/")) continue
            if (classifyInstructionFile(f.path.substring(root.length + 1)) == null) continue
            out[File(f.path).path] = doc.text
        }
        return out
    }

    /** Workspace result for a root. textOverride replaces one file's text (the editor's current text). */
    fun analyze(root: String, override: Pair<String, String>? = null): WorkspaceResult? {
        val settings = DriftSettings.get(project)
        if (!settings.state.enabled) return null
        val overrides = HashMap(unsavedOverrides(root))
        if (override != null) overrides[File(override.first).path] = override.second
        val st = roots.computeIfAbsent(root) { RootState() }
        synchronized(st) {
            val opts = settings.toOptions(overrides)
            val key = buildString {
                append(generation).append('|').append(opts.copy(overrides = emptyMap(), now = 0).hashCode())
                for ((k, v) in overrides.toSortedMap()) append('|').append(k).append('=').append(v.hashCode()).append(':').append(v.length)
            }
            st.result?.let { if (st.key == key) return it }
            val ctx = st.ctx ?: RepoContext.create(root).also { st.ctx = it }
            val r = analyzeWorkspace(root, opts, ctx)
            st.key = key
            st.result = r
            return r
        }
    }

    /** Findings for one instruction file, given the editor text. */
    fun diagnosticsFor(file: VirtualFile, text: String): List<Diagnostic> {
        val root = rootFor(file) ?: return emptyList()
        val path = File(file.path).path
        if (!path.startsWith(root + File.separator)) return emptyList()
        val rel = path.substring(root.length + 1).replace(File.separatorChar, '/')
        if (classifyInstructionFile(rel) == null) return emptyList()
        val r = analyze(root, path to text) ?: return emptyList()
        notifyListenersLater()
        return r.diagnostics.filter { it.file == rel }
    }

    fun absPath(root: String, rel: String) = File(root, rel).path

    fun findFile(root: String, rel: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(absPath(root, rel))

    /** Files were created, deleted or moved: walk again. */
    fun invalidateTree() {
        roots.values.forEach { synchronized(it) { it.ctx = null; it.result = null; it.key = null } }
        generation++
        restart()
    }

    /** A manifest (package.json, Makefile, lockfile, ...) changed: re-read manifests only. */
    fun invalidateManifests() {
        roots.values.forEach { synchronized(it) { it.ctx?.invalidateManifests(); it.result = null; it.key = null } }
        generation++
        restart()
    }

    /** Settings or an instruction file changed on disk. */
    fun invalidateResults() {
        roots.values.forEach { synchronized(it) { it.result = null; it.key = null } }
        generation++
        restart()
    }

    private fun restart() {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart() }, project.disposed)
        notifyListenersLater()
    }

    @Volatile private var notifyPending = false
    private fun notifyListenersLater() {
        if (listeners.isEmpty() || notifyPending) return
        notifyPending = true
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            notifyPending = false
            listeners.forEach { it() }
        }, 400, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    companion object {
        fun get(project: Project): DriftService = project.getService(DriftService::class.java)
    }
}

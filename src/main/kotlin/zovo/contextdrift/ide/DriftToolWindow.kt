package zovo.contextdrift.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import zovo.contextdrift.engine.Diagnostic
import zovo.contextdrift.engine.Severity
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

internal const val SPONSORS_URL = "https://github.com/sponsors/theluckystrike"
internal const val VSCODE_URL = "https://marketplace.visualstudio.com/items?itemName=zovo.context-drift"

/** One row of the results tree. */
internal class FileNode(val root: String, val rel: String, val count: Int)
internal class FindingNode(val root: String, val d: Diagnostic)

/** Pure model for the tree, so tests can check it without Swing. */
internal fun buildTreeModel(results: List<Pair<String, List<Diagnostic>>>): DefaultMutableTreeNode {
    val top = DefaultMutableTreeNode("Context Drift")
    for ((root, diags) in results) {
        for ((rel, ds) in diags.groupBy { it.file }.toSortedMap()) {
            val fn = DefaultMutableTreeNode(FileNode(root, rel, ds.size))
            for (d in ds) fn.add(DefaultMutableTreeNode(FindingNode(root, d)))
            top.add(fn)
        }
    }
    return top
}

internal fun summaryText(results: List<Pair<String, List<Diagnostic>>>, files: Int): String {
    val all = results.flatMap { it.second }
    val e = all.count { it.severity == Severity.ERROR }
    val w = all.count { it.severity == Severity.WARNING }
    val i = all.count { it.severity == Severity.INFO }
    return "$files instruction file(s) checked: $e error(s), $w warning(s), $i note(s)"
}

class DriftPanel(private val project: Project) : SimpleToolWindowPanel(true, true), com.intellij.openapi.Disposable {
    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode("Context Drift")))
    private val status = JLabel("Checking…")
    private val listener: () -> Unit = { refresh() }

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = Renderer()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) navigateSelected() }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) { if (e.keyCode == KeyEvent.VK_ENTER) navigateSelected() }
        })
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Re-check", "Check the agent instruction files again", AllIcons.Actions.Refresh), DumbAware {
                override fun actionPerformed(e: AnActionEvent) { DriftService.get(project).invalidateTree(); refresh() }
            })
            add(object : AnAction("Settings", "Open Context Drift settings", AllIcons.General.Settings), DumbAware {
                override fun actionPerformed(e: AnActionEvent) {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(project, DriftConfigurable::class.java)
                }
            })
            add(object : AnAction("Expand All", null, AllIcons.Actions.Expandall), DumbAware {
                override fun actionPerformed(e: AnActionEvent) { TreeUtil.expandAll(tree) }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("ContextDriftToolbar", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val footer = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2)))
        footer.add(status)
        footer.add(HyperlinkLabel("Sponsor on GitHub").apply { setHyperlinkTarget(SPONSORS_URL) })
        footer.add(HyperlinkLabel("Also for VS Code").apply { setHyperlinkTarget(VSCODE_URL) })
        val body = JPanel(BorderLayout())
        body.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        body.add(footer, BorderLayout.SOUTH)
        setContent(body)
        DriftService.get(project).addListener(listener)
    }

    override fun dispose() { DriftService.get(project).removeListener(listener) }

    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val svc = DriftService.get(project)
            val enabled = DriftSettings.get(project).state.enabled
            val results = ArrayList<Pair<String, List<Diagnostic>>>()
            var files = 0
            if (enabled) for (root in svc.projectRoots()) {
                val r = ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable { svc.analyze(root) }) ?: continue
                files += r.files.size
                results.add(root to r.diagnostics)
            }
            val model = buildTreeModel(results)
            val text = if (!enabled) "Context Drift is turned off in Settings | Tools | Context Drift." else summaryText(results, files)
            ApplicationManager.getApplication().invokeLater({
                (tree.model as DefaultTreeModel).setRoot(model)
                TreeUtil.expandAll(tree)
                status.text = text
            }, project.disposed)
        }
    }

    private fun navigateSelected() {
        val node = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject
        val svc = DriftService.get(project)
        when (node) {
            is FindingNode -> svc.findFile(node.root, node.d.file)?.let { OpenFileDescriptor(project, it, node.d.line, node.d.column).navigate(true) }
            is FileNode -> svc.findFile(node.root, node.rel)?.let { OpenFileDescriptor(project, it).navigate(true) }
        }
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val o = (value as? DefaultMutableTreeNode)?.userObject) {
                is FileNode -> {
                    icon = AllIcons.FileTypes.Text
                    append(o.rel, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${o.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is FindingNode -> {
                    icon = when (o.d.severity) {
                        Severity.ERROR -> AllIcons.General.Error
                        Severity.WARNING -> AllIcons.General.Warning
                        Severity.INFO -> AllIcons.General.Information
                    }
                    append("${o.d.line + 1}:${o.d.column + 1} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(o.d.ruleId + " ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append(o.d.message)
                }
                else -> append(value?.toString() ?: "")
            }
        }
    }
}

/** Tools | Context Drift: Check Agent Instruction Files. */
class CheckProjectAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        DriftService.get(project).invalidateTree()
        ToolWindowManager.getInstance(project).getToolWindow("Context Drift")?.show()
    }
}

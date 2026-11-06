package zovo.contextdrift.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import zovo.contextdrift.engine.directiveInsertion
import zovo.contextdrift.ide.DriftService
import zovo.contextdrift.ide.DriftSettings

/** The descriptor's current range in the file (it tracks edits made since the check ran). */
internal fun currentRange(descriptor: ProblemDescriptor): com.intellij.openapi.util.TextRange? {
    (descriptor as? com.intellij.codeInspection.ProblemDescriptorBase)?.textRange?.let { return it }
    val el = descriptor.psiElement ?: return null
    val r = descriptor.textRangeInElement ?: return el.textRange
    return r.shiftRight(el.textRange.startOffset)
}

/**
 * Replaces one range with the engine's suggestion. The range is kept relative to the finding, and the fix checks
 * that the text is still what the check read, so it never applies at an old position.
 */
class ReplaceTextFix(
    private val title: String,
    private val relStart: Int,
    private val length: Int,
    private val expected: String,
    private val newText: String,
    private val family: String,
) : LocalQuickFix, com.intellij.codeInsight.intention.HighPriorityAction {
    override fun getName(): String = title
    override fun getFamilyName(): String = family

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val pdm = PsiDocumentManager.getInstance(project)
        val doc = pdm.getDocument(file) ?: return
        val r = currentRange(descriptor) ?: return
        val s = r.startOffset + relStart
        val e = s + length
        if (s < 0 || e > doc.textLength || doc.getText(com.intellij.openapi.util.TextRange(s, e)) != expected) return
        doc.replaceString(s, e, newText)
        pdm.commitDocument(doc)
    }
}

/** Adds "context-drift-disable-next-line CDxxx" above the line (above the fence for a code block, a YAML comment in frontmatter). */
class SuppressLineFix(private val ruleId: String, private val line: Int) : LocalQuickFix, com.intellij.codeInsight.intention.LowPriorityAction {
    override fun getName(): String = "Disable $ruleId for this line"
    override fun getFamilyName(): String = "Disable a Context Drift check for this line"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement?.containingFile ?: return
        val pdm = PsiDocumentManager.getInstance(project)
        val doc = pdm.getDocument(file) ?: return
        val target = currentRange(descriptor)?.let { doc.getLineNumber(minOf(it.startOffset, doc.textLength)) } ?: line
        val ins = directiveInsertion(doc.text, target, ruleId)
        if (ins.line < 0 || ins.line >= maxOf(1, doc.lineCount)) return
        // a second finding of the same rule on this line: the directive is already there
        if (ins.line > 0) {
            val above = doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(ins.line - 1), doc.getLineEndOffset(ins.line - 1)))
            if (above.trim() == ins.text.trim()) return
        }
        doc.insertString(if (doc.lineCount == 0) 0 else doc.getLineStartOffset(ins.line), ins.text)
        pdm.commitDocument(doc)
    }
}

/** Adds the rule to the project's ignore list (Settings | Tools | Context Drift). */
class IgnoreRuleFix(private val ruleId: String) : LocalQuickFix, com.intellij.codeInsight.intention.LowPriorityAction {
    override fun getName(): String = "Ignore $ruleId in this project"
    override fun getFamilyName(): String = name
    override fun startInWriteAction(): Boolean = false
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        DriftSettings.get(project).ignoreRule(ruleId)
        DriftService.get(project).invalidateResults()
    }
}

/** Opens a repo file (the manifest a command was checked against, or the other file of a conflict). */
class OpenRepoFileFix(private val rel: String, private val search: String?, private val line: Int = -1) : LocalQuickFix {
    override fun getName(): String = if (search == "\"scripts\"") "Show scripts in $rel" else "Open $rel"
    override fun getFamilyName(): String = "Open the related file"
    override fun startInWriteAction(): Boolean = false
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val vf = descriptor.psiElement?.containingFile?.virtualFile ?: return
        val svc = DriftService.get(project)
        val root = svc.rootFor(vf) ?: return
        val target = svc.findFile(root, rel) ?: return
        var offset = -1
        if (search != null) {
            val text = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(target)?.text
            offset = text?.indexOf(search) ?: -1
        }
        val d = if (offset >= 0) OpenFileDescriptor(project, target, offset) else if (line >= 0) OpenFileDescriptor(project, target, line, 0) else OpenFileDescriptor(project, target)
        d.navigate(true)
    }
}

package zovo.contextdrift.ide.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import zovo.contextdrift.engine.Diagnostic
import zovo.contextdrift.engine.Severity
import zovo.contextdrift.ide.DriftService
import zovo.contextdrift.ide.DriftSettings

/** One inspection per rule, so each rule can be turned off or re-levelled in the inspection profile. */
abstract class DriftInspectionBase(private val ruleId: String) : LocalInspectionTool() {
    override fun runForWholeFile(): Boolean = true

    override fun checkFile(file: PsiFile, manager: InspectionManager, isOnTheFly: Boolean): Array<ProblemDescriptor>? {
        val vp = file.viewProvider
        // Markdown files have a Markdown and an HTML PSI root: check the file once, on the base language.
        if (file != vp.getPsi(vp.baseLanguage)) return null
        val vf = vp.virtualFile
        if (!vf.isInLocalFileSystem) return null
        val project = file.project
        if (!DriftSettings.get(project).state.enabled) return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val diags = DriftService.get(project).diagnosticsFor(vf, doc.text).filter { it.ruleId == ruleId }
        if (diags.isEmpty()) return null
        val out = ArrayList<ProblemDescriptor>()
        for (d in diags) {
            val range = rangeOf(doc, d) ?: continue
            val fixes = ArrayList<LocalQuickFix>()
            d.fix?.let { f ->
                val fr = fixRange(doc, f.line, f.column, f.endLine, f.endColumn) ?: return@let
                fixes.add(ReplaceTextFix(f.title, fr.startOffset - range.startOffset, fr.length, doc.getText(fr), f.newText, familyFor(d)))
            }
            val manifest = d.data["manifest"] as? String
            if (d.ruleId == "CD001" && manifest != null) fixes.add(OpenRepoFileFix(manifest, if (manifest.endsWith("package.json")) "\"scripts\"" else null))
            val related = d.related.firstOrNull()
            if (related != null) fixes.add(OpenRepoFileFix(related.file, null, related.line))
            if (d.data["kind"] != "not-project-root" && d.data["kind"] != "capped" && d.ruleId != "CD006") fixes.add(SuppressLineFix(d.ruleId, d.line))
            fixes.add(IgnoreRuleFix(d.ruleId))
            val type = when (d.severity) {
                Severity.ERROR -> ProblemHighlightType.GENERIC_ERROR
                Severity.WARNING -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                Severity.INFO -> ProblemHighlightType.WEAK_WARNING
            }
            out.add(manager.createProblemDescriptor(file, range, "${d.ruleId} ${d.message}", type, isOnTheFly, *fixes.toTypedArray()))
        }
        return out.toTypedArray()
    }

    private fun familyFor(d: Diagnostic): String = when (d.ruleId) {
        "CD003" -> "Replace the package manager with the repo's"
        "CD007" -> "Remove duplicate instruction lines"
        else -> "Apply Context Drift suggestion"
    }

    companion object {
        fun rangeOf(doc: Document, d: Diagnostic): TextRange? = fixRange(doc, d.line, d.column, d.endLine, d.endColumn)?.let {
            if (it.isEmpty) TextRange(it.startOffset, minOf(doc.textLength, it.startOffset + 1)) else it
        }

        /** Line/column to offsets, clamped to the document; a range that ends on the line after the last one ends at the text end. */
        fun fixRange(doc: Document, line: Int, col: Int, endLine: Int, endCol: Int): TextRange? {
            if (line < 0 || line >= doc.lineCount) return null
            val s = minOf(doc.getLineStartOffset(line) + col, doc.getLineEndOffset(line))
            val e = if (endLine >= doc.lineCount) doc.textLength else minOf(doc.getLineStartOffset(endLine) + endCol, if (endLine == line && endCol > 0) doc.getLineEndOffset(endLine) else doc.textLength)
            return if (e < s) null else TextRange(s, e)
        }
    }
}

class DriftCD001Inspection : DriftInspectionBase("CD001")
class DriftCD002Inspection : DriftInspectionBase("CD002")
class DriftCD003Inspection : DriftInspectionBase("CD003")
class DriftCD004Inspection : DriftInspectionBase("CD004")
class DriftCD005Inspection : DriftInspectionBase("CD005")
class DriftCD006Inspection : DriftInspectionBase("CD006")
class DriftCD007Inspection : DriftInspectionBase("CD007")
class DriftCD008Inspection : DriftInspectionBase("CD008")

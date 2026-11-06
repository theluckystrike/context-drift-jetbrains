package zovo.contextdrift.ide

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl
import zovo.contextdrift.ide.inspections.DriftCD001Inspection
import zovo.contextdrift.ide.inspections.DriftCD002Inspection
import zovo.contextdrift.ide.inspections.DriftCD003Inspection
import zovo.contextdrift.ide.inspections.DriftCD004Inspection
import zovo.contextdrift.ide.inspections.DriftCD005Inspection
import zovo.contextdrift.ide.inspections.DriftCD007Inspection
import zovo.contextdrift.ide.inspections.DriftCD008Inspection

/**
 * Runs the inspections inside a real IntelliJ test application: files on disk, the Markdown editor,
 * highlighting, quick fixes and "fix all in file".
 */
class InspectionPlatformTest : BasePlatformTestCase() {
    // The engine reads the repository from disk, so the test files must be real files.
    override fun createTempDirTestFixture(): TempDirTestFixture = TempDirTestFixtureImpl()

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(
            DriftCD001Inspection(), DriftCD002Inspection(), DriftCD003Inspection(), DriftCD004Inspection(),
            DriftCD005Inspection(), DriftCD007Inspection(), DriftCD008Inspection(),
        )
    }

    private fun repo(files: Map<String, String>) {
        for ((rel, text) in files) myFixture.tempDirFixture.createFile(rel, text)
        DriftService.get(project).invalidateTree()
    }

    /** Puts the caret on the first occurrence of text (quick fixes are offered at the caret). */
    private fun caretAt(text: String) {
        val i = myFixture.editor.document.text.indexOf(text)
        assertTrue("'$text' not in the document", i >= 0)
        myFixture.editor.caretModel.moveToOffset(i + 1)
    }

    private fun fix(at: String, name: String) {
        caretAt(at)
        myFixture.launchAction(myFixture.findSingleIntention(name))
    }

    private fun drift(): List<HighlightInfo> = myFixture.doHighlighting().filter { it.description?.startsWith("CD0") == true }

    private val readmeExample = mapOf(
        "package.json" to "{\"name\":\"x\",\"scripts\":{\"build\":\"tsc\",\"test\":\"node --test\"}}",
        "package-lock.json" to "{}",
        ".nvmrc" to "20.11.0\n",
        "Makefile" to "deploy-prod:\n\t@true\n",
        "src/main.ts" to "export {}\n",
    )
    private val agents = "- Requires Node 18.\n- Install with `pnpm install`.\n- Build: `npm run buidl`. Deploy: `make deploy`.\n- Entry point: `src/server/main.ts`\n"

    fun testReadmeExampleFindingsAreHighlighted() {
        repo(readmeExample + ("AGENTS.md" to agents))
        myFixture.configureFromTempProjectFile("AGENTS.md")
        val infos = drift()
        val got = infos.map { "${it.description!!.substring(0, 5)} ${it.severity.name} ${myFixture.editor.document.getLineNumber(it.startOffset)}:${it.text}" }.sorted()
        assertEquals(listOf(
            "CD001 ERROR 2:buidl",
            "CD001 ERROR 2:deploy",
            "CD002 WARNING 3:src/server/main.ts",
            "CD003 WARNING 1:pnpm",
            "CD004 WARNING 0:Node 18",
        ), got)
    }

    fun testQuickFixesProduceTheReadmeAfterText() {
        repo(readmeExample + ("AGENTS.md" to agents))
        myFixture.configureFromTempProjectFile("AGENTS.md")
        fix("buidl", "Replace with \"build\"")
        fix("pnpm", "Replace `pnpm` with `npm`")
        fix("src/server", "Replace with \"src/main.ts\"")
        fix("Node 18", "Replace with \"20\"")
        assertEquals(
            "- Requires Node 20.\n- Install with `npm install`.\n- Build: `npm run build`. Deploy: `make deploy`.\n- Entry point: `src/main.ts`\n",
            myFixture.editor.document.text,
        )
        // after the edits only `make deploy` is left (no close target name, so no one-click fix)
        assertEquals(listOf("CD001"), drift().map { it.description!!.substring(0, 5) })
    }

    fun testFixAllInFileAppliesEverySuggestion() {
        repo(readmeExample + ("AGENTS.md" to "Run `npm run buidl`, then `npm run tst`.\nAlso `npm run biuld`.\n"))
        myFixture.configureFromTempProjectFile("AGENTS.md")
        caretAt("buidl")
        // IntelliJ lists each quick fix followed by its options; the "Fix all" right after "Replace with" applies replacements
        val list = myFixture.availableIntentions
        val at = list.indexOfFirst { it.text == "Replace with \"build\"" }
        val all = list.drop(at + 1).firstOrNull { it.text.startsWith("Fix all") && it.text.contains("CD001") }
        assertNotNull("no fix-all intention in " + myFixture.availableIntentions.map { it.text }, all)
        myFixture.launchAction(all!!)
        assertEquals("Run `npm run build`, then `npm run test`.\nAlso `npm run build`.\n", myFixture.editor.document.text)
    }

    fun testSuppressFixAddsADirective() {
        repo(readmeExample + ("AGENTS.md" to "Run `npm run nope`.\n"))
        myFixture.configureFromTempProjectFile("AGENTS.md")
        fix("nope", "Disable CD001 for this line")
        assertEquals("<!-- context-drift-disable-next-line CD001 -->\nRun `npm run nope`.\n", myFixture.editor.document.text)
        assertEmpty(drift())
    }

    fun testIgnoreRuleFixUpdatesSettings() {
        repo(readmeExample + ("AGENTS.md" to "Run `npm run nope`.\n"))
        myFixture.configureFromTempProjectFile("AGENTS.md")
        fix("nope", "Ignore CD001 in this project")
        assertEquals(listOf("CD001"), DriftSettings.get(project).state.ignoreRules)
        assertEmpty(drift())
        DriftSettings.get(project).state.ignoreRules.clear()
    }

    fun testDuplicateLineFixRemovesTheSecondCopy() {
        repo(mapOf("CLAUDE.md" to "- Run the full test suite before you commit.\n- Other rule here.\n- run the full test suite before you commit\n"))
        myFixture.configureFromTempProjectFile("CLAUDE.md")
        val dup = drift().single()
        assertEquals(HighlightSeverity.WARNING, dup.severity)
        fix("run the full test suite before you commit\n", "Remove this duplicate line")
        assertEquals("- Run the full test suite before you commit.\n- Other rule here.\n", myFixture.editor.document.text)
    }

    fun testUnsavedEditsAreChecked() {
        repo(readmeExample + ("AGENTS.md" to "Run `npm run build`.\n"))
        myFixture.configureFromTempProjectFile("AGENTS.md")
        assertEmpty(drift())
        myFixture.type("Also `npm run lnt`. ")
        assertEquals(listOf("CD001"), drift().map { it.description!!.substring(0, 5) })
    }

    fun testCursorMdcAndCrossFileConflict() {
        repo(readmeExample + mapOf(
            ".cursor/rules/vue.mdc" to "---\nglobs: src/**/*.vue\n---\nTest with `npm test`.\n",
            "AGENTS.md" to "Test with `make test`.\n",
            "Makefile" to "test:\n\t@true\n",
        ))
        myFixture.configureFromTempProjectFile(".cursor/rules/vue.mdc")
        val got = drift().map { "${it.description!!.substring(0, 5)} ${it.severity.name}" }.sorted()
        assertEquals(listOf("CD002 WEAK WARNING", "CD005 WEAK WARNING"), got)
    }

    fun testToolWindowModelGroupsByFile() {
        repo(readmeExample + ("AGENTS.md" to agents) + ("CLAUDE.md" to "Run `npm run nope`.\n"))
        val root = myFixture.tempDirFixture.getFile("AGENTS.md")!!.parent.path
        val r = DriftService.get(project).analyze(root)!!
        val tree = buildTreeModel(listOf(root to r.diagnostics))
        assertEquals(2, tree.childCount)
        assertEquals("AGENTS.md", (tree.getChildAt(0) as javax.swing.tree.DefaultMutableTreeNode).let { (it.userObject as FileNode).rel })
        assertEquals("2 instruction file(s) checked: 3 error(s), 3 warning(s), 0 note(s)", summaryText(listOf(root to r.diagnostics), r.files.size))
    }

    fun testListenerImpact() {
        assertEquals(Impact.MANIFESTS, impactOf("/r/package.json", true))
        assertEquals(Impact.RESULTS, impactOf("/r/docs/AGENTS.md", true))
        assertEquals(Impact.NONE, impactOf("/r/src/a.ts", true))
        assertEquals(Impact.TREE, impactOf("/r/src/a.ts", false))
    }
}

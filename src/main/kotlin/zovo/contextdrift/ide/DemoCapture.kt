package zovo.contextdrift.ide

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import java.awt.Component
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

/**
 * Screenshot mode for the Marketplace listing. It does nothing unless the IDE is started with
 * -Dcontextdrift.demo.capture=<folder> (see the runIde task in build.gradle.kts). Then it opens the demo files,
 * shows the tool window, paints the IDE window into PNG files and closes the IDE.
 */
class DemoCapture : ProjectActivity {
    override suspend fun execute(project: Project) {
        val out = System.getProperty("contextdrift.demo.capture") ?: return
        Thread({ run(project, File(out)) }, "Context Drift demo capture").apply { isDaemon = true }.start()
    }

    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(block, com.intellij.openapi.application.ModalityState.any())

    /** The IDE frame plus the popups and dialogs shown over it (on macOS they are separate windows). */
    private fun paintScreen(project: Project, file: File) {
        val frame = WindowManager.getInstance().getFrame(project) ?: return
        val scale = 2
        val img = BufferedImage(frame.width * scale, frame.height * scale, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.scale(scale.toDouble(), scale.toDouble())
        frame.rootPane.paint(g)
        for (w in Window.getWindows()) {
            if (w === frame || !w.isShowing || w !is javax.swing.RootPaneContainer) continue
            val g2 = g.create(w.x - frame.x, w.y - frame.y, w.width, w.height)
            w.rootPane.paint(g2)
            g2.dispose()
        }
        g.dispose()
        file.parentFile.mkdirs()
        ImageIO.write(img, "png", file)
    }

    private fun paint(c: Component, file: File) {
        val scale = 2
        val img = BufferedImage(c.width * scale, c.height * scale, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.scale(scale.toDouble(), scale.toDouble())
        c.paint(g)
        g.dispose()
        file.parentFile.mkdirs()
        ImageIO.write(img, "png", file)
    }

    private fun open(project: Project, root: String, rel: String, line: Int, col: Int) = ui {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("$root/$rel") ?: return@ui
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, vf, line, col), true)
    }

    private fun run(project: Project, out: File) {
        try {
            DumbService.getInstance(project).waitForSmartMode()
            val root = project.basePath ?: return
            ui {
                WindowManager.getInstance().getFrame(project)?.let { it.setSize(1440, 900); it.setLocation(40, 40) }
                ToolWindowManager.getInstance(project).getToolWindow("Context Drift")?.show()
                ToolWindowManager.getInstance(project).getToolWindow("Project")?.hide()
            }
            open(project, root, "CLAUDE.md", 5, 4)
            Thread.sleep(8_000)
            open(project, root, "AGENTS.md", 4, 21)
            Thread.sleep(15_000)
            DumbService.getInstance(project).waitForSmartMode()
            Thread.sleep(5_000)
            ui { paintScreen(project, File(out, "1-editor-and-tool-window.png")) }

            // the quick-fix popup must be opened from a write-safe context
            ApplicationManager.getApplication().invokeLater({
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                ActionManager.getInstance().getAction("ShowIntentionActions")?.let { ActionManager.getInstance().tryToExecute(it, null, editor?.contentComponent, "demo", true) }
            }, com.intellij.openapi.application.ModalityState.nonModal())
            Thread.sleep(5_000)
            ui { paintScreen(project, File(out, "2-quick-fix.png")) }
            ui { com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getChildPopups(WindowManager.getInstance().getFrame(project)!!).forEach { it.cancel() } }
            Thread.sleep(1_000)

            open(project, root, "CLAUDE.md", 5, 4)
            Thread.sleep(15_000)
            ui { paintScreen(project, File(out, "3-duplicate-and-conflict.png")) }

            SwingUtilities.invokeLater { ShowSettingsUtil.getInstance().showSettingsDialog(project, DriftConfigurable::class.java) }
            Thread.sleep(6_000)
            ui {
                Window.getWindows().filter { it.isShowing && it is javax.swing.JDialog }.maxByOrNull { it.width * it.height }?.let { d ->
                    paint((d as javax.swing.JDialog).rootPane, File(out, "4-settings.png"))
                    com.intellij.openapi.ui.DialogWrapper.findInstance(d.rootPane)?.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE) ?: d.dispose()
                }
            }
            File(out, "done.txt").writeText("ok\n")
        } catch (e: Throwable) {
            File(out, "error.txt").writeText(e.stackTraceToString())
        } finally {
            if (System.getProperty("contextdrift.demo.exit") == "true") {
                ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().exit(true, true, false) }
            }
        }
    }
}

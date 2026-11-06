package zovo.contextdrift.engine

import java.io.File
import java.nio.file.Files

/** Small throwaway repositories for engine tests, under build/test-repos (deleted after each test). */
object TestRepos {
    private val base: File by lazy {
        File(System.getProperty("contextdrift.testRoot") ?: "build/test-repos").absoluteFile.also { it.mkdirs() }
    }

    fun <T> withRepo(files: Map<String, String>, body: (File) -> T): T {
        val dir = Files.createTempDirectory(base.toPath(), "repo-").toFile()
        try {
            for ((rel, content) in files) {
                val f = File(dir, rel)
                f.parentFile.mkdirs()
                f.writeText(content)
            }
            return body(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    fun run(files: Map<String, String>, options: Options = Options()): WorkspaceResult = withRepo(files) { analyzeWorkspace(it.path, options) }

    fun pkg(vararg scripts: String, extra: String = ""): String =
        "{\"name\":\"x\",\"scripts\":{" + scripts.joinToString(",") { "\"$it\":\"x\"" } + "}" + (if (extra.isNotEmpty()) ",$extra" else "") + "}"
}

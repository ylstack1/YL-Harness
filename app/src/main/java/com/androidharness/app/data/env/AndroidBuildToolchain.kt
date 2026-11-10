package com.androidharness.app.data.env

import java.io.File
import java.util.zip.ZipFile

/** Relocation and file checks for optional Android build packages, not an SDK installer. */
internal object AndroidBuildToolchain {
    private data class Launcher(val pkg: String, val jar: String, val main: String)
    private val launchers = mapOf(
        "d8" to Launcher("d8", "d8.jar", "com.android.tools.r8.D8"),
        "r8" to Launcher("d8", "d8.jar", "com.android.tools.r8.R8"),
        "apksigner" to Launcher("apksigner", "apksigner.jar", "-jar"),
    )
    private val jdkName = Regex("java-(\\d+)-openjdk")

    fun javaHome(prefix: File): File? = File(prefix, "lib/jvm").listFiles().orEmpty()
        .filter { jdkName.matches(it.name) && File(it, "bin/java").isFile }
        .maxByOrNull { jdkName.matchEntire(it.name)!!.groupValues[1].toInt() }

    // The deployed tree cannot be inspected by the app UID. Derive its paths
    // from the same app-side tree that will be staged for that execution tier.
    fun javaHome(prefix: File, executionPrefix: String): String? {
        val home = javaHome(prefix) ?: return null
        // Android aliases /data/user/0 to /data/data. libjli compares this
        // string with its loaded library path before deciding to re-exec.
        return if (executionPrefix == prefix.absolutePath) home.canonicalPath
            else "$executionPrefix/${home.relativeTo(prefix).invariantSeparatorsPath}"
    }

    fun libraryPath(javaHome: String?, executionPrefix: String): String =
        // libjli re-execs /proc/self/exe if lib/server is not first. In the
        // app tier /proc/self/exe is Android's linker, which then treats a Java
        // argument such as -version as the executable and fails.
        javaHome?.let { "$it/lib/server:$it/lib:$it/lib/jli:$executionPrefix/lib" } ?: "$executionPrefix/lib"

    fun rewriteLauncher(name: String, text: String): String? {
        val spec = launchers[name] ?: return null
        val termuxJar = "/data/data/com.termux/files/usr/share/java/${spec.jar}"
        if (!text.startsWith("#!") || !text.contains(spec.main) || !text.contains(termuxJar)) return null
        // The launcher is shared by the app and shell UID copies. A fixed
        // deployed path would just move the failure to the app execution tier.
        val path = Regex("[\"']?${Regex.escape(termuxJar)}[\"']?")
        val rewritten = path.replace(text) { "\"\${PREFIX:?Linux environment prefix is not set}/share/java/${spec.jar}\"" }
        return "#!/system/bin/sh\n" + rewritten.substringAfter('\n')
    }

    fun repairLaunchers(prefix: File, installed: Collection<String>): Int {
        return launchers.filterValues { it.pkg in installed }.keys.count { name ->
            val file = File(prefix, "bin/$name")
            if (!file.isFile) return@count false
            val repaired = rewriteLauncher(name, file.readText()) ?: return@count false
            file.writeText(repaired)
            true
        }
    }

    fun brokenPackages(prefix: File, installed: Collection<String>): List<String> = installed.filter { name ->
        when {
            name == "d8" -> listOf("bin/d8", "bin/r8").any { !File(prefix, it).isFile } ||
                !validJar(File(prefix, "share/java/d8.jar"), listOf("com/android/tools/r8/D8.class", "com/android/tools/r8/R8.class"))
            name == "apksigner" -> !File(prefix, "bin/apksigner").isFile ||
                !validJar(File(prefix, "share/java/apksigner.jar"), listOf("com/android/apksigner/ApkSignerTool.class"))
            name.matches(Regex("openjdk-\\d+")) -> {
                val home = File(prefix, "lib/jvm/java-${name.removePrefix("openjdk-")}-openjdk")
                listOf("bin/java", "bin/javac", "lib/modules").any { !File(home, it).isFile }
            }
            else -> false
        }
    }

    private fun validJar(jar: File, classes: List<String>): Boolean = runCatching {
        ZipFile(jar).use { zip ->
            classes.all { zip.getEntry(it) != null }
        }
    }.getOrDefault(false)

    fun status(prefix: File, installed: Collection<String>, executionPrefix: String): String {
        val home = javaHome(prefix, executionPrefix)
        val dex = when {
            "d8" !in installed -> "D8 not installed (pkg_search/pkg_install: d8)"
            "d8" in brokenPackages(prefix, listOf("d8")) -> "D8 files missing or invalid; reinstall with pkg_install: d8"
            else -> "D8 compiler files present at $executionPrefix/share/java/d8.jar"
        }
        return "${home?.let { "Java home: $it" } ?: "Java not installed (pkg_search/pkg_install: openjdk-21)"}; $dex. " +
            "Run java -version and d8 --version to verify execution. Android SDK/platform and project build compatibility are not checked by Linux environment readiness."
    }
}

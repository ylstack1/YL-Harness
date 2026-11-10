package com.androidharness.app.data.env

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class AndroidBuildToolchainTest {
    @Test fun `D8 and R8 launchers resolve jars in each execution tier and preserve arguments`() {
        for ((name, main) in listOf("d8" to "D8", "r8" to "R8")) {
            val original = "#!/deployed/bin/sh\nexec java -cp /data/data/com.termux/files/usr/share/java/d8.jar com.android.tools.r8.$main \"\$@\"\n"
            val repaired = requireNotNull(AndroidBuildToolchain.rewriteLauncher(name, original))
            assertFalse(repaired.contains("/data/data/com.termux"))
            assertTrue(repaired.contains("\"\${PREFIX:?Linux environment prefix is not set}/share/java/d8.jar\""))
            assertTrue(repaired.endsWith("com.android.tools.r8.$main \"\$@\"\n"))
            assertNull(AndroidBuildToolchain.rewriteLauncher(name, repaired))
        }
        assertNull(AndroidBuildToolchain.rewriteLauncher("other", "#!/bin/sh\n/data/data/com.termux/files/usr/share/java/d8.jar"))
        assertNull(AndroidBuildToolchain.rewriteLauncher("d8", "#!/bin/sh\necho 'leave this custom launcher alone'"))
    }

    @Test fun `broken compiler archives and missing Java files are detected even with installed markers`() = fixture { root ->
        val installed = listOf("d8", "openjdk-21")
        assertEquals(installed, AndroidBuildToolchain.brokenPackages(root, installed))
        write(root, "bin/d8"); write(root, "bin/r8")
        write(root, "lib/jvm/java-21-openjdk/bin/java")
        write(root, "lib/jvm/java-21-openjdk/bin/javac")
        write(root, "lib/jvm/java-21-openjdk/lib/modules")
        val jar = write(root, "share/java/d8.jar", "corrupted archive")
        assertEquals(listOf("d8"), AndroidBuildToolchain.brokenPackages(root, installed))
        ZipOutputStream(jar.outputStream()).use { zip ->
            for (main in listOf("D8", "R8")) { zip.putNextEntry(ZipEntry("com/android/tools/r8/$main.class")); zip.write(1); zip.closeEntry() }
        }
        assertTrue(AndroidBuildToolchain.brokenPackages(root, installed).isEmpty())
        jar.delete()
        assertEquals(listOf("d8"), AndroidBuildToolchain.brokenPackages(root, installed))
    }

    @Test fun `Java paths are derived from app files for the active tier and newest installed JDK`() = fixture { root ->
        write(root, "lib/jvm/java-17-openjdk/bin/java")
        write(root, "lib/jvm/java-21-openjdk/bin/java")
        File(root, "lib/jvm/java-25-openjdk").mkdirs()
        assertEquals("/shell/linux/lib/jvm/java-21-openjdk", AndroidBuildToolchain.javaHome(root, "/shell/linux"))
        assertEquals(File(root, "lib/jvm/java-21-openjdk"), AndroidBuildToolchain.javaHome(root))
        assertTrue(AndroidBuildToolchain.status(root, emptyList(), "/shell/linux").contains("D8 not installed"))
    }

    @Test fun `JVM server libraries must precede all others to avoid reexecuting the Android linker`() {
        assertEquals("/shell/jdk/lib/server:/shell/jdk/lib:/shell/jdk/lib/jli:/shell/linux/lib",
            AndroidBuildToolchain.libraryPath("/shell/jdk", "/shell/linux"))
        assertEquals("/app/linux/lib", AndroidBuildToolchain.libraryPath(null, "/app/linux"))
    }

    @Test fun `Java uses canonical app paths but derives deployed paths without inspecting that tree`() = fixture { root ->
        val actual = File(root, "actual").apply { mkdirs() }
        val alias = File(root, "alias")
        Files.createSymbolicLink(alias.toPath(), actual.toPath())
        write(actual, "lib/jvm/java-21-openjdk/bin/java")
        assertEquals(File(actual, "lib/jvm/java-21-openjdk").canonicalPath, AndroidBuildToolchain.javaHome(alias, alias.absolutePath))
        assertEquals("/deployed/lib/jvm/java-21-openjdk", AndroidBuildToolchain.javaHome(alias, "/deployed"))
    }

    @Test fun `APK signing launcher uses its own jar in the same execution tier`() {
        val original = "#!/data/data/com.termux/files/usr/bin/sh\nexec java -jar /data/data/com.termux/files/usr/share/java/apksigner.jar \"\$@\"\n"
        val repaired = requireNotNull(AndroidBuildToolchain.rewriteLauncher("apksigner", original))
        assertTrue(repaired.contains("-jar \"\${PREFIX:?Linux environment prefix is not set}/share/java/apksigner.jar\""))
        assertFalse(repaired.contains("com.termux"))
        assertNull(AndroidBuildToolchain.rewriteLauncher("apksigner", repaired))
    }

    @Test fun `legacy installed launchers repair in place without downloading or changing unrelated files`() = fixture { root ->
        val original = "#!/fixed/bin/sh\nexec java -cp '/data/data/com.termux/files/usr/share/java/d8.jar' com.android.tools.r8.D8 \"\$@\"\n"
        val launcher = write(root, "bin/d8", original)
        val other = write(root, "bin/custom", original)
        assertEquals(0, AndroidBuildToolchain.repairLaunchers(root, emptyList()))
        assertEquals(1, AndroidBuildToolchain.repairLaunchers(root, listOf("d8")))
        assertFalse(launcher.readText().contains("com.termux"))
        assertEquals(original, other.readText())
        assertEquals(0, AndroidBuildToolchain.repairLaunchers(root, listOf("d8")))
    }

    @Test fun `repaired launcher uses the active prefix with spaces and preserves Java exit status`() = fixture { root ->
        val bin = File(root, "spy-bin").apply { mkdirs() }
        File(bin, "java").apply { writeText("#!/bin/sh\nprintf '<%s>\\n' \"\$@\"\nexit 23\n"); setExecutable(true) }
        val original = "#!/data/data/com.termux/files/usr/bin/sh\nexec java -cp /data/data/com.termux/files/usr/share/java/d8.jar com.android.tools.r8.D8 \"\$@\"\n"
        val launcher = write(root, "bin/d8", requireNotNull(AndroidBuildToolchain.rewriteLauncher("d8", original)))
        for (tier in listOf("app prefix", "deployed prefix")) {
            val prefix = File(root, tier).absolutePath
            val process = ProcessBuilder("/bin/sh", launcher.absolutePath, "--output", "dex output", "A class.class")
                .apply { environment()["PREFIX"] = prefix; environment()["PATH"] = bin.absolutePath }
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(23, process.waitFor())
            assertEquals("<-cp>\n<$prefix/share/java/d8.jar>\n<com.android.tools.r8.D8>\n<--output>\n<dex output>\n<A class.class>\n", output)
        }
    }

    private fun write(root: File, path: String, text: String = "fixture") = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("android-build-tools").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}

package com.androidharness.app.data.env

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class AndroidBuildToolchainDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun installerRepairsExistingLaunchersAndRedownloadsMissingCompilerDespiteInstalledMarker() = runBlocking {
        val root = File(app.cacheDir, "dex-package-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getDataDir() = root
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        val jar = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            for (main in listOf("D8", "R8")) { zip.putNextEntry(ZipEntry("com/android/tools/r8/$main.class")); zip.write(1); zip.closeEntry() }
        } }.toByteArray()
        fun launcher(main: String) = "#!/data/data/com.termux/files/usr/bin/sh\nexec java -cp /data/data/com.termux/files/usr/share/java/d8.jar com.android.tools.r8.$main \"\$@\"\n"
        val payloads = mapOf("bin/d8" to launcher("D8").toByteArray(), "bin/r8" to launcher("R8").toByteArray(), "share/java/d8.jar" to jar)
        val tar = ByteArrayOutputStream().also { output -> TarArchiveOutputStream(GZIPOutputStream(output)).use { archive ->
            payloads.forEach { (path, bytes) ->
                archive.putArchiveEntry(TarArchiveEntry("data/data/com.termux/files/usr/$path").apply { size = bytes.size.toLong(); mode = 0x1C0 })
                archive.write(bytes); archive.closeArchiveEntry()
            }
        } }.toByteArray()
        val deb = ByteArrayOutputStream().also { output -> ArArchiveOutputStream(output).use { archive ->
            archive.putArchiveEntry(ArArchiveEntry("data.tar.gz", tar.size.toLong())); archive.write(tar); archive.closeArchiveEntry()
        } }.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(deb).joinToString("") { "%02x".format(it) }
        val meta = PkgMeta("d8", "fixture", emptyList(), "pool/d8-fixture.deb", deb.size.toLong(), hash)
        var downloads = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            downloads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(deb.toResponseBody()).build()
        }.build()
        val env = LinuxEnvironmentManager(context, packageIndexLoader = { mapOf("d8" to meta) }, packageHttpClient = client)
        try {
            env.install(listOf("d8")).getOrThrow()
            assertEquals(1, downloads)
            for (name in listOf("d8", "r8")) {
                val text = File(env.prefix, "bin/$name").readText()
                assertFalse(text, text.contains("/data/data/com.termux"))
                assertTrue(text, text.contains("\${PREFIX:?"))
            }
            File(env.prefix, "bin/d8").writeText(launcher("D8"))
            env.install(listOf("d8")).getOrThrow()
            assertEquals("An existing launcher should repair without another download", 1, downloads)
            assertFalse(File(env.prefix, "bin/d8").readText().contains("com.termux"))
            File(env.prefix, "share/java/d8.jar").delete()
            assertTrue(env.checkMissing().contains("d8"))
            env.install(listOf("d8")).getOrThrow()
            assertEquals("The installed marker must not hide a missing compiler", 2, downloads)
            assertTrue(AndroidBuildToolchain.brokenPackages(env.prefix, listOf("d8")).isEmpty())
            assertTrue(env.androidBuildStatus().contains("Run java -version and d8 --version"))
        } finally { root.deleteRecursively() }
    }

    /** Opt in to real repository downloads on a dedicated test device. */
    @Test fun realOnDeviceJavaCompilationProducesDex() = runBlocking {
        val env = (app.applicationContext as com.androidharness.app.HarnessApp).container.linuxEnv
        val args = InstrumentationRegistry.getArguments()
        val platformJar = args.getString("androidPlatformJar")?.let(::File)
        if (args.getString("installAndroidBuildTools") == "true") {
            env.install(env.fullPackages + "d8" + if (platformJar != null) listOf("aapt2", "apksigner") else emptyList()).getOrThrow()
        }
        org.junit.Assume.assumeTrue("Install Java and D8 to run the real compiler check", env.installedContains("d8"))
        val cwd = File(app.cacheDir, "dex-compile-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(cwd, "Hello.java").writeText("public class Hello { public static int answer() { return 42; } }")
            suspend fun command(text: String, terminal: Boolean = false) = coroutineScope {
                val process = if (terminal) env.shellProcessBuilder(text).directory(cwd).redirectErrorStream(true).start()
                    else env.startShell("$text 2>&1", cwd).let { (process, tier) ->
                        assertEquals("The agent must use the installed Linux environment", LinuxEnvironmentManager.ShellTier.LINUX, tier)
                        process
                    }
                val output = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader().readText()
                }
                try {
                    assertTrue("$text timed out", process.waitFor(90, TimeUnit.SECONDS))
                    assertEquals("$text: ${output.await()}", 0, process.exitValue())
                } finally { process.destroyForcibly() }
            }
            command("java -version", terminal = true)
            command("java -version")
            command("d8 --version")
            command("javac --release 8 Hello.java")
            File(cwd, "dex").mkdirs()
            command("d8 --min-api 26 --output dex Hello.class")
            val dex = File(cwd, "dex/classes.dex")
            assertTrue("The compiler must emit a real DEX file", dex.length() > 100)
            assertEquals("dex\n", dex.inputStream().use { stream ->
                val magic = ByteArray(4); assertEquals(4, stream.read(magic)); String(magic, Charsets.US_ASCII)
            })
            if (platformJar != null) {
                assertTrue("Supply an SDK platform jar for the optional full APK smoke test", platformJar.isFile)
                File(cwd, "AndroidManifest.xml").writeText("""
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.androidharness.issue56.smoke" android:versionCode="1" android:versionName="1">
                        <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
                        <application android:label="Harness build smoke" android:hasCode="true" />
                    </manifest>
                """.trimIndent())
                val sdk = "'" + platformJar.absolutePath.replace("'", "'\\''") + "'"
                command("aapt2 link -I $sdk --manifest AndroidManifest.xml -o unsigned.apk")
                command("jar --update --file unsigned.apk -C dex classes.dex")
                command("keytool -genkeypair -keystore smoke.jks -storepass android -keypass android -alias smoke -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=Harness Smoke'")
                command("apksigner sign --ks smoke.jks --ks-pass pass:android --key-pass pass:android --out smoke.apk unsigned.apk")
                command("apksigner verify --verbose smoke.apk")
                File(cwd, "smoke.apk").copyTo(File(app.getExternalFilesDir(null), "issue56-smoke.apk"), overwrite = true)
                if (args.getString("stageAndroidBuildTools") == "true") {
                    env.stageForShell()
                    assertNotNull("The staged toolchain must have a verified content hash", env.deployedTag())
                    File(app.getExternalFilesDir(null), "issue56-shell-env.json")
                        .writeText(org.json.JSONObject(env.tmpProcessEnv()).toString())
                }
            }
        } finally { cwd.deleteRecursively() }
    }
}

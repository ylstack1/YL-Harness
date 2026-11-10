package com.androidharness.app.data

import java.security.MessageDigest
import java.util.Locale

/** Source code and configuration; documentation, binaries and generated/dependency folders are excluded. */
object CodeFilePolicy {
    private val extensions = setOf("kt", "kts", "java", "py", "pyi", "js", "jsx", "ts", "tsx", "mjs", "cjs", "c", "h", "cpp", "cc", "hpp", "cs", "go", "rs", "swift", "m", "mm", "rb", "php", "dart", "lua", "r", "scala", "sc", "clj", "ex", "exs", "erl", "hs", "sql", "sh", "bash", "zsh", "ps1", "bat", "cmd", "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "xml", "json", "jsonc", "yaml", "yml", "toml", "ini", "properties", "gradle", "cmake", "proto", "graphql", "gql", "tf")
    private val ignored = setOf(".git", ".gradle", "node_modules", "vendor", "build", "dist", "target", "generated", "out", "__pycache__", ".venv", "venv", "pods")
    fun counts(path: String): Boolean {
        val segments = path.replace('\\', '/').lowercase(Locale.ROOT).split('/')
        if (segments.dropLast(1).any { it in ignored }) return false
        val name = segments.last()
        if (name in setOf("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "composer.lock", "cargo.lock") ||
            name.endsWith(".min.js") || name.endsWith(".min.css") || name.endsWith(".g.dart")) return false
        return name in setOf("dockerfile", "makefile", "cmakelists.txt", "gemfile", "rakefile") || name.substringAfterLast('.', "") in extensions
    }
    fun fileKey(sessionId: String, path: String): String = digest("$sessionId:${path.removePrefix("./")}")
    fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

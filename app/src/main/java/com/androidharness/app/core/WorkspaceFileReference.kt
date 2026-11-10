package com.androidharness.app.core

import java.net.URI
import java.net.URLDecoder

/** A reference into the current workspace, with an optional one-based starting line. */
data class WorkspaceFileReference(val path: String, val line: Int? = null) {
    companion object {
        private val suffix = Regex("""(?::([0-9]+)(?::[0-9]+)?(?:-[0-9]+)?|#L([0-9]+)(?:-L?[0-9]+)?)$""")
        private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
        private val ipv4Port = Regex("""(?:[0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]+(?:[/#?].*)?""")
        private val dotFile = Regex("""\.[\p{L}\p{N}_][\p{L}\p{N}_.-]*""")
        private val tokens = Regex("""(?<![\p{L}\p{N}_/.:@)\]-])(?:\./|/)?(?:[\p{L}\p{N}_@+.()\[\]-]+/)*[\p{L}\p{N}_@+.\[\]-]+(?::[0-9]+(?::[0-9]+)?(?:-[0-9]+)?)?(?:#L[0-9]+(?:-L?[0-9]+)?)?""")
        private val extensions = setOf(
            "kt", "kts", "java", "xml", "gradle", "properties", "json", "jsonc", "yaml", "yml", "toml",
            "md", "mdx", "txt", "csv", "sql", "py", "pyi", "js", "jsx", "mjs", "cjs", "ts", "tsx",
            "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "astro", "sh", "bash", "zsh",
            "c", "h", "cpp", "hpp", "cc", "rs", "go", "rb", "php", "swift", "dart", "cs", "fs",
            "ex", "exs", "erl", "hrl", "lua", "r", "pl", "pm", "ini", "conf", "cfg", "proto",
            "graphql", "gql", "svg", "lock", "cmake", "ipynb", "bat", "ps1",
        )
        private val names = setOf("Makefile", "Dockerfile", "Containerfile", "CMakeLists.txt", "Gemfile", "Rakefile",
            "Procfile", "Justfile", "LICENSE", "README", "NOTICE", "gradlew")

        /** Explicit Markdown destinations may name extensionless files and contain encoded spaces. */
        fun fromLink(destination: String): WorkspaceFileReference? = parse(destination, explicit = true)

        /** Inline code is linked only when it looks like a file, rather than a symbol or command. */
        fun fromText(text: String): WorkspaceFileReference? = parse(text, explicit = false)

        data class Match(val range: IntRange, val reference: WorkspaceFileReference)
        fun findInText(text: String): List<Match> = tokens.findAll(text).mapNotNull { match ->
            val token = match.value.trimEnd('.', ',', ';', '!', '?')
            fromText(token)?.let { Match(match.range.first until match.range.first + token.length, it) }
        }.toList()

        private fun parse(input: String, explicit: Boolean): WorkspaceFileReference? {
            if (input.isBlank() || input.length > 4096 || input.any { it.isISOControl() } || '\\' in input) return null
            var target = input.trim().removeSurrounding("<", ">")
            if (target.startsWith("file:", ignoreCase = true)) {
                if (!explicit) return null
                val uri = runCatching { URI(target) }.getOrNull() ?: return null
                if (!uri.rawAuthority.isNullOrEmpty() && !uri.rawAuthority.equals("localhost", ignoreCase = true)) return null
                if (uri.rawQuery != null) return null
                target = uri.rawPath ?: return null
                uri.rawFragment?.let { target += "#$it" }
            }
            // A basename plus :line can resemble a URI scheme (Main.kt:42), but tel:42 cannot.
            // Bare loopback preview URLs must keep using the web preview rather than the editor.
            if (ipv4Port.matches(target)) return null
            if (scheme.containsMatchIn(target)) {
                val prefix = target.substringBefore(':')
                if (prefix !in names && !dotFile.matches(prefix) &&
                    prefix.substringAfterLast('.', "").lowercase() !in extensions) return null
            }
            val location = suffix.find(target)
            val line = location?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull()?.takeIf { n -> n > 0 } ?: return null }
            if (location != null) target = target.substring(0, location.range.first)
            if (scheme.containsMatchIn(target) || target.startsWith("//") || '#' in target) return null
            if (explicit) target = runCatching { URLDecoder.decode(target.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            if (target.any { it.isISOControl() } || '\\' in target || '?' in target || target.endsWith('/') || target.isBlank()) return null
            while (target.startsWith("./")) target = target.removePrefix("./")
            if (target.split('/').any { it == ".." } || target in listOf("", ".", "/")) return null
            val name = target.substringAfterLast('/')
            if (name.isBlank() || name == "." || ':' in target || '#' in target && !explicit) return null
            val fileLike = name in names || dotFile.matches(name) ||
                name.substringAfterLast('.', "").lowercase() in extensions
            if (!explicit && !fileLike) return null
            // Do not turn a command such as `cat src/Main.kt` into a file name.
            if (!explicit && target.any { it.isWhitespace() } && target.substringBefore('/').any { it.isWhitespace() }) return null
            return WorkspaceFileReference(target, line)
        }
    }
}

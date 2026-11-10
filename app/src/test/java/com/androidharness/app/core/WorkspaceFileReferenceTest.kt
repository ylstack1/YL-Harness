package com.androidharness.app.core

import org.junit.Assert.*
import org.junit.Test

class WorkspaceFileReferenceTest {
    @Test fun sourceLocationsUseOneBasedStartingLines() {
        for ((input, expected) in listOf(
            "app/src/Main.kt:42" to WorkspaceFileReference("app/src/Main.kt", 42),
            "app/src/Main.kt:42:7" to WorkspaceFileReference("app/src/Main.kt", 42),
            "./app/src/Main.kt:42-49" to WorkspaceFileReference("app/src/Main.kt", 42),
            "/work/app/src/Main.kt#L42-L49" to WorkspaceFileReference("/work/app/src/Main.kt", 42),
            "src/Main.kt#L42-49" to WorkspaceFileReference("src/Main.kt", 42),
            "src/Main.kt" to WorkspaceFileReference("src/Main.kt"),
            ".github/workflows/build.yml" to WorkspaceFileReference(".github/workflows/build.yml"),
            "Dockerfile" to WorkspaceFileReference("Dockerfile"),
            ".gitignore" to WorkspaceFileReference(".gitignore"),
            "src/日本語.kt:8" to WorkspaceFileReference("src/日本語.kt", 8),
        )) assertEquals(input, expected, WorkspaceFileReference.fromText(input))
    }

    @Test fun explicitLinksDecodePathsWithoutChangingPlusSignsOrInventingLineNumbers() {
        assertEquals(WorkspaceFileReference("src/My File.kt", 73), WorkspaceFileReference.fromLink("<src/My File.kt#L73>"))
        assertEquals(WorkspaceFileReference("src/My File.kt", 73), WorkspaceFileReference.fromLink("src/My%20File.kt:73"))
        assertEquals(WorkspaceFileReference("src/+page.svelte", 9), WorkspaceFileReference.fromLink("src/+page.svelte#L9"))
        assertEquals(WorkspaceFileReference("/work/My File.kt", 3), WorkspaceFileReference.fromLink("file:///work/My%20File.kt#L3"))
        assertEquals(WorkspaceFileReference("/work/Main.kt", 3), WorkspaceFileReference.fromLink("file://localhost/work/Main.kt:3"))
        assertEquals(WorkspaceFileReference("src/custom-extensionless"), WorkspaceFileReference.fromLink("src/custom-extensionless"))
        assertEquals(WorkspaceFileReference("src/Main.kt"), WorkspaceFileReference.fromLink("src/Main.kt"))
    }

    @Test fun normalProseDomainsSymbolsCommandsAndWebUrlsAreNotImplicitFiles() {
        for (input in listOf("hello", "...", ".*", ".env = true", "example.com", "v2.4.0", "android.view.View", "localhost:8080",
            "https://example.com/main.kt#L3", "https://localhost:8080", "user@example.com", "foo(bar.kt)",
            "cat src/Main.kt", "rm /work/Main.kt", "cat Main.kt", "val path = src/Main.kt")) {
            assertNull(input, WorkspaceFileReference.fromText(input))
        }
        for (input in listOf("https://example.com/Main.kt:42", "http://localhost:8080", "127.0.0.1:8080", "127.0.0.1:8080/index.html", "dev.local:3000", "mailto:person@example.com", "tel:1234", "javascript:alert(1)", "content://files/Main.kt", "ftp://server/Main.kt")) {
            assertNull(input, WorkspaceFileReference.fromLink(input))
        }
    }

    @Test fun invalidLocationsTraversalAndRemoteFileAuthoritiesStayOutOfTheEditor() {
        for (input in listOf("Main.kt:0", "Main.kt:99999999999999999999", "../Main.kt", "src/../Main.kt",
            "file://remote/work/Main.kt", "src/%2e%2e/Main.kt", "src/%00Main.kt", "src/Main.kt#wrong",
            "src/Main.kt?line=42", "src\\Main.kt", "src/Main.kt\n", "/", "src/", "#L42", "#section")) {
            assertNull(input, WorkspaceFileReference.fromLink(input))
        }
    }

    @Test fun bareReferencesKeepTheirExactRangesAndExcludeNearbyWebAddressesAndPunctuation() {
        val text = "See src/Main.kt:42, Dockerfile and /work/build.gradle.kts#L8-L12. Visit https://example.com/src/Foo.kt:9 instead."
        val found = WorkspaceFileReference.findInText(text)
        assertEquals(listOf("src/Main.kt:42", "Dockerfile", "/work/build.gradle.kts#L8-L12"), found.map { text.substring(it.range) })
        assertEquals(listOf(42, null, 8), found.map { it.reference.line })
        val routes = "See src/routes/[id]/+page.svelte:7 and src/(legacy)/Main.kt#L6."
        assertEquals(listOf("src/routes/[id]/+page.svelte", "src/(legacy)/Main.kt"),
            WorkspaceFileReference.findInText(routes).map { it.reference.path })
    }
}

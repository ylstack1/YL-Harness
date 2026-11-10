package com.androidharness.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillStoreTest {

    @Test fun `missing skill lists every enabled name beyond twelve`() {
        val names = (1..23).map { "audit-skill-${it.toString().padStart(2, '0')}" }
        val s = store(bundled = names.associateWith { SkillStore.BundledSkill(it, skill(it, "test")) })
        val message = s.view("missing").exceptionOrNull()?.message.orEmpty()
        names.forEach { assertTrue(message, message.contains(it)) }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun skill(name: String, category: String, extra: String = "Body of $name."): String = """
        ---
        name: $name
        description: Trigger line for $name that tells the model when to load it.
        category: $category
        ---

        $extra
    """.trimIndent()

    private fun store(
        bundled: Map<String, SkillStore.BundledSkill> = mapOf(
            "git" to SkillStore.BundledSkill("git", skill("git", "repo")),
            "sketch" to SkillStore.BundledSkill(
                "sketch",
                skill("sketch", "design"),
                mapOf("references/catalog.md" to "# brands\nStripe: #635bff"),
            ),
        ),
        disabled: Set<String> = emptySet(),
        project: java.io.File? = null,
    ): SkillStore {
        val user = tmp.newFolder("user-skills")
        return SkillStore(
            bundled = bundled,
            userDir = user,
            projectDir = { project },
            disabled = { disabled },
        )
    }

    @Test fun `project scoped skills stay bound when the selected workspace changes`() {
        val alpha = tmp.newFolder("alpha")
        val beta = tmp.newFolder("beta")
        java.io.File(alpha, "git").apply { mkdirs(); resolve("SKILL.md").writeText(skill("git", "repo", "Alpha instructions")) }
        java.io.File(beta, "git").apply { mkdirs(); resolve("SKILL.md").writeText(skill("git", "repo", "Beta instructions")) }
        var selected = alpha
        val base = SkillStore(emptyMap(), tmp.newFolder("shared"), { selected }, { emptySet() })
        val a = base.forProject(alpha)
        val b = base.forProject(beta)
        selected = beta
        assertTrue(a.view("git").getOrThrow().content.contains("Alpha instructions"))
        assertTrue(b.view("git").getOrThrow().content.contains("Beta instructions"))
        assertTrue(base.view("git").getOrThrow().content.contains("Beta instructions"))
        assertTrue(base.forProject(null).list().isEmpty())
    }

    @Test
    fun `catalog lists enabled bundled skills grouped by category`() {
        val catalog = store().catalog()
        assertTrue(catalog.contains("  design:"))
        assertTrue(catalog.contains("    - sketch:"))
        assertTrue(catalog.contains("  repo:"))
        assertTrue(catalog.contains("    - git:"))
        assertTrue(catalog.contains("<available_skills>"))
    }

    @Test
    fun `disabled skills drop out of catalog and slash names`() {
        val s = store(disabled = setOf("git"))
        assertTrue("git" !in s.slashNames())
        assertTrue(!s.catalog().contains("- git:"))
        assertTrue(s.view("git").isFailure)
    }

    @Test
    fun `user skill overrides bundled`() {
        val s = store()
        s.saveUser(skill("git", "repo", "User git body."))
        val viewed = s.view("git").getOrThrow()
        assertEquals(SkillSource.USER, viewed.source)
        assertTrue(viewed.content.contains("User git body."))
    }

    @Test
    fun `project skill wins over user and bundled`() {
        val project = tmp.newFolder("project-skills")
        val dir = java.io.File(project, "git").apply { mkdirs() }
        java.io.File(dir, "SKILL.md").writeText(skill("git", "repo", "Project git body."))
        val s = store(project = project)
        s.saveUser(skill("git", "repo", "User git body."))
        val viewed = s.view("git").getOrThrow()
        assertEquals(SkillSource.PROJECT, viewed.source)
        assertTrue(viewed.content.contains("Project git body."))
    }

    @Test
    fun `support file loads and traversal is rejected`() {
        val s = store()
        val ok = s.view("sketch", "references/catalog.md").getOrThrow()
        assertTrue(ok.content.contains("Stripe"))
        assertTrue(s.view("sketch", "../secrets").isFailure)
        assertTrue(s.view("sketch", "SKILL.md").isFailure)
    }

    @Test
    fun `unknown skill lists available`() {
        val err = sError(store().view("nope"))
        assertTrue(err.contains("not found"))
        assertTrue(err.contains("git"))
    }

    @Test
    fun `patch copies bundled skill into user dir`() {
        val s = store()
        val result = s.patchUserOrCopy(
            "git",
            "Body of git.",
            "Body of git, plus never force-push.",
        )
        assertTrue(result.isSuccess)
        val viewed = s.view("git").getOrThrow()
        assertEquals(SkillSource.USER, viewed.source)
        assertTrue(viewed.content.contains("never force-push"))
    }

    @Test
    fun `delete user skill falls back to bundled`() {
        val s = store()
        s.saveUser(skill("git", "repo", "User override."))
        assertTrue(s.deleteUser("git"))
        val viewed = s.view("git").getOrThrow()
        assertEquals(SkillSource.BUNDLED, viewed.source)
    }

    private fun sError(result: Result<SkillView>): String =
        result.exceptionOrNull()?.message.orEmpty()
    @Test fun `multi workspace skills use the captured primary project`() {
        val alpha = tmp.newFolder("skill-alpha")
        val beta = tmp.newFolder("skill-beta")
        fun guide(root: java.io.File, body: String) {
            root.resolve(".harness/skills/androidharness-environment").mkdirs()
            root.resolve(".harness/skills/androidharness-environment/SKILL.md")
                .writeText(skill("androidharness-environment", "environment", body))
        }
        guide(alpha, "Alpha guide")
        guide(beta, "Beta guide")
        val bindings = listOf(
            com.androidharness.app.workspace.ChatWorkspaceBinding("a", "Alpha", false, com.androidharness.app.workspace.FileFs(alpha)),
            com.androidharness.app.workspace.ChatWorkspaceBinding("b", "Beta", true, com.androidharness.app.workspace.FileFs(beta)))
        val base = store()
        val captured = base.forWorkspace(com.androidharness.app.workspace.ChatWorkspaceFs("a", bindings))
        assertTrue(captured.view("androidharness-environment").getOrThrow().content.contains("Alpha guide"))
        assertTrue(base.forWorkspace(com.androidharness.app.workspace.ChatWorkspaceFs("b", bindings))
            .view("androidharness-environment").getOrThrow().content.contains("Beta guide"))
        assertTrue(captured.view("androidharness-environment").getOrThrow().content.contains("Alpha guide"))
    }

    @Test fun `bundled environment guide references all load through the app skill parser`() {
        val folder = java.io.File("src/main/assets/skills/androidharness-environment")
        val raw = folder.resolve("SKILL.md").readText()
        val parsed = SkillParser.parse(raw)
        assertEquals("androidharness-environment", parsed.name)
        val files = folder.walkTopDown().filter { it.isFile && it.name != "SKILL.md" }
            .associate { it.relativeTo(folder).invariantSeparatorsPath to it.readText() }
        val base = store(bundled = mapOf(parsed.name to SkillStore.BundledSkill(parsed.name, raw, files)))
        assertTrue(base.view(parsed.name).isSuccess)
        val paths = Regex("`((?:references|templates)/[^`]+)`").findAll(raw).map { it.groupValues[1] }.toList()
        assertEquals(7, paths.size)
        assertEquals(files.keys, paths.toSet())
        paths.forEach { assertTrue(it, base.view(parsed.name, it).getOrThrow().content.isNotBlank()) }
    }

}

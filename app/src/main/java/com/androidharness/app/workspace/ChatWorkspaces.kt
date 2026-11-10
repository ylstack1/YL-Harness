package com.androidharness.app.workspace

import com.androidharness.app.tools.ToolFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ChatWorkspaceGrant(val projectId: String, val canEdit: Boolean = false)

/** Missing selection means a legacy chat with one writable workspace. Invalid selections never widen access. */
@Serializable
data class ChatWorkspaceSelection(val primaryId: String, val grants: List<ChatWorkspaceGrant>) {
    fun validated(): ChatWorkspaceSelection = apply {
        require(grants.isNotEmpty()) { "Choose at least one workspace." }
        require(grants.all { it.projectId.isNotBlank() } && grants.map { it.projectId }.distinct().size == grants.size) {
            "Each workspace must be selected once."
        }
        require(grants.any { it.projectId == primaryId }) { "Choose a primary workspace from the selected projects." }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun single(id: String) = ChatWorkspaceSelection(id, listOf(ChatWorkspaceGrant(id, true)))
        fun decode(value: String): ChatWorkspaceSelection = try {
            json.decodeFromString<ChatWorkspaceSelection>(value).validated()
        } catch (e: Exception) { throw IllegalStateException("This chat's workspace access could not be read. Open Workspaces to choose it again.", e) }
        fun encode(value: ChatWorkspaceSelection): String = json.encodeToString(value.validated())
    }
}

data class ChatWorkspaceBinding(val id: String, val name: String, val canEdit: Boolean, val fs: WorkspaceFs)

/** A captured set of grants. Tool calls select a binding; saved paths always include its stable project id. */
class ChatWorkspaceFs(val primaryId: String, val bindings: List<ChatWorkspaceBinding>) : WorkspaceFs {
    private val localRoots = bindings.mapNotNull { binding -> binding.fs.shellRoot?.let { binding.id to it.canonicalFile.toPath() } }.toMap()
    init {
        require(bindings.isNotEmpty() && bindings.map { it.id }.distinct().size == bindings.size)
        require(bindings.any { it.id == primaryId })
        require(bindings.none { '/' in it.id || it.id == "." || it.id == ".." })
    }
    val primary: ChatWorkspaceBinding get() = binding(primaryId)
    val hasReadOnly: Boolean get() = bindings.any { !it.canEdit }
    override val shellRoot: File? = null // Never let Full access replace the selected roots with UnboundedFileFs.
    override val isSaf = true
    override val displayPath: String = bindings.sortedBy { it.id }.joinToString(
        prefix = "${bindings.first { it.id == primaryId }.fs.displayPath} [primary=$primaryId; ", postfix = "]",
    ) { "${it.id}:${if (it.canEdit) "edit" else "read"}:${it.fs.displayPath}" }

    fun binding(id: String?): ChatWorkspaceBinding = bindings.firstOrNull { it.id == (id ?: primaryId) }
        ?: throw ToolFailure("Workspace is not selected for this chat: $id. Open Workspaces to change access.")

    fun key(id: String, path: String): String {
        binding(id)
        val normalized = File("/", path).normalize().path.removePrefix("/")
        require(!path.startsWith('/') && path.split('/').none { it == ".." }) { "Use paths relative to the selected workspace." }
        require(!path.startsWith("$PREFIX/")) { "Use workspace_id to select a workspace and a path relative to it." }
        return "$PREFIX/$id" + if (normalized.isBlank() || normalized == ".") "" else "/$normalized"
    }

    fun target(path: String): Pair<ChatWorkspaceBinding, String> {
        val clean = path.removePrefix("./").trimEnd('/')
        if (clean.startsWith("$PREFIX/")) {
            val rest = clean.removePrefix("$PREFIX/")
            val id = rest.substringBefore('/')
            val relative = rest.substringAfter('/', ".")
            key(id, relative) // Reject traversal before touching any tree.
            return binding(id) to relative
        }
        key(primaryId, clean.ifBlank { "." })
        return primary to clean.ifBlank { "." }
    }

    fun displayPathFor(path: String): String = runCatching {
        val (binding, relative) = target(path)
        binding.name + if (relative in listOf("", ".")) "" else "/$relative"
    }.getOrDefault(path)

    override fun resolve(path: String): FsNode {
        if (path.removePrefix("./").trimEnd('/') == PREFIX) return RootsNode()
        val (binding, relative) = target(path)
        checkLocalPath(binding, relative)
        return BoundNode(binding, binding.fs.resolve(relative))
    }

    override fun walk(path: String): Sequence<FsNode> {
        val (binding, relative) = target(path)
        checkLocalPath(binding, relative)
        return binding.fs.walk(relative).map { BoundNode(binding, it) }
    }

    /** Use for tool execution as well as UI file operations; every node operation retains the grant. */
    fun fileAccess(binding: ChatWorkspaceBinding): WorkspaceFs = object : WorkspaceFs {
        override val displayPath = binding.fs.displayPath
        override val shellRoot: File? = null
        override val isSaf = binding.fs.isSaf
        override fun resolve(path: String): FsNode {
            key(binding.id, path)
            checkLocalPath(binding, path)
            return BoundNode(binding, binding.fs.resolve(path), qualified = false)
        }
        override fun walk(path: String): Sequence<FsNode> {
            key(binding.id, path)
            checkLocalPath(binding, path)
            return binding.fs.walk(path).map { BoundNode(binding, it, qualified = false) }
        }
    }

    private fun checkLocalPath(binding: ChatWorkspaceBinding, path: String) {
        val root = binding.fs.shellRoot ?: return
        val capturedRoot = localRoots.getValue(binding.id)
        require(root.canonicalFile.toPath() == capturedRoot && root.resolve(path).canonicalFile.toPath().startsWith(capturedRoot)) {
            "Path is outside ${binding.name} and was blocked."
        }
    }

    private inner class BoundNode(val binding: ChatWorkspaceBinding, val node: FsNode, val qualified: Boolean = true) : FsNode by node {
        override val relPath get() = if (qualified) key(binding.id, node.relPath) else node.relPath
        override val exists get(): Boolean { read(); return node.exists }
        override val isFile get(): Boolean { read(); return node.isFile }
        override val isDirectory get(): Boolean { read(); return node.isDirectory }
        override val length get(): Long { read(); return node.length }
        private fun read() = checkLocalPath(binding, node.relPath)
        private fun edit() { read(); check(binding.canEdit) { "${binding.name} is read only in this chat. Change access in Workspaces to allow edits." } }
        override fun list(): List<FsNode> {
            read()
            val children = node.list().map { BoundNode(binding, it, qualified) }
            return if (qualified && binding.id == primaryId && node.relPath in listOf("", ".")) listOf(RootsNode()) + children else children
        }
        override fun readText(): String { read(); return node.readText() }
        override fun openInputStream(): java.io.InputStream? { read(); return node.openInputStream() }
        override fun isBinary(): Boolean { read(); return node.isBinary() }
        override fun writeText(content: String) { edit(); node.writeText(content) }
        override fun writeBytes(data: ByteArray) { edit(); node.writeBytes(data) }
        override fun mkdirs() { edit(); node.mkdirs() }
        override fun delete(): Boolean { edit(); return node.delete() }
        override fun renameTo(newName: String): Boolean { edit(); require('/' !in newName && newName !in listOf(".", "..")); return node.renameTo(newName) }
        override fun createFile(name: String): FsNode { edit(); require('/' !in name && name !in listOf(".", "..")); return BoundNode(binding, node.createFile(name), qualified) }
        override fun createDir(name: String): FsNode { edit(); require('/' !in name && name !in listOf(".", "..")); return BoundNode(binding, node.createDir(name), qualified) }
    }

    private inner class RootsNode : FsNode {
        override val relPath = PREFIX
        override val name = "Workspaces"
        override val exists = true
        override val isDirectory = true
        override val isFile = false
        override val length = 0L
        override fun list(): List<FsNode> = bindings.map { binding ->
            val root = resolve("$PREFIX/${binding.id}")
            object : FsNode by root { override val name = binding.name }
        }
        private fun blocked(): Nothing = throw ToolFailure("Choose a workspace inside Workspaces first.")
        override fun readText(): String = blocked()
        override fun openInputStream(): java.io.InputStream? = blocked()
        override fun isBinary() = false
        override fun writeText(content: String) = blocked()
        override fun writeBytes(data: ByteArray) = blocked()
        override fun mkdirs() = blocked()
        override fun delete(): Boolean = blocked()
        override fun renameTo(newName: String): Boolean = blocked()
        override fun createFile(name: String): FsNode = blocked()
        override fun createDir(name: String): FsNode = blocked()
    }

    companion object { const val PREFIX = "@workspaces" }
}

fun WorkspaceFs.workspacePaths(): List<String> = if (this is ChatWorkspaceFs) bindings.flatMap { it.fs.workspacePaths() }
    else listOf(shellRoot?.canonicalPath ?: displayPath)

fun workspacePathsOverlap(left: List<String>, right: List<String>): Boolean = left.any { a -> right.any { b ->
    val x = a.trimEnd('/'); val y = b.trimEnd('/')
    x == y || x.startsWith("$y/") || y.startsWith("$x/")
} }

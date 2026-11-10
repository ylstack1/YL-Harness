package com.androidharness.app.data.transfer

import com.androidharness.app.data.KeyStoreManager
import com.androidharness.app.workspace.SshConnection
import com.androidharness.app.workspace.SshConnections
import com.androidharness.app.workspace.SftpLease
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.*
import java.time.Duration
import java.util.UUID

@Serializable internal enum class SyncProtocol { FTP, SFTP }
@Serializable internal data class SyncProfile(
    val id: String = UUID.randomUUID().toString(), val name: String = "", val protocol: SyncProtocol = SyncProtocol.SFTP,
    val host: String = "", val port: Int = 22, val username: String = "", val password: String = "", val remoteFolder: String = "/",
    val fingerprint: String = "", val privateKey: String = "", val passphrase: String = "",
) {
    override fun toString() = "SyncProfile(id=$id, protocol=$protocol)"
    fun validate(pin: Boolean = true) {
        require(id.matches(Regex("[A-Za-z0-9-]{1,100}")) && name.length <= 100) { "Enter a short connection name." }
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it.isISOControl() || it in "/\\@?#" }) { "Enter a host without a URL or path." }
        require(port in 1..65535) { "Enter a port from 1 to 65535." }
        require(username.isNotBlank() && username.length <= 256 && username.none(Char::isISOControl)) { "Enter a username." }
        require(password.length <= 8192 && password.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Enter a valid password." }
        require(remoteFolder.startsWith('/') && remoteFolder.length <= 2048) { "Enter an absolute remote folder, starting with /." }
        TransferPaths.relative(remoteFolder.trim('/'))
        if (protocol == SyncProtocol.SFTP) ssh().validate(pin)
    }
    fun ssh() = SshConnection(id, host, port, username, password, privateKey, passphrase, fingerprint, termux = false)
}

internal class SyncProfiles(private val keys: KeyStoreManager) {
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized fun all(): List<SyncProfile> = keys.localSecret("sync_profiles")?.let { json.decodeFromString<List<SyncProfile>>(it) }.orEmpty()
    @Synchronized fun save(profile: SyncProfile) {
        profile.validate()
        val next = all().filterNot { it.id == profile.id } + profile
        require(next.size <= 30) { "Save at most 30 sync connections." }
        keys.putLocalSecret("sync_profiles", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncProfile.serializer()), next))
    }
    @Synchronized fun remove(id: String) { keys.putLocalSecret("sync_profiles", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SyncProfile.serializer()), all().filterNot { it.id == id })) }
}

internal interface RemoteTransferFiles : TransferFiles { fun connect() }
internal fun remoteTransfer(profile: SyncProfile, ssh: SshConnections): RemoteTransferFiles =
    if (profile.protocol == SyncProtocol.FTP) FtpTransferFiles(profile) else SftpTransferFiles(profile, ssh)

internal class FtpTransferFiles(private val profile: SyncProfile) : RemoteTransferFiles {
    private val ftp = FTPClient()
    private val sockets = TransferSockets()
    @Volatile private var closed = false
    private var base = ""
    override fun connect() {
        check(!closed) { "Transfer cancelled." }
        profile.validate()
        ftp.setSocketFactory(sockets)
        ftp.controlEncoding = "UTF-8"; ftp.connectTimeout = 15_000; ftp.defaultTimeout = 15_000
        ftp.setDataTimeout(Duration.ofSeconds(15)); ftp.setIpAddressFromPasvResponse(false); ftp.setUseEPSVwithIPv4(true)
        try {
            ftp.connect(profile.host, profile.port)
            check(!closed) { "Transfer cancelled." }
            check(FTPReply.isPositiveCompletion(ftp.replyCode) && ftp.login(profile.username, profile.password)) { "FTP login failed." }
            ftp.soTimeout = 15_000; ftp.enterLocalPassiveMode(); check(ftp.setFileType(FTP.BINARY_FILE_TYPE)) { "FTP binary transfers are unavailable." }
            check(ftp.changeWorkingDirectory(profile.remoteFolder)) { "Remote folder unavailable. Create it on the server first." }
            base = ftp.printWorkingDirectory().trimEnd('/')
        } catch (e: Exception) { close(); throw e }
    }
    private fun absolute(path: String) = "$base/${TransferPaths.relative(path)}"
    override fun stat(path: String): TransferEntry? {
        if (path.isEmpty()) return TransferEntry("", true)
        val found = ftp.mlistFile(absolute(path))
        if (found != null) {
            require(!found.isSymbolicLink && (found.isFile || found.isDirectory)) { "Remote symbolic links and special files are skipped." }
            return TransferEntry(path, found.isDirectory, found.size)
        }
        if (ftp.replyCode == 550) return null
        val parent = path.substringBeforeLast('/', "")
        val files = ftp.listFiles(absolute(parent))
        if (ftp.replyCode == 550) return null
        check(FTPReply.isPositiveCompletion(ftp.replyCode)) { "Cannot inspect the remote folder." }
        val file = files.singleOrNull { it.name == path.substringAfterLast('/') } ?: return null
        require(!file.isSymbolicLink && (file.isFile || file.isDirectory)) { "Remote symbolic links and special files are skipped." }
        return TransferEntry(path, file.isDirectory, file.size)
    }
    override fun list(path: String): List<TransferEntry> {
        var entries = ftp.mlistDir(absolute(path))
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) entries = ftp.listFiles(absolute(path))
        check(FTPReply.isPositiveCompletion(ftp.replyCode)) { "Cannot list the remote folder." }
        require(entries.size <= MAX_TRANSFER_ENTRIES) { "Remote folder has too many entries." }
        return entries.filter { it.name !in listOf(".", "..") && !it.isSymbolicLink && (it.isFile || it.isDirectory) }.map {
            TransferPaths.relative(it.name); require('/' !in it.name) { "Invalid remote filename." }
            TransferEntry(if (path.isEmpty()) it.name else "$path/${it.name}", it.isDirectory, it.size)
        }
    }
    override fun read(path: String): InputStream {
        val input = ftp.retrieveFileStream(absolute(path)) ?: error("Cannot read remote file.")
        return object : FilterInputStream(input) { override fun close() { super.close(); check(ftp.completePendingCommand()) { "Remote file transfer failed." } } }
    }
    override fun mkdir(path: String) {
        var current = ""
        if (path.isNotEmpty()) path.split('/').forEach { part ->
            current = if (current.isEmpty()) part else "$current/$part"
            val entry = stat(current)
            if (entry == null) check(ftp.makeDirectory(absolute(current))) { "Cannot create remote folder." }
            else require(entry.directory) { "A remote folder conflicts with a file." }
        }
    }
    override fun writeAtomic(path: String, input: InputStream, beforeCommit: () -> Unit) {
        mkdir(path.substringBeforeLast('/', ""))
        val parent = path.substringBeforeLast('/', "")
        val temp = (if (parent.isEmpty()) "" else "$parent/") + ".harness-sync-${UUID.randomUUID()}"
        try {
            check(ftp.storeFile(absolute(temp), LimitedTransferInput(input))) { "Remote upload failed." }
            beforeCommit()
            check(ftp.rename(absolute(temp), absolute(path))) { "Remote server could not publish the file. Existing file was kept." }
        } finally { runCatching { ftp.deleteFile(absolute(temp)) } }
    }
    override fun close() { closed = true; sockets.close(); runCatching { ftp.disconnect() } }
}

internal class SftpTransferFiles(private val profile: SyncProfile, private val ssh: SshConnections) : RemoteTransferFiles {
    private val sockets = TransferSockets()
    @Volatile private var lease: SftpLease? = null
    @Volatile private var closed = false
    @Volatile private var connecting: com.jcraft.jsch.Session? = null
    private var base = ""
    private val channel get() = checkNotNull(lease) { "SFTP is disconnected." }.channel
    override fun connect() {
        profile.validate()
        check(!closed) { "Transfer cancelled." }
        val opened = ssh.openTransfer(profile.ssh()) { session ->
            connecting = session
            session.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
                override fun createSocket(host: String, port: Int) = sockets.createSocket(host, port)
                override fun getInputStream(socket: java.net.Socket) = socket.getInputStream()
                override fun getOutputStream(socket: java.net.Socket) = socket.getOutputStream()
            })
            if (closed) { session.disconnect(); throw java.util.concurrent.CancellationException("Transfer cancelled.") }
        }
        lease = opened
        if (closed) { opened.close(); error("Transfer cancelled.") }
        try {
            base = channel.realpath(profile.remoteFolder).trimEnd('/')
            check(channel.stat(if (base.isEmpty()) "/" else base).isDir) { "Remote folder unavailable." }
        } catch (e: Exception) { close(); throw e }
    }
    private fun absolute(path: String) = "$base/${TransferPaths.relative(path)}"
    override fun stat(path: String): TransferEntry? = try {
        val value = channel.lstat(absolute(path))
        require(!value.isLink && (value.isDir || value.isReg)) { "Remote symbolic links and special files are skipped." }
        TransferEntry(path, value.isDir, value.size)
    } catch (e: SftpException) { if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) null else throw e }
    override fun list(path: String): List<TransferEntry> {
        val values = channel.ls(absolute(path)); require(values.size <= MAX_TRANSFER_ENTRIES) { "Remote folder has too many entries." }
        return values.filterIsInstance<ChannelSftp.LsEntry>().filter { it.filename !in listOf(".", "..") && !it.attrs.isLink && (it.attrs.isDir || it.attrs.isReg) }.map {
            TransferPaths.relative(it.filename); require('/' !in it.filename) { "Invalid remote filename." }
            TransferEntry(if (path.isEmpty()) it.filename else "$path/${it.filename}", it.attrs.isDir, it.attrs.size)
        }
    }
    override fun read(path: String): InputStream { require(stat(path)?.directory == false) { "Remote file unavailable." }; return channel.get(absolute(path)) }
    override fun mkdir(path: String) {
        var current = ""
        if (path.isNotEmpty()) path.split('/').forEach { part ->
            current = if (current.isEmpty()) part else "$current/$part"
            val existing = stat(current)
            if (existing == null) channel.mkdir(absolute(current)) else require(existing.directory) { "A remote folder conflicts with a file." }
        }
    }
    override fun writeAtomic(path: String, input: InputStream, beforeCommit: () -> Unit) {
        mkdir(path.substringBeforeLast('/', ""))
        val parent = path.substringBeforeLast('/', "")
        val temp = (if (parent.isEmpty()) "" else "$parent/") + ".harness-sync-${UUID.randomUUID()}"
        try {
            channel.put(LimitedTransferInput(input), absolute(temp)); beforeCommit()
            if (stat(path) != null) channel.chmod(channel.lstat(absolute(path)).permissions, absolute(temp))
            channel.rename(absolute(temp), absolute(path))
        }
        finally { runCatching { channel.rm(absolute(temp)) } }
    }
    override fun close() { closed = true; sockets.close(); connecting?.disconnect(); lease?.close() }
}

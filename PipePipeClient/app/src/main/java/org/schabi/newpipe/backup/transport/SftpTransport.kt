package org.schabi.newpipe.backup.transport

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Uploads backups over SFTP. The server's key is trusted on first use: [hostKey] holds the key
 * that was seen, which the caller stores and passes as [ServerSettings.trustedHostKey] next time.
 */
class SftpTransport(private val settings: ServerSettings) : BackupTransport {
    private var session: Session? = null
    private var channel: ChannelSftp? = null

    /** The server's key as "type base64", known once connected. */
    var hostKey: String? = null
        private set

    /** The SHA-256 fingerprint of the server's key, known once connected. */
    var hostKeyFingerprint: String? = null
        private set

    override fun connect() {
        val jsch = JSch()
        try {
            if (settings.privateKey.isNotBlank()) {
                jsch.addIdentity(
                    "pipepipe-backup",
                    settings.privateKey.trim().toByteArray(Charsets.UTF_8),
                    null,
                    settings.privateKeyPassphrase.takeIf { it.isNotEmpty() }
                        ?.toByteArray(Charsets.UTF_8)
                )
            }
            val trustedHostKey = settings.trustedHostKey?.takeIf { it.isNotBlank() }
            if (trustedHostKey != null) {
                jsch.setKnownHosts(
                    ByteArrayInputStream("$HOST_KEY_ALIAS $trustedHostKey\n".toByteArray())
                )
            }

            val newSession = jsch.getSession(settings.username, settings.host, settings.effectivePort)
            session = newSession
            newSession.setHostKeyAlias(HOST_KEY_ALIAS)
            if (settings.password.isNotEmpty()) {
                newSession.setPassword(settings.password)
            }
            newSession.userInfo = PasswordUserInfo(settings.password)
            // Without a trusted key, the first key seen is accepted and reported in hostKey
            newSession.setConfig("StrictHostKeyChecking", if (trustedHostKey != null) "yes" else "no")
            newSession.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
            newSession.timeout = settings.timeoutMillis
            newSession.connect(settings.timeoutMillis)

            newSession.hostKey?.let {
                hostKey = "${it.type} ${it.key}"
                hostKeyFingerprint = it.getFingerPrint(jsch)
            }

            val newChannel = newSession.openChannel("sftp") as ChannelSftp
            channel = newChannel
            newChannel.connect(settings.timeoutMillis)
            openFolder(newChannel)
        } catch (e: JSchHostKeyException) {
            // With a trusted key, any other key, even of another type, means a different server
            close()
            throw HostKeyChangedException("The server's SSH key changed", e)
        } catch (e: JSchException) {
            close()
            val message = e.message ?: e.toString()
            if (message.contains("Auth fail", ignoreCase = true) ||
                message.contains("Auth cancel", ignoreCase = true)
            ) {
                throw AuthenticationException("The server rejected the login", e)
            }
            throw IOException(message, e)
        } catch (e: SftpException) {
            close()
            throw IOException("Could not open the backup folder: ${e.message}", e)
        }
    }

    private fun openFolder(sftp: ChannelSftp) {
        if (settings.folder.trim().startsWith("/")) {
            sftp.cd("/")
        }
        for (segment in BackupTransport.folderSegments(settings.folder)) {
            try {
                sftp.cd(segment)
            } catch (e: SftpException) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw e
                }
                sftp.mkdir(segment)
                sftp.cd(segment)
            }
        }
    }

    private fun requireChannel(): ChannelSftp =
        channel ?: throw IOException("Not connected")

    override fun list(): List<RemoteFile> = sftp {
        requireChannel().ls(".")
            .filter { !it.attrs.isDir && it.filename != "." && it.filename != ".." }
            .map { RemoteFile(it.filename, it.attrs.size) }
    }

    override fun upload(source: File, name: String) = sftp {
        val sftp = requireChannel()
        val partial = name + BackupTransport.PARTIAL_SUFFIX
        FileInputStream(source).use { sftp.put(it, partial, ChannelSftp.OVERWRITE) }
        try {
            sftp.rename(partial, name)
        } catch (e: SftpException) {
            // Plain SFTP renames refuse to replace an existing file
            sftp.rm(name)
            sftp.rename(partial, name)
        }
    }

    override fun download(name: String, target: File) = sftp {
        FileOutputStream(target).use { requireChannel().get(name, it) }
    }

    override fun delete(name: String) = sftp {
        requireChannel().rm(name)
    }

    override fun close() {
        channel?.disconnect()
        channel = null
        session?.disconnect()
        session = null
    }

    private inline fun <T> sftp(action: () -> T): T {
        try {
            return action()
        } catch (e: SftpException) {
            throw IOException(e.message ?: e.toString(), e)
        }
    }

    /** Answers password and keyboard-interactive prompts with the configured password. */
    private class PasswordUserInfo(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String = password
        override fun promptPassword(message: String?): Boolean = password.isNotEmpty()
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) {}

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?
        ): Array<String>? {
            if (password.isEmpty() || prompt == null) {
                return null
            }
            return Array(prompt.size) { password }
        }
    }

    companion object {
        /** The name the trusted key is stored under, independent of the host name and port. */
        private const val HOST_KEY_ALIAS = "pipepipe-backup-server"
    }
}

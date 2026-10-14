package org.schabi.newpipe.backup.transport

import java.io.Closeable
import java.io.File
import java.io.IOException

/** A file in the backup folder on the server. */
data class RemoteFile(
    val name: String,
    /** Size in bytes, or -1 when the server does not say. */
    val size: Long
)

/**
 * A connection to a folder on a server that backups are uploaded to.
 *
 * This code must not use Android classes so that it can be tested against real servers on the
 * JVM.
 */
interface BackupTransport : Closeable {
    /** Connects, logs in and creates the backup folder if it does not exist. */
    @Throws(IOException::class)
    fun connect()

    /** Lists the files, not folders, in the backup folder. */
    @Throws(IOException::class)
    fun list(): List<RemoteFile>

    /**
     * Uploads [source] as [name]. The file is uploaded under a temporary name first, so that an
     * interrupted upload never looks like a complete backup.
     */
    @Throws(IOException::class)
    fun upload(source: File, name: String)

    @Throws(IOException::class)
    fun download(name: String, target: File)

    @Throws(IOException::class)
    fun delete(name: String)

    companion object {
        const val PARTIAL_SUFFIX = ".part"

        @JvmStatic
        fun create(settings: ServerSettings): BackupTransport = when (settings.protocol) {
            Protocol.SFTP -> SftpTransport(settings)
            Protocol.FTP, Protocol.FTPS -> FtpTransport(settings)
            Protocol.WEBDAV_HTTPS, Protocol.WEBDAV_HTTP -> WebDavTransport(settings)
        }

        /** Splits a folder path into its non-empty parts. */
        @JvmStatic
        fun folderSegments(folder: String): List<String> =
            folder.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
    }
}

/** Thrown when the login is rejected. */
class AuthenticationException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** Thrown when an SFTP server presents a different key than the one that was trusted. */
class HostKeyChangedException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

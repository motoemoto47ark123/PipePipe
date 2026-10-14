package org.schabi.newpipe.backup.transport

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import javax.net.ssl.HttpsURLConnection

/** Uploads backups over FTP, or FTP with explicit TLS (FTPS). */
class FtpTransport(private val settings: ServerSettings) : BackupTransport {
    private var client: FTPClient? = null

    override fun connect() {
        val ftp = createClient()
        client = ftp
        try {
            ftp.connectTimeout = settings.timeoutMillis
            ftp.defaultTimeout = settings.timeoutMillis
            ftp.setDataTimeout(settings.timeoutMillis)
            ftp.connect(settings.host, settings.effectivePort)
            if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
                throw IOException("The server refused the connection: ${reply(ftp)}")
            }
            if (ftp is FTPSClient) {
                // Encrypt file transfers as well, not only the commands
                ftp.execPBSZ(0)
                ftp.execPROT("P")
            }
            val username = settings.username.ifEmpty { "anonymous" }
            if (!ftp.login(username, settings.password)) {
                throw AuthenticationException("The server rejected the login: ${reply(ftp)}")
            }
            ftp.enterLocalPassiveMode()
            // Extended passive mode only sends a port, which works through NAT; servers without
            // it get the classic passive mode
            ftp.setUseEPSVwithIPv4(true)
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw IOException("Could not switch to binary mode: ${reply(ftp)}")
            }
            openFolder(ftp)
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    private fun createClient(): FTPClient {
        if (settings.protocol != Protocol.FTPS) {
            return FTPClient()
        }
        val ftps = FTPSClient(false)
        if (settings.allowUntrustedCertificates) {
            ftps.trustManager = TrustManagerUtils.getAcceptAllTrustManager()
        } else {
            // The default trust manager of FTPSClient only checks the validity dates
            try {
                ftps.trustManager = TrustManagerUtils.getDefaultTrustManager(null)
            } catch (e: GeneralSecurityException) {
                throw IOException("Could not load the trusted certificates", e)
            }
            ftps.setHostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
            ftps.setEndpointCheckingEnabled(true)
        }
        return ftps
    }

    private fun openFolder(ftp: FTPClient) {
        if (settings.folder.trim().startsWith("/") && !ftp.changeWorkingDirectory("/")) {
            throw IOException("Could not open / on the server: ${reply(ftp)}")
        }
        for (segment in BackupTransport.folderSegments(settings.folder)) {
            if (!ftp.changeWorkingDirectory(segment) &&
                (!ftp.makeDirectory(segment) || !ftp.changeWorkingDirectory(segment))
            ) {
                throw IOException("Could not open or create the folder $segment: ${reply(ftp)}")
            }
        }
    }

    private fun requireClient(): FTPClient = client ?: throw IOException("Not connected")

    override fun list(): List<RemoteFile> {
        val ftp = requireClient()
        // Names only: the format of detailed listings differs between servers
        val names = ftp.listNames()
            ?: if (FTPReply.isPositiveCompletion(ftp.replyCode)) {
                emptyArray()
            } else {
                throw IOException("Could not list the backup folder: ${reply(ftp)}")
            }
        return names
            .map { it.substringAfterLast('/') }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { RemoteFile(it, -1) }
    }

    override fun upload(source: File, name: String) {
        val ftp = requireClient()
        val partial = name + BackupTransport.PARTIAL_SUFFIX
        val stored = FileInputStream(source).use { ftp.storeFile(partial, it) }
        if (!stored) {
            throw IOException("Could not upload $name: ${reply(ftp)}")
        }
        if (!ftp.rename(partial, name)) {
            // Some servers refuse to replace an existing file
            ftp.deleteFile(name)
            if (!ftp.rename(partial, name)) {
                throw IOException("Could not rename the uploaded file: ${reply(ftp)}")
            }
        }
    }

    override fun download(name: String, target: File) {
        val ftp = requireClient()
        val retrieved = FileOutputStream(target).use { ftp.retrieveFile(name, it) }
        if (!retrieved) {
            throw IOException("Could not download $name: ${reply(ftp)}")
        }
    }

    override fun delete(name: String) {
        val ftp = requireClient()
        if (!ftp.deleteFile(name)) {
            throw IOException("Could not delete $name: ${reply(ftp)}")
        }
    }

    override fun close() {
        val ftp = client ?: return
        client = null
        try {
            if (ftp.isConnected) {
                ftp.logout()
            }
        } catch (ignored: IOException) {
        }
        try {
            ftp.disconnect()
        } catch (ignored: IOException) {
        }
    }

    private fun reply(ftp: FTPClient): String = ftp.replyString?.trim().orEmpty()
}

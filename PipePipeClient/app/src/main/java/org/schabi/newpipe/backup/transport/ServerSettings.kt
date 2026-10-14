package org.schabi.newpipe.backup.transport

enum class Protocol(val defaultPort: Int) {
    SFTP(22),

    /** Plain FTP, which sends the password unencrypted */
    FTP(21),

    /** FTP with explicit TLS (AUTH TLS) */
    FTPS(21),

    /** FTP with implicit TLS, where the connection starts with TLS */
    FTPS_IMPLICIT(990),
    WEBDAV_HTTPS(443),
    WEBDAV_HTTP(80);

    val usesTls: Boolean
        get() = this == FTPS || this == FTPS_IMPLICIT || this == WEBDAV_HTTPS

    companion object {
        @JvmStatic
        fun fromName(name: String?): Protocol =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: SFTP
    }
}

/** Everything needed to connect to the backup folder on a server. */
data class ServerSettings(
    val protocol: Protocol,
    val host: String,
    /** The port, or 0 for the protocol's default port. */
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** SFTP only: a private key in OpenSSH, PEM or PuTTY format. */
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
    /** The backup folder, relative to the login folder unless it starts with a slash. */
    val folder: String = "",
    /** SFTP only: the trusted server key as "type base64", or null to trust the first key seen. */
    val trustedHostKey: String? = null,
    /** FTPS and WebDAV over HTTPS: accept certificates that cannot be verified. */
    val allowUntrustedCertificates: Boolean = false,
    val timeoutMillis: Int = 30_000
) {
    val effectivePort: Int
        get() = if (port in 1..65535) port else protocol.defaultPort

    override fun toString(): String =
        "ServerSettings(protocol=$protocol, host=$host, port=$effectivePort, folder=$folder)"
}

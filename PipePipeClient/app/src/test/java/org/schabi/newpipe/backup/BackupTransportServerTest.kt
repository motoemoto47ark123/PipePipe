package org.schabi.newpipe.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.schabi.newpipe.backup.transport.AuthenticationException
import org.schabi.newpipe.backup.transport.BackupTransport
import org.schabi.newpipe.backup.transport.HostKeyChangedException
import org.schabi.newpipe.backup.transport.Protocol
import org.schabi.newpipe.backup.transport.ServerSettings
import org.schabi.newpipe.backup.transport.SftpTransport
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

/**
 * Uploads, lists, downloads and deletes files on real servers. The servers are started by
 * .github/workflows/app-tests.yml and described by environment variables; without them the tests
 * are skipped.
 */
class BackupTransportServerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun env(name: String): String? = System.getenv("PIPEPIPE_TEST_$name")?.takeIf { it.isNotBlank() }

    private val host = env("SERVER_HOST") ?: "127.0.0.1"
    private val user = env("SERVER_USER") ?: "pipetest"
    private val password = env("SERVER_PASSWORD") ?: ""

    /** A new folder for each test, two levels deep to test creating folders. */
    private val folder = "pipepipe-tests/${UUID.randomUUID()}/backups"

    private fun settings(protocol: Protocol, portVariable: String): ServerSettings {
        val port = env(portVariable)
        assumeTrue("$portVariable is not set", port != null)
        return ServerSettings(
            protocol = protocol,
            host = host,
            port = port!!.toInt(),
            username = user,
            password = password,
            folder = folder,
            allowUntrustedCertificates = true,
            timeoutMillis = 15_000
        )
    }

    private fun randomFile(name: String, size: Int): File {
        val file = temporaryFolder.newFile(name)
        file.writeBytes(Random.nextBytes(size))
        return file
    }

    /** Runs every operation a backup needs. */
    private fun checkRoundTrip(settings: ServerSettings) {
        val name = BackupRetention.nameFor(java.util.Date())
        val first = randomFile("first.zip", 300_000)
        val second = randomFile("second.zip", 1_000)

        BackupTransport.create(settings).use { transport ->
            transport.connect()
            assertEquals(emptyList<String>(), transport.list().map { it.name })

            transport.upload(first, name)
            // Uploading the same name again replaces the file
            transport.upload(second, name)
            val listed = transport.list()
            assertEquals(listOf(name), listed.map { it.name })
            if (listed[0].size >= 0) {
                assertEquals(second.length(), listed[0].size)
            }

            val downloaded = File(temporaryFolder.root, "downloaded.zip")
            transport.download(name, downloaded)
            assertArrayEquals(second.readBytes(), downloaded.readBytes())

            transport.delete(name)
            assertEquals(emptyList<String>(), transport.list().map { it.name })
        }

        // Connecting again finds the existing folder
        BackupTransport.create(settings).use { transport ->
            transport.connect()
            transport.upload(first, name)
            assertEquals(listOf(name), transport.list().map { it.name })
            transport.delete(name)
        }
    }

    private fun assertLoginRejected(settings: ServerSettings) {
        try {
            BackupTransport.create(settings).use { it.connect() }
            fail("The login was accepted")
        } catch (e: AuthenticationException) {
            // expected
        }
    }

    @Test
    fun sftpWithPassword() {
        val settings = settings(Protocol.SFTP, "SFTP_PORT")
        checkRoundTrip(settings)
        assertLoginRejected(settings.copy(password = "wrong password"))
    }

    @Test
    fun sftpWithEd25519Key() {
        val key = env("SFTP_KEY_ED25519")
        assumeTrue("SFTP_KEY_ED25519 is not set", key != null)
        checkRoundTrip(
            settings(Protocol.SFTP, "SFTP_PORT").copy(password = "", privateKey = File(key!!).readText())
        )
    }

    @Test
    fun sftpWithEncryptedRsaKey() {
        val key = env("SFTP_KEY_RSA")
        assumeTrue("SFTP_KEY_RSA is not set", key != null)
        val settings = settings(Protocol.SFTP, "SFTP_PORT").copy(
            password = "",
            privateKey = File(key!!).readText(),
            privateKeyPassphrase = env("SFTP_KEY_RSA_PASSPHRASE").orEmpty()
        )
        checkRoundTrip(settings)
    }

    @Test
    fun sftpTrustsTheFirstKeyAndRefusesAChangedOne() {
        val settings = settings(Protocol.SFTP, "SFTP_PORT")
        val hostKey = SftpTransport(settings).use { transport ->
            transport.connect()
            assertTrue(transport.hostKeyFingerprint!!.startsWith("SHA256:"))
            transport.hostKey
        }
        assertNotNull(hostKey)

        // The trusted key is accepted
        SftpTransport(settings.copy(trustedHostKey = hostKey)).use { it.connect() }

        // Another key of the same type is refused
        val type = hostKey!!.substringBefore(' ')
        val otherKey = otherHostKey(type)
        try {
            SftpTransport(settings.copy(trustedHostKey = otherKey)).use { it.connect() }
            fail("A changed server key was accepted")
        } catch (e: HostKeyChangedException) {
            // expected
        }
    }

    /** A valid public key of [type] that the test server does not have. */
    private fun otherHostKey(type: String): String {
        val keyType = when (type) {
            "ssh-ed25519" -> com.jcraft.jsch.KeyPair.ED25519
            "ssh-rsa", "rsa-sha2-256", "rsa-sha2-512" -> com.jcraft.jsch.KeyPair.RSA
            else -> com.jcraft.jsch.KeyPair.ECDSA
        }
        val keySize = if (keyType == com.jcraft.jsch.KeyPair.RSA) 2048 else 256
        val keyPair = com.jcraft.jsch.KeyPair.genKeyPair(com.jcraft.jsch.JSch(), keyType, keySize)
        val output = java.io.ByteArrayOutputStream()
        keyPair.writePublicKey(output, "")
        return output.toString().trim().split(' ').take(2).joinToString(" ")
    }

    @Test
    fun ftp() {
        val settings = settings(Protocol.FTP, "FTP_PORT")
        checkRoundTrip(settings)
        assertLoginRejected(settings.copy(password = "wrong password"))
    }

    @Test
    fun ftpsWithExplicitTls() {
        checkRoundTrip(settings(Protocol.FTPS, "FTPS_PORT"))
    }

    @Test
    fun ftpsWithImplicitTls() {
        checkRoundTrip(settings(Protocol.FTPS_IMPLICIT, "FTPS_IMPLICIT_PORT"))
    }

    @Test
    fun ftpsRefusesUntrustedCertificatesUnlessAllowed() {
        assertCertificateRefused(
            settings(Protocol.FTPS, "FTPS_PORT").copy(allowUntrustedCertificates = false)
        )
        assertCertificateRefused(
            settings(Protocol.FTPS_IMPLICIT, "FTPS_IMPLICIT_PORT")
                .copy(allowUntrustedCertificates = false)
        )
    }

    @Test
    fun ftpsRefusesServersThatDoNotEncryptFileTransfers() {
        // rclone's server encrypts the commands with implicit TLS, but not the file transfers
        val settings = settings(Protocol.FTPS_IMPLICIT, "FTPS_PLAIN_DATA_PORT")
        try {
            BackupTransport.create(settings).use { it.connect() }
            fail("A server that does not encrypt file transfers was used")
        } catch (e: IOException) {
            assertTrue(e.toString(), e.message.orEmpty().contains("encrypt file transfers"))
        }
    }

    /** Connecting must fail because of the self-signed certificate, not for another reason. */
    private fun assertCertificateRefused(settings: ServerSettings) {
        try {
            BackupTransport.create(settings).use { it.connect() }
            fail("A self-signed certificate was trusted")
        } catch (e: IOException) {
            val causes = generateSequence(e as Throwable) { it.cause }.toList()
            assertTrue(
                "Not a certificate error: $e",
                causes.any { it is javax.net.ssl.SSLException }
            )
        }
    }

    @Test
    fun webDav() {
        val settings = settings(Protocol.WEBDAV_HTTP, "WEBDAV_PORT")
        checkRoundTrip(settings)
        assertLoginRejected(settings.copy(password = "wrong password"))
    }

    @Test
    fun webDavHttps() {
        checkRoundTrip(settings(Protocol.WEBDAV_HTTPS, "WEBDAV_HTTPS_PORT"))
    }

    @Test
    fun webDavHttpsRefusesUntrustedCertificatesUnlessAllowed() {
        assertCertificateRefused(
            settings(Protocol.WEBDAV_HTTPS, "WEBDAV_HTTPS_PORT")
                .copy(allowUntrustedCertificates = false)
        )
    }
}

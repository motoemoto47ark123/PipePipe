package org.schabi.newpipe.backup

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import org.schabi.newpipe.R
import org.schabi.newpipe.backup.transport.AuthenticationException
import org.schabi.newpipe.backup.transport.BackupTransport
import org.schabi.newpipe.backup.transport.HostKeyChangedException
import org.schabi.newpipe.backup.transport.RemoteFile
import org.schabi.newpipe.backup.transport.SftpTransport
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Date
import javax.net.ssl.SSLException

/** Uploads backups, and lists and downloads them for restoring. */
object RemoteBackupRunner {
    private const val TAG = "RemoteBackupRunner"
    private const val WORK_FOLDER = "remote-backup"
    private const val TEST_FILE_NAME = ".pipepipe-connection-test"

    /** Only one backup or connection test runs at a time. */
    private val lock = Any()

    sealed class Outcome {
        data class Uploaded(val name: String, val size: Long) : Outcome()

        /** Nothing changed since the last uploaded backup. */
        data object Unchanged : Outcome()
    }

    data class TestResult(
        /** SFTP only: the fingerprint of the server's key. */
        val hostKeyFingerprint: String?
    )

    /**
     * Uploads a backup and deletes old ones.
     *
     * @param force whether to upload even when nothing changed since the last backup
     */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun backUp(context: Context, force: Boolean): Outcome = synchronized(lock) {
        val config = RemoteBackupConfig.load(context)
        val now = System.currentTimeMillis()
        try {
            if (!config.isComplete) {
                throw IOException(context.getString(R.string.remote_backup_error_no_server))
            }
            val workFolder = File(context.cacheDir, WORK_FOLDER)
            val name = BackupRetention.nameFor(Date(now))
            val archive = File(workFolder, name)
            try {
                val hash = BackupArchiver.createArchive(
                    context, archive, config.includeSettings, workFolder
                )
                if (!force && hash == RemoteBackupStatus.lastHash(context)) {
                    RemoteBackupStatus.recordUnchanged(context, now)
                    return Outcome.Unchanged
                }
                val size = archive.length()
                connect(context, config).use { transport ->
                    transport.upload(archive, name)
                    deleteOldBackups(transport, config, name)
                }
                RemoteBackupStatus.recordSuccess(context, now, name, size, hash)
                return Outcome.Uploaded(name, size)
            } finally {
                archive.delete()
            }
        } catch (e: IOException) {
            RemoteBackupStatus.recordFailure(context, now, describeError(context, e))
            throw e
        } catch (e: RuntimeException) {
            RemoteBackupStatus.recordFailure(context, now, describeError(context, e))
            throw IOException(e.message, e)
        }
    }

    /** Connects with [config], creates the backup folder and checks that files can be written. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun testConnection(context: Context, config: RemoteBackupConfig): TestResult =
        synchronized(lock) {
            if (!config.isComplete) {
                throw IOException(context.getString(R.string.remote_backup_error_no_server))
            }
            val testFile = File(context.cacheDir, TEST_FILE_NAME)
            try {
                testFile.writeText("PipePipe can write backups here.\n")
                connect(context, config).use { transport ->
                    transport.upload(testFile, TEST_FILE_NAME)
                    transport.delete(TEST_FILE_NAME)
                    TestResult((transport as? SftpTransport)?.hostKeyFingerprint)
                }
            } finally {
                testFile.delete()
            }
        }

    /** Lists the backups on the server, newest first. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun listBackups(context: Context): List<RemoteFile> {
        val config = RemoteBackupConfig.load(context)
        connect(context, config).use { transport ->
            val files = transport.list().associateBy { it.name }
            return BackupRetention.newestFirst(files.keys).mapNotNull { files[it] }
        }
    }

    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun download(context: Context, name: String, target: File) {
        val config = RemoteBackupConfig.load(context)
        connect(context, config).use { transport -> transport.download(name, target) }
    }

    @Throws(IOException::class)
    private fun connect(context: Context, config: RemoteBackupConfig): BackupTransport {
        val transport = BackupTransport.create(config.server)
        transport.connect()
        if (transport is SftpTransport && config.server.trustedHostKey.isNullOrBlank()) {
            // Trust on first use: from now on, a different key is refused
            RemoteBackupConfig.saveTrustedHostKey(
                context, transport.hostKey, transport.hostKeyFingerprint
            )
        }
        return transport
    }

    private fun deleteOldBackups(
        transport: BackupTransport,
        config: RemoteBackupConfig,
        uploaded: String
    ) {
        try {
            val names = transport.list().map { it.name }
            val obsolete = BackupRetention.backupsToDelete(
                names, config.keepRecent, config.keepDaily
            ).filter { it != uploaded }
            // Leftovers of interrupted uploads
            val partial = names.filter {
                it.endsWith(BackupTransport.PARTIAL_SUFFIX) &&
                    BackupRetention.isBackupName(it.removeSuffix(BackupTransport.PARTIAL_SUFFIX))
            }
            for (name in obsolete + partial) {
                try {
                    transport.delete(name)
                } catch (e: IOException) {
                    Log.w(TAG, "Could not delete $name", e)
                }
            }
        } catch (e: IOException) {
            // The backup itself was uploaded, old ones are deleted next time
            Log.w(TAG, "Could not delete old backups", e)
        }
    }

    /** A message about [e] that tells the user what to do. */
    @JvmStatic
    fun describeError(context: Context, e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is AuthenticationException ->
                    return context.getString(R.string.remote_backup_error_login)
                is HostKeyChangedException ->
                    return context.getString(R.string.remote_backup_error_host_key_changed)
                is UnknownHostException ->
                    return context.getString(R.string.remote_backup_error_unknown_host)
                is ConnectException, is NoRouteToHostException, is SocketTimeoutException ->
                    return context.getString(
                        R.string.remote_backup_error_unreachable, cause.message.orEmpty()
                    )
                is SSLException ->
                    return context.getString(
                        R.string.remote_backup_error_certificate, cause.message.orEmpty()
                    )
            }
            cause = cause.cause
        }
        return e.message ?: e.javaClass.simpleName
    }
}

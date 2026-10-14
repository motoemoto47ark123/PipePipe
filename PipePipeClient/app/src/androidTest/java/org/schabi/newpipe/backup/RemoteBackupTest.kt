package org.schabi.newpipe.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestWorkerBuilder
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.backup.transport.HostKeyChangedException
import org.schabi.newpipe.backup.transport.Protocol
import org.schabi.newpipe.database.TestDatabases.stream
import org.schabi.newpipe.util.ZipHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Backs up from the emulator to servers on the machine running it, which the emulator reaches at
 * 10.0.2.2. The servers are started by .github/workflows/app-tests.yml and passed as
 * instrumentation arguments; without them the tests are skipped.
 */
@RunWith(AndroidJUnit4::class)
class RemoteBackupTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    private fun argument(name: String): String? =
        arguments.getString(name)?.takeIf { it.isNotBlank() }

    private val folder = "pipepipe-emulator-tests/${UUID.randomUUID()}"

    @Before
    fun setUp() {
        RemoteBackupConfig.preferences(context).edit(commit = true) { clear() }
    }

    @After
    fun tearDown() {
        RemoteBackupConfig.preferences(context).edit(commit = true) { clear() }
        RemoteBackupScheduler.schedule(context, true)
    }

    private fun configure(
        protocol: Protocol,
        portArgument: String,
        password: String? = argument("serverPassword"),
        privateKey: String = "",
        enabled: Boolean = false
    ) {
        val port = argument(portArgument)
        assumeTrue("$portArgument was not passed", port != null)
        RemoteBackupConfig.preferences(context).edit(commit = true) {
            putBoolean(context.getString(R.string.remote_backup_enabled_key), enabled)
            putString(context.getString(R.string.remote_backup_protocol_key), protocol.name)
            putString(
                context.getString(R.string.remote_backup_host_key),
                argument("backupHost") ?: "10.0.2.2"
            )
            putString(context.getString(R.string.remote_backup_port_key), port)
            putString(
                context.getString(R.string.remote_backup_username_key),
                argument("serverUser") ?: "pipetest"
            )
            putString(context.getString(R.string.remote_backup_password_key), password.orEmpty())
            putString(context.getString(R.string.remote_backup_private_key_key), privateKey)
            putString(context.getString(R.string.remote_backup_folder_key), folder)
            putBoolean(context.getString(R.string.remote_backup_allow_untrusted_key), true)
        }
    }

    @Test
    fun backsUpToSftpAndTheBackupCanBeRestored() {
        configure(Protocol.SFTP, "sftpPort")
        val title = "backed up ${UUID.randomUUID()}"
        NewPipeDatabase.getInstance(context).streamDAO().insert(stream(title))

        val uploaded = RemoteBackupRunner.backUp(context, true)
            as RemoteBackupRunner.Outcome.Uploaded
        assertNotNull("The server key was not remembered",
            RemoteBackupConfig.trustedHostKeyFingerprint(context))
        assertNull(RemoteBackupStatus.lastError(context))
        assertEquals(RemoteBackupRunner.Outcome.Unchanged, RemoteBackupRunner.backUp(context, false))

        val backups = RemoteBackupRunner.listBackups(context)
        assertEquals(listOf(uploaded.name), backups.map { it.name })

        val zip = File(context.cacheDir, "restore-test.zip")
        val database = File(context.cacheDir, "restore-test.db")
        try {
            RemoteBackupRunner.download(context, uploaded.name, zip)
            assertTrue(ZipHelper.isValidZipFile(zip))
            assertTrue(ZipHelper.extractFileFromZip(zip, database.path, "newpipe.db"))
            SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.rawQuery("SELECT COUNT(*) FROM streams WHERE title = ?", arrayOf(title))
                    .use { cursor -> assertTrue(cursor.moveToFirst() && cursor.getInt(0) == 1) }
            }
            val settings = File(context.cacheDir, "restore-test.settings")
            assertTrue(ZipHelper.extractFileFromZip(zip, settings.path, "newpipe.settings"))
            settings.delete()
        } finally {
            zip.delete()
            database.delete()
            File(database.path + "-wal").delete()
            File(database.path + "-shm").delete()
        }
    }

    @Test
    fun refusesAChangedSftpServerKey() {
        configure(Protocol.SFTP, "sftpPort")
        RemoteBackupRunner.testConnection(context, RemoteBackupConfig.load(context))
        val trusted = RemoteBackupConfig.load(context).server.trustedHostKey!!

        val type = trusted.substringBefore(' ')
        val keyType = when (type) {
            "ssh-ed25519" -> KeyPair.ED25519
            "ssh-rsa" -> KeyPair.RSA
            else -> KeyPair.ECDSA
        }
        val other = KeyPair.genKeyPair(JSch(), keyType, if (keyType == KeyPair.RSA) 2048 else 256)
        val output = ByteArrayOutputStream()
        other.writePublicKey(output, "")
        val otherKey = output.toString().trim().split(' ').take(2).joinToString(" ")
        RemoteBackupConfig.saveTrustedHostKey(context, otherKey, "SHA256:other")

        try {
            RemoteBackupRunner.testConnection(context, RemoteBackupConfig.load(context))
            fail("A changed server key was accepted")
        } catch (e: HostKeyChangedException) {
            // expected
        }
    }

    @Test
    fun logsInWithAnEd25519Key() {
        val key = argument("sftpKeyEd25519Base64")
        assumeTrue("sftpKeyEd25519Base64 was not passed", key != null)
        configure(
            Protocol.SFTP, "sftpPort", password = "",
            privateKey = String(Base64.decode(key, Base64.DEFAULT))
        )
        RemoteBackupRunner.testConnection(context, RemoteBackupConfig.load(context))
    }

    @Test
    fun backsUpToWebDav() {
        configure(Protocol.WEBDAV_HTTP, "webdavPort")
        val uploaded = RemoteBackupRunner.backUp(context, true)
            as RemoteBackupRunner.Outcome.Uploaded
        assertEquals(listOf(uploaded.name), RemoteBackupRunner.listBackups(context).map { it.name })
    }

    @Test
    fun theWorkerBacksUpWhenEnabled() {
        configure(Protocol.SFTP, "sftpPort", enabled = true)
        val worker = TestWorkerBuilder<RemoteBackupWorker>(
            context, Executors.newSingleThreadExecutor()
        ).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertNull(RemoteBackupStatus.lastError(context))
        assertEquals(1, RemoteBackupRunner.listBackups(context).size)
    }

    @Test
    fun schedulesHourlyBackupsOnlyWhenEnabled() {
        configure(Protocol.SFTP, "sftpPort", enabled = true)
        RemoteBackupScheduler.schedule(context, true)
        val workManager = WorkManager.getInstance(context)
        val scheduled = workManager
            .getWorkInfosForUniqueWork(RemoteBackupScheduler.PERIODIC_WORK_NAME)
            .get(10, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }
        assertEquals(1, scheduled.size)
        assertEquals(
            TimeUnit.HOURS.toMillis(1),
            scheduled[0].periodicityInfo!!.repeatIntervalMillis
        )

        RemoteBackupConfig.preferences(context).edit(commit = true) {
            putBoolean(context.getString(R.string.remote_backup_enabled_key), false)
        }
        RemoteBackupScheduler.schedule(context, true)
        val afterDisabling = workManager
            .getWorkInfosForUniqueWork(RemoteBackupScheduler.PERIODIC_WORK_NAME)
            .get(10, TimeUnit.SECONDS)
        assertTrue(afterDisabling.all { it.state == WorkInfo.State.CANCELLED })
    }
}

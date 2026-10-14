package org.schabi.newpipe.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.backup.BackupArchiver
import org.schabi.newpipe.database.TestDatabases.hasStream
import org.schabi.newpipe.database.TestDatabases.stream
import java.io.File
import java.io.IOException
import java.util.UUID

/** Moves the database between private storage and a folder on the emulator's storage. */
@RunWith(AndroidJUnit4::class)
class DatabaseLocationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var folder: File

    @Before
    fun setUp() {
        TestDatabases.grantStorageAccess(context)
        assertTrue("Storage access was not granted", DatabaseLocation.hasStorageAccess(context))
        folder = File(Environment.getExternalStorageDirectory(), "PipePipeTest-${UUID.randomUUID()}")
        assertTrue("Cannot write to $folder", DatabaseLocation.isWritableFolder(folder))
    }

    @After
    fun tearDown() {
        if (DatabaseLocation.getFolder(context) != null) {
            try {
                DatabaseLocation.moveDatabase(context, null)
            } catch (e: IOException) {
                DatabaseLocation.forgetFolder(context)
            }
        }
        NewPipeDatabase.close()
        DatabaseLocation.resolve(context)
        folder.deleteRecursively()
    }

    @Test
    fun roomWorksInAFolderInRollbackJournalMode() {
        val file = File(folder, "room-test.db")
        val database = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        database.streamDAO().insert(stream("folder test"))
        database.close()
        assertTrue(file.isFile)
        assertFalse(DatabaseLocation.isInWriteAheadLogMode(file))
        assertFalse(File(file.path + "-wal").exists())

        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        assertTrue(hasStream(reopened, "folder test"))
        reopened.close()
    }

    @Test
    fun movesTheDatabaseToAFolderAndBack() {
        NewPipeDatabase.getInstance(context).streamDAO().insert(stream("before moving"))

        DatabaseLocation.moveDatabase(context, folder.path)
        val inFolder = File(folder, AppDatabase.DATABASE_NAME)
        assertEquals(folder.path, DatabaseLocation.getFolder(context))
        assertTrue(inFolder.isFile)
        assertFalse(DatabaseLocation.defaultDatabaseFile(context).exists())

        val moved = NewPipeDatabase.getInstance(context)
        assertEquals(inFolder.path, NewPipeDatabase.getDatabaseFile(context).path)
        assertTrue(hasStream(moved, "before moving"))
        moved.streamDAO().insert(stream("written in the folder"))
        assertFalse(DatabaseLocation.isInWriteAheadLogMode(inFolder))
        assertFalse(File(inFolder.path + "-wal").exists())

        DatabaseLocation.moveDatabase(context, null)
        assertNull(DatabaseLocation.getFolder(context))
        assertFalse(inFolder.exists())
        val back = NewPipeDatabase.getInstance(context)
        assertEquals(
            DatabaseLocation.defaultDatabaseFile(context).path,
            NewPipeDatabase.getDatabaseFile(context).path
        )
        assertTrue(hasStream(back, "before moving"))
        assertTrue(hasStream(back, "written in the folder"))
    }

    /** A database in write-ahead log mode, like a copy from an export, in [target]. */
    private fun createSavedDatabase(target: File, title: String) {
        val staging = File(context.cacheDir, "saved-${UUID.randomUUID()}.db")
        val database = Room.databaseBuilder(context, AppDatabase::class.java, staging.absolutePath)
            .build()
        database.streamDAO().insert(stream(title))
        database.close()
        target.parentFile!!.mkdirs()
        staging.copyTo(target, overwrite = true)
        File(staging.path + "-wal").takeIf { it.exists() }
            ?.copyTo(File(target.path + "-wal"), overwrite = true)
        DatabaseLocation.deleteDatabaseFiles(staging)
    }

    @Test
    fun usesDataThatIsAlreadyInAFolder() {
        val saved = File(folder, AppDatabase.DATABASE_NAME)
        createSavedDatabase(saved, "saved before reinstalling")

        val info = DatabaseLocation.inspectDatabaseIn(context, folder.path)!!
        assertEquals(AppDatabase.DATABASE_VERSION, info.version)

        DatabaseLocation.useDatabaseIn(context, folder.path)
        assertEquals(folder.path, DatabaseLocation.getFolder(context))
        assertFalse(DatabaseLocation.isInWriteAheadLogMode(saved))
        assertTrue(hasStream(NewPipeDatabase.getInstance(context), "saved before reinstalling"))
    }

    @Test
    fun refusesDataFromANewerVersion() {
        val saved = File(folder, AppDatabase.DATABASE_NAME)
        createSavedDatabase(saved, "from the future")
        SQLiteDatabase.openDatabase(saved.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = AppDatabase.DATABASE_VERSION + 1
        }
        try {
            DatabaseLocation.useDatabaseIn(context, folder.path)
            fail("A database from a newer version was used")
        } catch (e: IOException) {
            assertNull(DatabaseLocation.getFolder(context))
        }
    }

    @Test
    fun fallsBackToPrivateStorageAndSkipsBackupsWhenTheFolderIsInaccessible() {
        context.getSharedPreferences("database_location", Context.MODE_PRIVATE).edit()
            .putString("folder", "/proc/pipepipe-not-writable").commit()
        try {
            NewPipeDatabase.close()
            val resolved = DatabaseLocation.resolve(context)
            assertTrue(resolved.isFallback)
            assertEquals(DatabaseLocation.defaultDatabaseFile(context), resolved.file)

            val work = File(context.cacheDir, "fallback-test")
            try {
                BackupArchiver.createArchive(context, File(work, "backup.zip"), false, work)
                fail("A backup was made from the fallback database")
            } catch (e: IOException) {
                // expected: it would push the real backups out
            } finally {
                work.deleteRecursively()
            }
        } finally {
            context.getSharedPreferences("database_location", Context.MODE_PRIVATE).edit()
                .remove("folder").commit()
            NewPipeDatabase.close()
        }
    }
}

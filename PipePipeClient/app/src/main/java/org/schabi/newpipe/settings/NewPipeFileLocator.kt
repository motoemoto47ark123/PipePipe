package org.schabi.newpipe.settings

import java.io.File

/**
 * Locates specific files of NewPipe based on the home directory of the app and the database file,
 * which can be stored outside of the home directory.
 */
class NewPipeFileLocator(private val homeDir: File, val db: File) {

    val dbDir: File by lazy { db.absoluteFile.parentFile ?: File(homeDir, "/databases") }

    val dbJournal by lazy { File(db.path + "-journal") }

    val dbShm by lazy { File(db.path + "-shm") }

    val dbWal by lazy { File(db.path + "-wal") }

    val settings by lazy { File(homeDir, "/databases/newpipe.settings") }
}

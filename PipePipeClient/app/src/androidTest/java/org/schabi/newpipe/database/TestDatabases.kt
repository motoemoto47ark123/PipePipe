package org.schabi.newpipe.database

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.stream.StreamType

/** Helpers for tests that work with the database. */
object TestDatabases {
    fun stream(title: String, type: StreamType = StreamType.VIDEO_STREAM, paid: Boolean = false) =
        StreamEntity(
            serviceId = 0,
            url = "https://www.youtube.com/watch?v=" + title.replace(' ', '_'),
            title = title,
            streamType = type,
            duration = 600,
            uploader = "PipePipe tests",
            isPaid = paid
        )

    fun hasStream(database: AppDatabase, title: String): Boolean =
        database.query("SELECT COUNT(*) FROM streams WHERE title = ?", arrayOf(title))
            .use { it.moveToFirst() && it.getInt(0) == 1 }

    /** Grants the storage access a user grants before choosing a folder on the phone. */
    fun grantStorageAccess(context: Context) {
        val packageName = context.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            shell("appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow")
        } else {
            shell("pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE")
            shell("pm grant $packageName android.permission.READ_EXTERNAL_STORAGE")
        }
    }

    fun shell(command: String): String {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use { String(it.readBytes()) }
    }
}

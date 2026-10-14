package org.schabi.newpipe;

import static org.schabi.newpipe.database.AppDatabase.DATABASE_NAME;
import static org.schabi.newpipe.database.Migrations.*;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.DatabaseLocation;

import java.io.File;
import java.io.IOException;

public final class NewPipeDatabase {
    private static final String TAG = "NewPipeDatabase";
    private static volatile AppDatabase databaseInstance;
    private static volatile File databaseFile;

    private NewPipeDatabase() {
        //no instance
    }

    private static AppDatabase getDatabase(final Context context) {
        final Context appContext = context.getApplicationContext();
        final DatabaseLocation.Resolved location = DatabaseLocation.resolve(appContext);
        // Room opens a name relative to the private databases folder, or an absolute path
        final String name = location.isCustom()
                ? location.getFile().getAbsolutePath() : DATABASE_NAME;
        final RoomDatabase.Builder<AppDatabase> builder = Room
                .databaseBuilder(appContext, AppDatabase.class, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                        MIGRATION_5_6, MIGRATION_7_6, MIGRATION_8_6, MIGRATION_9_6, MIGRATION_6_900, MIGRATION_9_900, MIGRATION_900_901);
        if (location.isCustom()) {
            // Shared storage does not reliably support the write-ahead log's shared memory
            try {
                DatabaseLocation.prepareForSharedStorage(appContext, location.getFile());
            } catch (final IOException e) {
                Log.e(TAG, "Could not convert the database to rollback journal mode", e);
            }
            builder.setJournalMode(RoomDatabase.JournalMode.TRUNCATE);
        }
        databaseFile = location.getFile();
        return builder.build();
    }

    @NonNull
    public static AppDatabase getInstance(@NonNull final Context context) {
        AppDatabase result = databaseInstance;
        if (result == null) {
            synchronized (NewPipeDatabase.class) {
                result = databaseInstance;
                if (result == null) {
                    databaseInstance = getDatabase(context);
                    result = databaseInstance;
                }
            }
        }

        return result;
    }

    /**
     * @return the database file that is open, or that would be opened next
     */
    @NonNull
    public static File getDatabaseFile(@NonNull final Context context) {
        final File file = databaseFile;
        if (databaseInstance != null && file != null) {
            return file;
        }
        return DatabaseLocation.resolve(context).getFile();
    }

    public static void checkpoint() {
        if (databaseInstance == null) {
            throw new IllegalStateException("database is not initialized");
        }
        final Cursor c = databaseInstance.query("pragma wal_checkpoint(full)", null);
        if (c.moveToFirst() && c.getInt(0) == 1) {
            throw new RuntimeException("Checkpoint was blocked from completing");
        }
    }

    public static void close() {
        if (databaseInstance != null) {
            synchronized (NewPipeDatabase.class) {
                if (databaseInstance != null) {
                    databaseInstance.close();
                    databaseInstance = null;
                }
            }
        }
    }
}

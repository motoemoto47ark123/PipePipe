package org.schabi.newpipe.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.DatabaseLocation
import org.schabi.newpipe.util.NavigationHelper
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/**
 * Lets the user choose where the database is stored. It has to be created while the fragment is
 * constructed, because it registers for activity results.
 */
class DatabaseLocationController(private val fragment: Fragment) {
    private val disposables = CompositeDisposable()

    /** What to do once storage access was granted. */
    private var afterStorageAccess: (() -> Unit)? = null

    private val allFilesAccessLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onStorageAccessResult() }

    private val writePermissionLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onStorageAccessResult() }

    private val folderPickerLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(::onFolderPicked) }

    private val context: Context
        get() = fragment.requireContext()

    fun getSummary(): String {
        val folder = DatabaseLocation.getFolder(context)
        return when {
            folder == null -> context.getString(R.string.database_location_private_summary)
            DatabaseLocation.isUsingFallback() ->
                context.getString(R.string.database_location_inaccessible_summary, folder)
            else -> folder
        }
    }

    fun showDialog() {
        val folder = DatabaseLocation.getFolder(context)
        if (folder != null && DatabaseLocation.isUsingFallback()) {
            showInaccessibleDialog(folder)
        } else {
            showChoiceDialog()
        }
    }

    private fun showChoiceDialog() {
        val current = DatabaseLocation.getFolder(context)
        val shared = DatabaseLocation.suggestedSharedFolder()
        val items = arrayOf(
            context.getString(R.string.database_location_private),
            context.getString(R.string.database_location_shared, shared),
            context.getString(R.string.database_location_choose)
        )
        val checked = when (current) {
            null -> 0
            shared -> 1
            else -> 2
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.database_location_title)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> if (current != null) confirmMoveToPrivateStorage()
                    1 -> withStorageAccess { onFolderChosen(shared) }
                    else -> withStorageAccess { launchFolderPicker() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showInaccessibleDialog(folder: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.database_location_title)
            .setMessage(context.getString(R.string.database_location_inaccessible_message, folder))
            .setPositiveButton(R.string.database_location_grant_access) { _, _ ->
                withStorageAccess {
                    if (DatabaseLocation.isWritableFolder(File(folder))) {
                        NavigationHelper.restartApp(fragment.requireActivity())
                    } else {
                        showError(context.getString(R.string.database_location_not_writable, folder))
                    }
                }
            }
            .setNeutralButton(R.string.database_location_change) { _, _ -> showChoiceDialog() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmMoveToPrivateStorage() {
        AlertDialog.Builder(context)
            .setTitle(R.string.database_location_title)
            .setMessage(R.string.database_location_move_to_private_message)
            .setPositiveButton(R.string.ok) { _, _ -> move(null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun withStorageAccess(action: () -> Unit) {
        if (DatabaseLocation.hasStorageAccess(context)) {
            action()
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.database_location_access_title)
            .setMessage(R.string.database_location_access_message)
            .setPositiveButton(R.string.ok) { _, _ ->
                afterStorageAccess = action
                requestStorageAccess()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )
            try {
                allFilesAccessLauncher.launch(appSettings)
            } catch (e: ActivityNotFoundException) {
                allFilesAccessLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            }
        } else {
            writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun onStorageAccessResult() {
        val action = afterStorageAccess
        afterStorageAccess = null
        if (!fragment.isAdded) {
            return
        }
        if (DatabaseLocation.hasStorageAccess(context)) {
            action?.invoke()
        } else {
            Toast.makeText(context, R.string.database_location_access_denied, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun launchFolderPicker() {
        try {
            folderPickerLauncher.launch(null)
        } catch (e: ActivityNotFoundException) {
            showError(context.getString(R.string.database_location_no_picker))
        }
    }

    private fun onFolderPicked(uri: Uri) {
        val path = DatabaseLocation.treeUriToPath(uri)
        if (path == null) {
            showError(context.getString(R.string.database_location_unsupported_folder))
        } else {
            onFolderChosen(path)
        }
    }

    /** What was found in a chosen folder. */
    private class FolderCheck(val existing: DatabaseLocation.DatabaseInfo?)

    private fun onFolderChosen(folder: String) {
        if (folder == DatabaseLocation.getFolder(context) && !DatabaseLocation.isUsingFallback()) {
            Toast.makeText(context, R.string.database_location_unchanged, Toast.LENGTH_SHORT).show()
            return
        }
        val appContext = context.applicationContext
        disposables.add(
            Single.fromCallable {
                if (!DatabaseLocation.isWritableFolder(File(folder))) {
                    throw IOException(
                        appContext.getString(R.string.database_location_not_writable, folder)
                    )
                }
                FolderCheck(DatabaseLocation.inspectDatabaseIn(appContext, folder))
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { check ->
                        val existing = check.existing
                        if (existing == null) {
                            confirmMove(folder)
                        } else {
                            askAboutExistingData(folder, existing)
                        }
                    },
                    { e -> showError(e.message ?: e.toString()) }
                )
        )
    }

    private fun confirmMove(folder: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.database_location_title)
            .setMessage(context.getString(R.string.database_location_move_message, folder))
            .setPositiveButton(R.string.ok) { _, _ -> move(folder) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askAboutExistingData(folder: String, existing: DatabaseLocation.DatabaseInfo) {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(existing.lastModified))
        val size = Formatter.formatShortFileSize(context, existing.size)
        AlertDialog.Builder(context)
            .setTitle(R.string.database_location_existing_title)
            .setMessage(
                context.getString(R.string.database_location_existing_message, folder, date, size)
            )
            .setPositiveButton(R.string.database_location_use_existing) { _, _ ->
                if (existing.version > AppDatabase.DATABASE_VERSION) {
                    showError(context.getString(R.string.database_location_newer_version))
                } else {
                    useExisting(folder)
                }
            }
            .setNeutralButton(R.string.database_location_replace_existing) { _, _ ->
                AlertDialog.Builder(context)
                    .setMessage(
                        context.getString(R.string.database_location_replace_confirm, folder)
                    )
                    .setPositiveButton(R.string.ok) { _, _ -> move(folder) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun move(folder: String?) {
        val appContext = context.applicationContext
        runThenRestart(Completable.fromAction { DatabaseLocation.moveDatabase(appContext, folder) })
    }

    private fun useExisting(folder: String) {
        val appContext = context.applicationContext
        runThenRestart(Completable.fromAction { DatabaseLocation.useDatabaseIn(appContext, folder) })
    }

    private fun runThenRestart(work: Completable) {
        val progress = AlertDialog.Builder(context)
            .setMessage(R.string.database_location_moving)
            .setCancelable(false)
            .show()
        disposables.add(
            work.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    {
                        progress.dismiss()
                        NavigationHelper.restartApp(fragment.requireActivity())
                    },
                    { e ->
                        progress.dismiss()
                        showError(e.message ?: e.toString())
                    }
                )
        )
    }

    private fun showError(message: String) {
        if (!fragment.isAdded) {
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.database_location_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    fun dispose() {
        disposables.clear()
    }
}

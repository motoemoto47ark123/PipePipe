package org.schabi.newpipe.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.backup.RemoteBackupConfig
import org.schabi.newpipe.backup.RemoteBackupRunner
import org.schabi.newpipe.backup.RemoteBackupScheduler
import org.schabi.newpipe.backup.RemoteBackupStatus
import org.schabi.newpipe.backup.transport.Protocol
import org.schabi.newpipe.backup.transport.RemoteFile
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ZipHelper
import java.io.File
import java.io.IOException

/** Settings for automatic backups to the user's own server. */
class RemoteBackupSettingsFragment : BasePreferenceFragment() {
    private val disposables = CompositeDisposable()
    private lateinit var prefs: SharedPreferences

    private val privateKeyPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::loadPrivateKey) }

    /** Settings that, when changed, require a different server key to be trusted. */
    private val serverIdentityKeys by lazy {
        setOf(
            getString(R.string.remote_backup_protocol_key),
            getString(R.string.remote_backup_host_key),
            getString(R.string.remote_backup_port_key)
        )
    }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || !isAdded) {
            return@OnSharedPreferenceChangeListener
        }
        // Only settings shown on this screen, not the status of backups, change the schedule
        if (findPreference<Preference>(key) != null) {
            if (key in serverIdentityKeys) {
                RemoteBackupConfig.saveTrustedHostKey(requireContext(), null, null)
            }
            if (key != getString(R.string.remote_backup_enabled_key)) {
                // Back up again even when nothing changed, for example to a new server
                RemoteBackupStatus.forgetLastUpload(requireContext())
            }
            RemoteBackupScheduler.schedule(requireContext(), true)
        }
        updateSummaries()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Kept apart from the other settings, see RemoteBackupConfig
        preferenceManager.sharedPreferencesName = RemoteBackupConfig.PREFERENCES_NAME
        prefs = RemoteBackupConfig.preferences(requireContext())
        addPreferencesFromResourceRegistry()

        setInputType(R.string.remote_backup_host_key, InputType.TYPE_TEXT_VARIATION_URI)
        setInputType(R.string.remote_backup_port_key, InputType.TYPE_CLASS_NUMBER)
        setInputType(R.string.remote_backup_username_key, InputType.TYPE_CLASS_TEXT)
        setInputType(
            R.string.remote_backup_password_key,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        setInputType(
            R.string.remote_backup_key_passphrase_key,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        setInputType(R.string.remote_backup_keep_recent_key, InputType.TYPE_CLASS_NUMBER)
        setInputType(R.string.remote_backup_keep_daily_key, InputType.TYPE_CLASS_NUMBER)

        onClick(R.string.remote_backup_private_key_key) { showPrivateKeyDialog() }
        onClick(R.string.remote_backup_server_key_key) { showServerKeyDialog() }
        onClick(R.string.remote_backup_test_key) { testConnection() }
        onClick(R.string.remote_backup_now_key) { backUpNow() }
        onClick(R.string.remote_backup_restore_key) { listBackupsForRestore() }

        updateSummaries()
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(listener)
        updateSummaries()
    }

    override fun onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        super.onPause()
    }

    override fun onDestroy() {
        disposables.clear()
        super.onDestroy()
    }

    private fun setInputType(keyRes: Int, inputType: Int) {
        findPreference<EditTextPreference>(getString(keyRes))?.setOnBindEditTextListener {
            it.inputType = if (inputType and InputType.TYPE_MASK_CLASS == 0) {
                InputType.TYPE_CLASS_TEXT or inputType
            } else {
                inputType
            }
            it.setSelection(it.text.length)
        }
    }

    private fun onClick(keyRes: Int, action: () -> Unit) {
        requirePreference(keyRes).setOnPreferenceClickListener {
            action()
            true
        }
    }

    private fun updateSummaries() {
        if (!isAdded) {
            return
        }
        val context = requireContext()
        val config = RemoteBackupConfig.load(context)
        val protocol = config.server.protocol
        val notSet = getString(R.string.remote_backup_not_set)

        requirePreference(R.string.remote_backup_status_key).summary =
            RemoteBackupStatus.describe(context)
        requirePreference(R.string.remote_backup_host_key).summary =
            config.server.host.ifEmpty { notSet }
        requirePreference(R.string.remote_backup_port_key).summary =
            config.server.effectivePort.toString()
        requirePreference(R.string.remote_backup_username_key).summary =
            config.server.username.ifEmpty { notSet }
        requirePreference(R.string.remote_backup_password_key).summary = getString(
            if (config.server.password.isEmpty()) R.string.remote_backup_not_set
            else R.string.remote_backup_saved
        )
        requirePreference(R.string.remote_backup_private_key_key).summary = getString(
            if (config.server.privateKey.isBlank()) R.string.remote_backup_private_key_none
            else R.string.remote_backup_saved
        )
        requirePreference(R.string.remote_backup_key_passphrase_key).summary = getString(
            if (config.server.privateKeyPassphrase.isEmpty()) R.string.remote_backup_not_set
            else R.string.remote_backup_saved
        )
        requirePreference(R.string.remote_backup_server_key_key).summary =
            RemoteBackupConfig.trustedHostKeyFingerprint(context)
                ?: getString(R.string.remote_backup_server_key_none)

        val sftp = protocol == Protocol.SFTP
        requirePreference(R.string.remote_backup_private_key_key).isVisible = sftp
        requirePreference(R.string.remote_backup_key_passphrase_key).isVisible = sftp
        requirePreference(R.string.remote_backup_server_key_key).isVisible = sftp
        requirePreference(R.string.remote_backup_allow_untrusted_key).isVisible = protocol.usesTls
    }

    private fun showPrivateKeyDialog() {
        val items = arrayOf(
            getString(R.string.remote_backup_private_key_load),
            getString(R.string.remote_backup_private_key_paste),
            getString(R.string.remote_backup_private_key_remove)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remote_backup_private_key_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> try {
                        privateKeyPicker.launch(arrayOf("*/*"))
                    } catch (e: android.content.ActivityNotFoundException) {
                        showMessage(getString(R.string.remote_backup_private_key_title), e.toString())
                    }
                    1 -> showPastePrivateKeyDialog()
                    else -> savePrivateKey("")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPastePrivateKeyDialog() {
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.minLines = 4
        input.hint = "-----BEGIN OPENSSH PRIVATE KEY-----"
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remote_backup_private_key_paste)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ -> savePrivateKey(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadPrivateKey(uri: Uri) {
        val text = try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_PRIVATE_KEY_SIZE) {
                    throw IOException(getString(R.string.remote_backup_private_key_invalid))
                }
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            showMessage(getString(R.string.remote_backup_private_key_title), e.message.orEmpty())
            return
        } catch (e: SecurityException) {
            showMessage(getString(R.string.remote_backup_private_key_title), e.message.orEmpty())
            return
        }
        savePrivateKey(text.orEmpty())
    }

    private fun savePrivateKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isNotEmpty() && !trimmed.contains("PRIVATE KEY") &&
            !trimmed.startsWith("PuTTY-User-Key-File")
        ) {
            showMessage(
                getString(R.string.remote_backup_private_key_title),
                getString(R.string.remote_backup_private_key_invalid)
            )
            return
        }
        prefs.edit {
            putString(getString(R.string.remote_backup_private_key_key), trimmed)
        }
        updateSummaries()
    }

    private fun showServerKeyDialog() {
        val fingerprint = RemoteBackupConfig.trustedHostKeyFingerprint(requireContext())
        if (fingerprint == null) {
            showMessage(
                getString(R.string.remote_backup_server_key_title),
                getString(R.string.remote_backup_server_key_none)
            )
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remote_backup_server_key_title)
            .setMessage(getString(R.string.remote_backup_server_key_message, fingerprint))
            .setPositiveButton(R.string.remote_backup_server_key_forget) { _, _ ->
                RemoteBackupConfig.saveTrustedHostKey(requireContext(), null, null)
                updateSummaries()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun testConnection() {
        val context = requireContext().applicationContext
        val config = RemoteBackupConfig.load(context)
        runWithProgress(
            getString(R.string.remote_backup_connecting),
            Single.fromCallable { RemoteBackupRunner.testConnection(context, config) }
        ) { result ->
            val message = StringBuilder(
                getString(R.string.remote_backup_test_success, config.server.host)
            )
            result.hostKeyFingerprint?.let {
                message.append("\n\n").append(getString(R.string.remote_backup_test_fingerprint, it))
            }
            showMessage(getString(R.string.remote_backup_test_title), message.toString())
        }
    }

    private fun backUpNow() {
        val context = requireContext().applicationContext
        runWithProgress(
            getString(R.string.remote_backup_uploading),
            Single.fromCallable { RemoteBackupRunner.backUp(context, true) }
        ) { outcome ->
            val message = when (outcome) {
                is RemoteBackupRunner.Outcome.Uploaded -> getString(
                    R.string.remote_backup_uploaded,
                    outcome.name,
                    Formatter.formatShortFileSize(context, outcome.size)
                )
                RemoteBackupRunner.Outcome.Unchanged -> getString(R.string.remote_backup_unchanged)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun listBackupsForRestore() {
        val context = requireContext().applicationContext
        runWithProgress(
            getString(R.string.remote_backup_connecting),
            Single.fromCallable { RemoteBackupRunner.listBackups(context) }
        ) { backups ->
            if (backups.isEmpty()) {
                showMessage(
                    getString(R.string.remote_backup_restore_title),
                    getString(R.string.remote_backup_restore_none)
                )
            } else {
                chooseBackupToRestore(backups)
            }
        }
    }

    private fun chooseBackupToRestore(backups: List<RemoteFile>) {
        val names = backups.map { file ->
            if (file.size >= 0) {
                "${file.name} (${Formatter.formatShortFileSize(requireContext(), file.size)})"
            } else {
                file.name
            }
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.remote_backup_restore_title)
            .setItems(names) { _, which ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.remote_backup_restore_title)
                    .setMessage(R.string.override_current_data)
                    .setPositiveButton(R.string.ok) { _, _ -> restore(backups[which].name) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restore(name: String) {
        val context = requireContext().applicationContext
        val target = File(context.cacheDir, "remote-restore.zip")
        runWithProgress(
            getString(R.string.remote_backup_downloading),
            Single.fromCallable {
                RemoteBackupRunner.download(context, name, target)
                if (!ZipHelper.isValidZipFile(target)) {
                    throw IOException(getString(R.string.no_valid_zip_file))
                }
                target
            }
        ) { importBackup(it) }
    }

    /** Replaces the database with the backup, like "Import database" does. */
    private fun importBackup(zip: File) {
        val context = requireContext()
        val homeDir = ContextCompat.getDataDir(context) ?: return
        val manager = ContentSettingsManager(
            NewPipeFileLocator(homeDir, NewPipeDatabase.getDatabaseFile(context))
        )
        try {
            NewPipeDatabase.close()
            if (!manager.ensureDbDirectoryExists() || !manager.extractDb(zip)) {
                showMessage(
                    getString(R.string.remote_backup_restore_title),
                    getString(R.string.could_not_import_all_files)
                )
                return
            }
        } catch (e: Exception) {
            showMessage(getString(R.string.remote_backup_restore_title), e.message ?: e.toString())
            return
        }

        if (manager.extractSettings(zip)) {
            AlertDialog.Builder(context)
                .setTitle(R.string.import_settings)
                .setNegativeButton(R.string.cancel) { _, _ -> finishImport(zip, manager) }
                .setPositiveButton(R.string.ok) { _, _ ->
                    manager.loadSharedPreferences(
                        PreferenceManager.getDefaultSharedPreferences(context)
                    )
                    finishImport(zip, manager)
                }
                .setCancelable(false)
                .show()
        } else {
            finishImport(zip, manager)
        }
    }

    private fun finishImport(zip: File, manager: ContentSettingsManager) {
        zip.delete()
        manager.deleteSettingsFile()
        NavigationHelper.restartApp(requireActivity())
    }

    private fun <T : Any> runWithProgress(message: String, work: Single<T>, onSuccess: (T) -> Unit) {
        val progress = AlertDialog.Builder(requireContext())
            .setMessage(message)
            .setCancelable(false)
            .show()
        disposables.add(
            work.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { result ->
                        progress.dismiss()
                        if (isAdded) {
                            updateSummaries()
                            onSuccess(result)
                        }
                    },
                    { e ->
                        progress.dismiss()
                        if (isAdded) {
                            updateSummaries()
                            showMessage(
                                getString(R.string.remote_backup_error_title),
                                RemoteBackupRunner.describeError(requireContext(), e)
                            )
                        }
                    }
                )
        )
    }

    private fun showMessage(title: String, message: String) {
        val ctx: Context = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    companion object {
        private const val MAX_PRIVATE_KEY_SIZE = 64 * 1024
    }
}

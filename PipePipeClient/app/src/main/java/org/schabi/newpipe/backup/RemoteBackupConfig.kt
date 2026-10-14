package org.schabi.newpipe.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.schabi.newpipe.R
import org.schabi.newpipe.backup.transport.Protocol
import org.schabi.newpipe.backup.transport.ServerSettings

/**
 * The settings for automatic backups to a server.
 *
 * They are kept in their own preferences file, so that the password is neither part of exported
 * settings nor of the backups themselves, and so that importing settings does not change them.
 */
data class RemoteBackupConfig(
    val enabled: Boolean,
    val server: ServerSettings,
    val intervalHours: Int,
    val wifiOnly: Boolean,
    val includeSettings: Boolean,
    val keepRecent: Int,
    val keepDaily: Int
) {
    /** Whether enough is filled in to try connecting. */
    val isComplete: Boolean
        get() = server.host.isNotBlank()

    companion object {
        const val PREFERENCES_NAME = "remote_backup"
        const val DEFAULT_INTERVAL_HOURS = 1
        const val DEFAULT_KEEP_RECENT = 24
        const val DEFAULT_KEEP_DAILY = 30
        const val DEFAULT_FOLDER = "PipePipe"

        private const val KEY_TRUSTED_HOST_KEY = "trusted_host_key"
        private const val KEY_TRUSTED_HOST_KEY_FINGERPRINT = "trusted_host_key_fingerprint"

        @JvmStatic
        fun preferences(context: Context): SharedPreferences = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        @JvmStatic
        fun load(context: Context): RemoteBackupConfig {
            val prefs = preferences(context)
            fun string(key: Int, default: String = "") =
                prefs.getString(context.getString(key), null)?.trim() ?: default
            fun number(key: Int, default: Int) = string(key).toIntOrNull() ?: default

            val address = HostAddress.parse(string(R.string.remote_backup_host_key))
            val folder = string(R.string.remote_backup_folder_key, DEFAULT_FOLDER)
            val port = number(R.string.remote_backup_port_key, 0)
            val server = ServerSettings(
                protocol = Protocol.fromName(string(R.string.remote_backup_protocol_key)),
                host = address.host,
                port = if (port > 0) port else address.port,
                username = prefs.getString(
                    context.getString(R.string.remote_backup_username_key), null
                )?.trim().orEmpty(),
                // Passwords may start or end with spaces
                password = prefs.getString(
                    context.getString(R.string.remote_backup_password_key), null
                ).orEmpty(),
                privateKey = prefs.getString(
                    context.getString(R.string.remote_backup_private_key_key), null
                ).orEmpty(),
                privateKeyPassphrase = prefs.getString(
                    context.getString(R.string.remote_backup_key_passphrase_key), null
                ).orEmpty(),
                folder = address.combineWithFolder(folder),
                trustedHostKey = prefs.getString(KEY_TRUSTED_HOST_KEY, null),
                allowUntrustedCertificates = prefs.getBoolean(
                    context.getString(R.string.remote_backup_allow_untrusted_key), false
                )
            )
            return RemoteBackupConfig(
                enabled = prefs.getBoolean(
                    context.getString(R.string.remote_backup_enabled_key), false
                ),
                server = server,
                intervalHours = number(R.string.remote_backup_interval_key, DEFAULT_INTERVAL_HOURS)
                    .coerceIn(1, 24 * 7),
                wifiOnly = prefs.getBoolean(
                    context.getString(R.string.remote_backup_wifi_only_key), false
                ),
                includeSettings = prefs.getBoolean(
                    context.getString(R.string.remote_backup_include_settings_key), true
                ),
                keepRecent = number(R.string.remote_backup_keep_recent_key, DEFAULT_KEEP_RECENT)
                    .coerceAtLeast(1),
                keepDaily = number(R.string.remote_backup_keep_daily_key, DEFAULT_KEEP_DAILY)
                    .coerceAtLeast(0)
            )
        }

        /** Remembers the SFTP server's key, so that a different key is refused from now on. */
        @JvmStatic
        fun saveTrustedHostKey(context: Context, hostKey: String?, fingerprint: String?) {
            preferences(context).edit {
                if (hostKey == null) {
                    remove(KEY_TRUSTED_HOST_KEY)
                    remove(KEY_TRUSTED_HOST_KEY_FINGERPRINT)
                } else {
                    putString(KEY_TRUSTED_HOST_KEY, hostKey)
                    putString(KEY_TRUSTED_HOST_KEY_FINGERPRINT, fingerprint)
                }
            }
        }

        @JvmStatic
        fun trustedHostKeyFingerprint(context: Context): String? =
            preferences(context).getString(KEY_TRUSTED_HOST_KEY_FINGERPRINT, null)
    }
}

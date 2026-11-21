package top.nkbe.npatch.config

import android.content.SharedPreferences
import androidx.compose.runtime.snapshots.Snapshot
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.ui.util.delegateStateOf
import top.nkbe.npatch.ui.util.getValue
import top.nkbe.npatch.ui.util.setValue
import java.io.File

object Configs {

    internal const val KEYSTORE_PRESET = "keystore_preset"
    internal const val KEYSTORE_PASSWORD = "keystore_password"
    internal const val KEYSTORE_ALIAS = "keystore_alias"
    internal const val KEYSTORE_ALIAS_PASSWORD = "keystore_alias_password"
    internal const val STORAGE_DIRECTORY = "storage_directory"
    internal const val LANGUAGE = "language"

    private val backupKeys = linkedMapOf<String, Class<*>>()
    private val backupReaders = linkedMapOf<String, () -> Any?>()
    private val reloadActions = mutableListOf<() -> Unit>()

    internal val backupPreferenceTypes: Map<String, Class<*>> get() = backupKeys

    private fun <T> preference(
        key: String,
        type: Class<*>,
        backup: Boolean = true,
        read: (SharedPreferences) -> T,
        write: SharedPreferences.Editor.(T) -> Unit,
    ) = delegateStateOf(read(lspApp.prefs)) {
        lspApp.prefs.edit().apply { write(it) }.apply()
    }.also { state ->
        if (backup) {
            backupKeys[key] = type
            backupReaders[key] = { read(lspApp.prefs) }
            reloadActions += { state.updateFromSource(read(lspApp.prefs)) }
        }
    }

    private fun string(key: String, default: String) =
        preference(key, String::class.java, read = { it.getString(key, default) ?: default }) {
            putString(key, it)
        }

    private fun boolean(key: String, default: Boolean, backup: Boolean = true) =
        preference(key, Boolean::class.javaObjectType, backup, { it.getBoolean(key, default) }) {
            putBoolean(key, it)
        }

    private fun defaultKeyStorePreset(): KeystorePreset {
        return if (File(lspApp.filesDir, "keystore.bks").exists()) {
            KeystorePreset.CUSTOM
        } else {
            KeystorePreset.NPATCH
        }
    }

    var keyStorePreset by preference(KEYSTORE_PRESET, String::class.java, read = {
        KeystorePreset.fromPrefValue(it.getString(KEYSTORE_PRESET, null), defaultKeyStorePreset())
    }) { putString(KEYSTORE_PRESET, it.prefValue) }

    var language by string(LANGUAGE, "")
    var keyStorePassword by string(KEYSTORE_PASSWORD, "123456")
    var keyStoreAlias by string(KEYSTORE_ALIAS, "key0")
    var keyStoreAliasPassword by string(KEYSTORE_ALIAS_PASSWORD, "123456")

    var storageDirectory by preference(STORAGE_DIRECTORY, String::class.java, read = {
        it.getString(STORAGE_DIRECTORY, null)
    }) { putString(STORAGE_DIRECTORY, it) }

    var detailPatchLogs by boolean("detail_patch_logs", true)
    var welcomeSeen by boolean("WEL_SKIP", false, backup = false)
    var outputFullLog by boolean("output_full_log", false)
    var installNotificationEnabled by boolean("install_notification_enabled", false)
    var installAllUsers by boolean("install_all_users", false)
    var thirdPartyInstallerPackage by string("third_party_installer_package", "")
    var keepAlive by boolean("keep_alive", false)

    internal fun backupPreferenceValues(): Map<String, Any?> = backupReaders.mapValues { it.value() }

    internal fun reloadBackupPreferences() {
        Snapshot.withMutableSnapshot { reloadActions.forEach { it() } }
    }
}

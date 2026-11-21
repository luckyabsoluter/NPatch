package top.nkbe.npatch.config

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nkbe.util.ModuleMetadataReader
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.nkbe.npatch.LSPApplication
import top.nkbe.npatch.config.ConfigManager.ScopedModule
import top.nkbe.npatch.database.entity.LoadedModule
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.manager.ModuleActivationController
import top.nkbe.npatch.manager.ModuleScopeSyncStore
import top.nkbe.npatch.network.DnsProvider
import top.nkbe.npatch.network.NetworkDns
import top.nkbe.npatch.service.KeepAliveService
import top.nkbe.npatch.ui.util.BackgroundImageStorage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey

internal object SettingsBackup {
    enum class FolderStatus { RESTORED, KEPT, RESELECT }
    
    data class Preview(
        val preferences: JsonObject,
        val theme: JsonObject,
        val scopes: Map<String, List<String>>,
        val assets: Map<String, ByteArray>,
        val skippedModules: Int,
        val folderStatus: FolderStatus,
    )
    
    data class RestoreResult(val skippedModules: Int, val folderStatus: FolderStatus)
    
    private val operations = Mutex()
    private val packageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private val themeTypes = mapOf(
        ThemeConfig.USE_MONET.name to Boolean::class.javaObjectType,
        ThemeConfig.CUSTOM_COLOR.name to Int::class.javaObjectType,
        ThemeConfig.THEME_MODE.name to Int::class.javaObjectType,
        ThemeConfig.USE_FLOATING_GLASS_BOTTOM_BAR.name to Boolean::class.javaObjectType,
        ThemeConfig.USE_FLOATING_GLASS_BOTTOM_BAR_BLUR.name to Boolean::class.javaObjectType,
        ThemeConfig.CARD_BACKGROUND_ALPHA_PERCENT.name to Int::class.javaObjectType,
    )
    private val credentials = setOf(Configs.KEYSTORE_PASSWORD, Configs.KEYSTORE_ALIAS, Configs.KEYSTORE_ALIAS_PASSWORD)
    private val preferenceTypes
        get() = Configs.backupPreferenceTypes + mapOf(
            NetworkDns.PREF_PROVIDER to String::class.java,
            NetworkDns.PREF_CUSTOM_URL to String::class.java,
        )

    suspend fun export(context: Context, uri: Uri, compression: BackupCompression, password: CharArray?) =
        withContext(Dispatchers.IO) {
            operations.withLock {
                val preferences = encodeValues(
                    Configs.backupPreferenceValues() + mapOf(
                        NetworkDns.PREF_PROVIDER to NetworkDns.selectedProvider().preferenceValue,
                        NetworkDns.PREF_CUSTOM_URL to NetworkDns.customUrl(),
                    ),
                    preferenceTypes.keys,
                )
                val theme = ThemeConfig.getThemeFlow(context).first()
                val assets = linkedMapOf<String, ByteArray>()
                if (theme.backgroundImageUri.isNotEmpty()) {
                    assets[SettingsBackupArchive.BACKGROUND] = openBackground(context, theme.backgroundImageUri)
                        .use { it.readBounded(SettingsBackupArchive.MAX_BYTES) }
                }
                val preset = Configs.keyStorePreset
                preferences.addProperty(Configs.KEYSTORE_PRESET, preset.prefValue)
                if (preset == KeystorePreset.CUSTOM) {
                    preferences.addProperty(Configs.KEYSTORE_PASSWORD, Configs.keyStorePassword)
                    preferences.addProperty(Configs.KEYSTORE_ALIAS, Configs.keyStoreAlias)
                    preferences.addProperty(Configs.KEYSTORE_ALIAS_PASSWORD, Configs.keyStoreAliasPassword)
                    val remaining = SettingsBackupArchive.MAX_BYTES -
                        (assets[SettingsBackupArchive.BACKGROUND]?.size ?: 0)
                    assets[SettingsBackupArchive.KEYSTORE] = MyKeyStore.file.inputStream().use {
                        it.readBounded(remaining)
                    }
                    validateSigning(preferences, assets)
                } else {
                    credentials.forEach(preferences::remove)
                }
                validateDns(preferences)
                val scopes = ConfigManager.scopeSnapshot().associate { it.module.pkgName to it.apps }
                val settings = JsonObject().apply {
                    add("preferences", preferences)
                    add("theme", encodeTheme(theme))
                    add("scopes", encodeScopes(scopes))
                }
                withTemporaryZip(context) { archive ->
                    SettingsBackupArchive.write(archive, settings, assets, compression, password)
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Cannot open backup destination")
                    output.use { out -> archive.inputStream().use { it.copyTo(out) } }
                }
            }
        }

    suspend fun inspect(context: Context, uri: Uri, password: CharArray?): Preview = withContext(Dispatchers.IO) {
        operations.withLock {
            val contents = withTemporaryZip(context) { archive ->
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open backup")
                input.use { source ->
                    archive.outputStream().use { source.copyBounded(it, SettingsBackupArchive.MAX_BYTES) }
                }
                SettingsBackupArchive.read(archive, password)
            }
            val preferences = contents.settings.obj("preferences")
            validateTypes(preferences, preferenceTypes)
            if (preferences.has(Configs.LANGUAGE)) {
                preferences.addProperty(
                    Configs.LANGUAGE,
                    LSPApplication.normalizeLanguageTag(preferences.string(Configs.LANGUAGE)),
                )
            }
            validateDns(preferences)
            validateSigning(preferences, contents.assets)
            val theme = contents.settings.obj("theme")
            validateTypes(theme, themeTypes)
            if (theme.has(ThemeConfig.THEME_MODE.name) &&
                ThemeMode.entries.none { it.value == theme.integer(ThemeConfig.THEME_MODE.name) }
            ) throw BackupFormatException()
            if (theme.has(ThemeConfig.CARD_BACKGROUND_ALPHA_PERCENT.name) &&
                theme.integer(ThemeConfig.CARD_BACKGROUND_ALPHA_PERCENT.name) !in
                CARD_BACKGROUND_ALPHA_MIN..CARD_BACKGROUND_ALPHA_MAX
            ) throw BackupFormatException()
            val scopes = decodeScopes(contents.settings.obj("scopes"))
            Preview(
                preferences, theme, scopes, contents.assets,
                skippedModules = scopes.size - resolveModules(context, scopes).size,
                folderStatus = chooseFolder(context, preferences).second,
            )
        }
    }
    
    suspend fun restore(context: Context, preview: Preview): RestoreResult =
        withContext(Dispatchers.IO + NonCancellable) {
            operations.withLock {
                val result = ConfigManager.withScopeWrites {
                    val journal = journalDirectory(context)
                    if (journal.resolve(JOURNAL).exists()) recover(context, journal)
                    val modules = resolveModules(context, preview.scopes)
                    val (folder, folderStatus) = chooseFolder(context, preview.preferences)
                    val preferences = preview.preferences.deepCopy().apply {
                        if (folder == null) remove(Configs.STORAGE_DIRECTORY)
                        else addProperty(Configs.STORAGE_DIRECTORY, folder)
                    }
                    val theme = preview.theme.deepCopy().apply {
                        if (SettingsBackupArchive.BACKGROUND in preview.assets) {
                            addProperty(ThemeConfig.BG_IMAGE_URI.name, BackgroundImageStorage.file(context).absolutePath)
                        }
                    }
                    val oldScopes = ConfigManager.scopeSnapshot()
                    val affected = (oldScopes + modules).map { it.module.pkgName }.toSet()
                    prepareJournal(context, journal, oldScopes, affected)
                    try {
                        for ((name, file) in localAssets(context)) {
                            val bytes = preview.assets[name]
                            if (bytes == null) deleteFile(file)
                            else writeAtomically(file) { it.write(bytes) }
                        }
                        replacePreferences(preferences)
                        ConfigManager.replaceScopes(modules)
                        replaceTheme(context, theme)
                        updateScopeSnapshots(affected, modules)
                        deleteFile(journal.resolve(JOURNAL))
                    } catch (exception: Exception) {
                        try {
                            recover(context, journal)
                        } catch (rollbackFailure: Exception) {
                            exception.addSuppressed(rollbackFailure)
                        }
                        throw exception
                    } finally {
                        if (!journal.resolve(JOURNAL).exists()) journal.deleteRecursively()
                        Configs.reloadBackupPreferences()
                    }
                    ModuleActivationController.pushToCompanionsAsync(affected)
                    RestoreResult(preview.scopes.size - modules.size, folderStatus)
                }
                ConfigManager.notifyScopesRestored()
                withContext(Dispatchers.Main.immediate) {
                    if (Configs.keepAlive) KeepAliveService.start(context)
                    else KeepAliveService.stop(context)
                }
                result
            }
        }

    fun hasRestoreFiles(context: Context): Boolean = journalDirectory(context).isDirectory
    
    suspend fun recoverInterruptedRestore(context: Context) {
        val journal = journalDirectory(context)
        if (journal.resolve(JOURNAL).exists()) {
            ConfigManager.withScopeWrites { recover(context, journal) }
        }
        journal.deleteRecursively()
    }
    
    private fun readPreferences(): JsonObject = encodeValues(lspApp.prefs.all, preferenceTypes.keys)
    
    private suspend fun readTheme(context: Context): JsonObject = encodeValues(
        context.dataStore.data.first().asMap().mapKeys { it.key.name },
        themeTypes.keys + ThemeConfig.BG_IMAGE_URI.name,
    )
    
    private fun encodeTheme(theme: ThemeSettings) = JsonObject().apply {
        addProperty(ThemeConfig.USE_MONET.name, theme.useMonet)
        addProperty(ThemeConfig.CUSTOM_COLOR.name, theme.customColor)
        addProperty(ThemeConfig.THEME_MODE.name, theme.themeMode.value)
        addProperty(ThemeConfig.USE_FLOATING_GLASS_BOTTOM_BAR.name, theme.useFloatingGlassBottomBar)
        addProperty(ThemeConfig.USE_FLOATING_GLASS_BOTTOM_BAR_BLUR.name, theme.useFloatingGlassBottomBarBlur)
        addProperty(ThemeConfig.CARD_BACKGROUND_ALPHA_PERCENT.name, theme.cardBackgroundAlphaPercent)
    }
    
    private fun encodeValues(values: Map<String, Any?>, keys: Set<String>) = JsonObject().apply {
        for (key in keys) {
            when (val value = values[key]) {
                is Boolean -> addProperty(key, value)
                is Int -> addProperty(key, value)
                is String -> addProperty(key, value)
            }
        }
    }
    
    private fun validateTypes(document: JsonObject, types: Map<String, Class<*>>) {
        for ((key, type) in types) {
            if (!document.has(key)) continue
            when (type) {
                String::class.java -> document.string(key)
                Int::class.javaObjectType -> document.integer(key)
                Boolean::class.javaObjectType -> if (document[key]?.takeIf { it.isJsonPrimitive }
                        ?.asJsonPrimitive?.isBoolean != true
                ) {
                    throw BackupFormatException()
                }
            }
        }
    }
    
    private fun replacePreferences(document: JsonObject) {
        val editor = lspApp.prefs.edit()
        for ((key, type) in preferenceTypes) {
            if (!document.has(key)) {
                editor.remove(key)
            } else if (type == Boolean::class.javaObjectType) {
                editor.putBoolean(key, document[key].asBoolean)
            } else {
                editor.putString(key, document.string(key))
            }
        }
        if (!editor.commit()) throw IOException("Cannot save restored preferences")
    }
    
    private suspend fun replaceTheme(context: Context, document: JsonObject) {
        context.dataStore.edit { prefs ->
            for ((key, type) in themeTypes + (ThemeConfig.BG_IMAGE_URI.name to String::class.java)) {
                val value = document[key]
                when (type) {
                    Boolean::class.javaObjectType -> {
                        val preference = booleanPreferencesKey(key)
                        if (value == null) prefs.remove(preference) else prefs[preference] = value.asBoolean
                    }
                    Int::class.javaObjectType -> {
                        val preference = intPreferencesKey(key)
                        if (value == null) prefs.remove(preference) else prefs[preference] = value.asInt
                    }
                    else -> {
                        val preference = stringPreferencesKey(key)
                        if (value == null) prefs.remove(preference) else prefs[preference] = value.asString
                    }
                }
            }
        }
    }
    
    private fun validateDns(preferences: JsonObject) {
        val provider = preferences[NetworkDns.PREF_PROVIDER]?.asString ?: return
        if (DnsProvider.entries.none { it.preferenceValue == provider }) throw BackupFormatException()
        if (provider == DnsProvider.CUSTOM.preferenceValue) {
            val url = preferences.string(NetworkDns.PREF_CUSTOM_URL).toHttpUrlOrNull()
            if (url == null || !url.isHttps) throw BackupFormatException()
        }
    }
    
    private fun validateSigning(preferences: JsonObject, assets: Map<String, ByteArray>) {
        val preset = preferences.string(Configs.KEYSTORE_PRESET)
        if (KeystorePreset.entries.none { it.prefValue == preset }) throw BackupFormatException()
        val file = assets[SettingsBackupArchive.KEYSTORE]
        if (preset != KeystorePreset.CUSTOM.prefValue) {
            if (file != null || credentials.any(preferences::has)) throw BackupFormatException()
            return
        }
        if (file == null) throw BackupFormatException()
        val storePassword = preferences.string(Configs.KEYSTORE_PASSWORD).toCharArray()
        val keyPassword = preferences.string(Configs.KEYSTORE_ALIAS_PASSWORD).toCharArray()
        val alias = preferences.string(Configs.KEYSTORE_ALIAS)
        try {
            val store = KeyStore.getInstance("BKS")
            file.inputStream().use { store.load(it, storePassword) }
            if (store.getKey(alias, keyPassword) !is PrivateKey || store.getCertificate(alias) == null) {
                throw BackupFormatException()
            }
        } catch (exception: GeneralSecurityException) {
            throw BackupFormatException().apply { initCause(exception) }
        } catch (exception: IOException) {
            throw BackupFormatException().apply { initCause(exception) }
        } finally {
            storePassword.fill('\u0000')
            keyPassword.fill('\u0000')
        }
    }
    
    private fun encodeScopes(scopes: Map<String, List<String>>) = JsonObject().apply {
        scopes.forEach { (module, apps) ->
            add(module, JsonArray().apply { apps.forEach { add(it) } })
        }
    }
    
    private fun decodeScopes(document: JsonObject): Map<String, List<String>> =
        document.entrySet().associate { (module, value) ->
            if (!isPackageName(module) || !value.isJsonArray) throw BackupFormatException()
            val apps = value.asJsonArray.map { app ->
                if (!app.isJsonPrimitive || !app.asJsonPrimitive.isString || !isPackageName(app.asString)) {
                    throw BackupFormatException()
                }
                app.asString
            }.distinct()
            if (apps.isEmpty()) throw BackupFormatException()
            module to apps
        }

    private fun isPackageName(value: String) = value.length in 1..255 && packageNamePattern.matches(value)
    
    private fun resolveModules(context: Context, scopes: Map<String, List<String>>): List<ScopedModule> =
        scopes.mapNotNull { (module, apps) ->
            val manager = context.packageManager
            val info = try {
                manager.getPackageInfo(module, PackageManager.GET_META_DATA)
            } catch (_: PackageManager.NameNotFoundException) {
                return@mapNotNull null
            }
            val path = info.applicationInfo?.sourceDir ?: return@mapNotNull null
            if (ModuleMetadataReader.read(info, manager) == null) return@mapNotNull null
            ScopedModule(LoadedModule(module, path), apps)
        }

    private fun chooseFolder(context: Context, prefs: JsonObject): Pair<String?, FolderStatus> {
        val grants = context.contentResolver.persistedUriPermissions
        fun granted(path: String?): Boolean {
            if (path == null) return false
            val uri = Uri.parse(path)
            if (!DocumentsContract.isTreeUri(uri) ||
                grants.none { it.uri == uri && it.isReadPermission && it.isWritePermission }
            ) return false
            return runCatching {
                DocumentFile.fromTreeUri(context, uri)?.let { it.isDirectory && it.canWrite() } == true
            }.getOrDefault(false)
        }
        val restored = prefs[Configs.STORAGE_DIRECTORY]?.asString
        val current = Configs.storageDirectory
        return when {
            granted(restored) -> restored to FolderStatus.RESTORED
            granted(current) -> current to FolderStatus.KEPT
            else -> null to FolderStatus.RESELECT
        }
    }
    
    private fun openBackground(context: Context, path: String): InputStream {
        val local = BackgroundImageStorage.file(context)
        if (path == local.absolutePath) return local.inputStream()
        val uri = Uri.parse(path)
        if (uri.scheme == "content") {
            context.contentResolver.openInputStream(uri)?.let { return it }
        }
        throw IOException("Cannot open saved background image")
    }
    
    private const val JOURNAL = "rollback.json"
    
    private fun journalDirectory(context: Context) = context.noBackupFilesDir.resolve("settings-restore")
    
    private fun localAssets(context: Context) = mapOf(
        SettingsBackupArchive.BACKGROUND to BackgroundImageStorage.file(context),
        SettingsBackupArchive.KEYSTORE to MyKeyStore.file,
    )
    
    private suspend fun prepareJournal(
        context: Context,
        journal: File,
        scopes: List<ScopedModule>,
        affected: Set<String>,
    ) {
        val catalog = ConfigManager.getAllConfiguredModules().filter { it.pkgName in affected }
        val document = JsonObject().apply {
            add("preferences", readPreferences())
            add("theme", readTheme(context))
            add("scopes", encodeScopes(scopes.associate { it.module.pkgName to it.apps }))
            add("paths", JsonObject().apply {
                catalog.forEach { addProperty(it.pkgName, it.apkPath) }
            })
            add("affected", JsonArray().apply { affected.forEach { add(it) } })
            add("snapshots", JsonArray().apply {
                affected.filter(ModuleScopeSyncStore::hasSnapshot).forEach { add(it) }
            })
        }
        for ((name, file) in localAssets(context)) {
            val copy = journal.resolve(name)
            if (file.isFile) writeAtomically(copy) { output -> file.inputStream().use { it.copyTo(output) } }
            else deleteFile(copy)
        }
        writeAtomically(journal.resolve(JOURNAL)) { it.write(document.toString().encodeToByteArray()) }
    }
    
    private suspend fun recover(context: Context, journal: File) {
        val marker = journal.resolve(JOURNAL)
        val document = SettingsBackupArchive.parseObject(marker.readBytes())
        val paths = document.obj("paths")
        val affected = document.getAsJsonArray("affected").map { it.asString }.toSet()
        val previousSnapshots = document.getAsJsonArray("snapshots").map { it.asString }.toSet()
        val scopes = decodeScopes(document.obj("scopes")).map { (module, apps) ->
            ScopedModule(LoadedModule(module, paths.string(module)), apps)
        }
        val scopedPackages = scopes.mapTo(mutableSetOf()) { it.module.pkgName }
        for ((name, target) in localAssets(context)) {
            val original = journal.resolve(name)
            if (original.isFile) writeAtomically(target) { output -> original.inputStream().use { it.copyTo(output) } }
            else deleteFile(target)
        }
        replacePreferences(document.obj("preferences"))
        ConfigManager.replaceScopes(scopes, affected.associateWith { paths[it]?.asString })
        replaceTheme(context, document.obj("theme"))
        updateScopeSnapshots(previousSnapshots + scopedPackages, scopes)
        for (module in affected - previousSnapshots - scopedPackages) {
            ModuleScopeSyncStore.deleteSnapshot(module).getOrThrow()
        }
        deleteFile(marker)
        Configs.reloadBackupPreferences()
    }
    
    private suspend fun updateScopeSnapshots(affected: Set<String>, scopes: List<ScopedModule>) {
        val apps = scopes.associate { it.module.pkgName to it.apps }
        affected.forEach { ModuleScopeSyncStore.saveSnapshot(it, apps[it].orEmpty()).getOrThrow() }
    }
    
    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Cannot remove a settings file")
    }
    
    private fun writeAtomically(target: File, write: (FileOutputStream) -> Unit) {
        val directory = checkNotNull(target.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create settings directory")
        val temporary = File.createTempFile(".settings-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                write(output)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
    
    private fun <T> withTemporaryZip(context: Context, action: (File) -> T): T {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "settings-backup-").toFile()
        try {
            return action(directory.resolve("backup.zip"))
        } finally {
            directory.deleteRecursively()
        }
    }
}

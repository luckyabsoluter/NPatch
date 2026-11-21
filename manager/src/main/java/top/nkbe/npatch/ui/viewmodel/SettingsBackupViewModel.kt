package top.nkbe.npatch.ui.viewmodel

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.nkbe.npatch.R
import top.nkbe.npatch.config.BackupCompression
import top.nkbe.npatch.config.BackupFormatException
import top.nkbe.npatch.config.BackupPasswordRequired
import top.nkbe.npatch.config.BackupSizeException
import top.nkbe.npatch.config.BackupVersionException
import top.nkbe.npatch.config.SettingsBackup
import top.nkbe.npatch.lspApp

internal class SettingsBackupViewModel : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object ExportOptions : State
        data object PickingExport : State
        data object PickingImport : State
        data object Running : State
        data class Password(val uri: Uri) : State
        data class Confirm(val preview: SettingsBackup.Preview) : State
        data class Message(@param:StringRes val text: Int) : State
        data class Restored(val result: SettingsBackup.RestoreResult) : State
    }
    
    var state: State by mutableStateOf(State.Idle)
        private set
    private var compression = BackupCompression.STANDARD
    private var exportPassword: CharArray? = null
    
    fun showExportOptions() {
        if (state == State.Idle) state = State.ExportOptions
    }
    
    fun chooseExportFile(compression: BackupCompression, password: CharArray?) {
        this.compression = compression
        exportPassword = password
        state = State.PickingExport
    }
    
    fun exportPicked(uri: Uri?) {
        if (state != State.PickingExport) return
        val password = exportPassword
        exportPassword = null
        if (uri == null) {
            password?.fill('\u0000')
            reset()
            return
        }
        runOperation(R.string.settings_backup_write_error) {
            try {
                SettingsBackup.export(lspApp, uri, compression, password)
                State.Message(R.string.settings_backup_saved)
            } finally {
                password?.fill('\u0000')
            }
        }
    }
    
    fun chooseImportFile() {
        if (state == State.Idle) state = State.PickingImport
    }
    
    fun importPicked(uri: Uri?) {
        if (state != State.PickingImport) return
        if (uri == null) reset() else inspect(uri, null)
    }
    
    fun inspect(uri: Uri, password: CharArray?) {
        runOperation(R.string.settings_backup_read_error) {
            try {
                State.Confirm(SettingsBackup.inspect(lspApp, uri, password))
            } catch (_: BackupPasswordRequired) {
                State.Password(uri)
            } finally {
                password?.fill('\u0000')
            }
        }
    }
    
    fun restore(preview: SettingsBackup.Preview) {
        runOperation(R.string.settings_backup_apply_error) {
            val result = SettingsBackup.restore(lspApp, preview)
            lspApp.imageLoader.memoryCache?.clear()
            State.Restored(result)
        }
    }
    
    fun reset() {
        exportPassword?.fill('\u0000')
        exportPassword = null
        state = State.Idle
    }
    
    private fun runOperation(@StringRes ioError: Int, operation: suspend () -> State) {
        if (state == State.Running) return
        state = State.Running
        viewModelScope.launch {
            state = try {
                operation()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: BackupVersionException) {
                State.Message(R.string.settings_backup_version_error)
            } catch (_: BackupSizeException) {
                State.Message(R.string.settings_backup_size_error)
            } catch (_: BackupFormatException) {
                State.Message(R.string.settings_backup_format_error)
            } catch (_: Exception) {
                State.Message(ioError)
            }
        }
    }
    
    override fun onCleared() {
        exportPassword?.fill('\u0000')
    }
}

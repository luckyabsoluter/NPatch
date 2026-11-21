package top.nkbe.npatch.ui.page

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.nkbe.npatch.R
import top.nkbe.npatch.config.BackupCompression
import top.nkbe.npatch.config.SettingsBackup
import top.nkbe.npatch.ui.component.DialogButtonBar
import top.nkbe.npatch.ui.component.DialogButtonBarAction
import top.nkbe.npatch.ui.component.OverlayLoadingDialog
import top.nkbe.npatch.ui.viewmodel.SettingsBackupViewModel
import top.nkbe.npatch.ui.viewmodel.SettingsBackupViewModel.State
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SettingsBackupPreferences(model: SettingsBackupViewModel = viewModel()) {
    val context = LocalContext.current
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"), model::exportPicked,
    )
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(), model::importPicked,
    )
    ArrowPreference(
        title = stringResource(R.string.settings_backup_export),
        enabled = model.state == State.Idle,
        onClick = model::showExportOptions,
    )
    ArrowPreference(
        title = stringResource(R.string.settings_backup_import),
        enabled = model.state == State.Idle,
        onClick = {
            model.chooseImportFile()
            importPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        },
    )
    
    when (val state = model.state) {
        State.ExportOptions -> BackupExportDialog(model::reset) { compression, password ->
            model.chooseExportFile(compression, password)
            exportPicker.launch("NPatch-settings.zip")
        }
        is State.Password -> BackupPasswordDialog(model::reset) { model.inspect(state.uri, it) }
        is State.Confirm -> {
            OverlayDialog(
                title = stringResource(R.string.settings_backup_import),
                summary = stringResource(R.string.settings_backup_replace),
                show = true,
                onDismissRequest = model::reset,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RestoreDetails(state.preview.skippedModules, state.preview.folderStatus)
                    DialogButtonBar(
                        negative = DialogButtonBarAction(stringResource(android.R.string.cancel), model::reset),
                        positive = DialogButtonBarAction(stringResource(R.string.settings_backup_import)) {
                            model.restore(state.preview)
                        },
                    )
                }
            }
        }
        State.Running -> OverlayLoadingDialog(
            text = stringResource(R.string.settings_backup_working),
            show = true,
            onDismissRequest = {},
        )
        is State.Message -> OverlayDialog(
            title = stringResource(R.string.settings_backup_category),
            summary = stringResource(state.text),
            show = true,
            onDismissRequest = model::reset,
        ) {
            DialogButtonBar(positive = DialogButtonBarAction(stringResource(android.R.string.ok), model::reset))
        }
        is State.Restored -> {
            val close = {
                model.reset()
                (context as? Activity)?.recreate()
                Unit
            }
            OverlayDialog(
                title = stringResource(R.string.settings_backup_restored),
                show = true,
                onDismissRequest = close,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RestoreDetails(state.result.skippedModules, state.result.folderStatus)
                    DialogButtonBar(positive = DialogButtonBarAction(stringResource(android.R.string.ok), close))
                }
            }
        }
        State.Idle, State.PickingExport, State.PickingImport -> Unit
    }
}

@Composable
private fun BackupExportDialog(onDismiss: () -> Unit, onSave: (BackupCompression, CharArray?) -> Unit) {
    var compression by rememberSaveable { mutableStateOf(BackupCompression.STANDARD.ordinal) }
    var protected by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    OverlayDialog(
        title = stringResource(R.string.settings_backup_export),
        show = true,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayDropdownPreference(
                title = stringResource(R.string.settings_backup_compression),
                items = listOf(
                    stringResource(R.string.settings_backup_compression_store),
                    stringResource(R.string.settings_backup_compression_fast),
                    stringResource(R.string.settings_backup_compression_standard),
                    stringResource(R.string.settings_backup_compression_maximum),
                ),
                selectedIndex = compression,
                onSelectedIndexChange = { compression = it },
            )
            SwitchPreference(
                title = stringResource(R.string.settings_backup_protect),
                checked = protected,
                onCheckedChange = {
                    protected = it
                    if (!it) {
                        password = ""
                        confirmation = ""
                        showError = false
                    }
                },
            )
            if (protected) {
                PasswordField(password, { password = it }, R.string.settings_backup_password)
                PasswordField(confirmation, { confirmation = it }, R.string.settings_backup_confirm_password)
                if (showError) Text(stringResource(R.string.settings_backup_password_mismatch))
            } else {
                Text(stringResource(R.string.settings_backup_unprotected))
            }
            DialogButtonBar(
                negative = DialogButtonBarAction(stringResource(android.R.string.cancel), onDismiss),
                positive = DialogButtonBarAction(stringResource(R.string.settings_backup_choose_destination)) {
                    if (protected && (password.isEmpty() || password != confirmation)) {
                        showError = true
                    } else {
                        onSave(BackupCompression.entries[compression], if (protected) password.toCharArray() else null)
                    }
                },
            )
        }
    }
}

@Composable
private fun BackupPasswordDialog(onDismiss: () -> Unit, onSubmit: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    OverlayDialog(
        title = stringResource(R.string.settings_backup_password),
        show = true,
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PasswordField(password, { password = it }, R.string.settings_backup_password)
            if (showError) Text(stringResource(R.string.settings_backup_password_required))
            DialogButtonBar(
                negative = DialogButtonBarAction(stringResource(android.R.string.cancel), onDismiss),
                positive = DialogButtonBarAction(stringResource(android.R.string.ok)) {
                    if (password.isEmpty()) showError = true else onSubmit(password.toCharArray())
                },
            )
        }
    }
}

@Composable
private fun PasswordField(value: String, change: (String) -> Unit, label: Int) {
    TextField(
        value = value,
        onValueChange = change,
        label = stringResource(label),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}

@Composable
private fun RestoreDetails(skipped: Int, folder: SettingsBackup.FolderStatus) {
    if (skipped != 0) Text(pluralStringResource(R.plurals.settings_backup_skipped_modules, skipped, skipped))
    when (folder) {
        SettingsBackup.FolderStatus.KEPT -> Text(stringResource(R.string.settings_backup_folder_kept))
        SettingsBackup.FolderStatus.RESELECT -> Text(stringResource(R.string.settings_backup_folder_reselect))
        SettingsBackup.FolderStatus.RESTORED -> Unit
    }
}

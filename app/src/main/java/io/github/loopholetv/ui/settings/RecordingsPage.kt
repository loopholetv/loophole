package io.github.loopholetv.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.loopholetv.data.settings.AppSettings
import io.github.loopholetv.record.REMINDER_LEAD_CHOICES
import io.github.loopholetv.record.storage.StorageKind
import io.github.loopholetv.ui.common.TypingField
import io.github.loopholetv.ui.common.TypingState
import io.github.loopholetv.ui.common.rememberTypingState
import io.github.loopholetv.ui.files.isRealDocumentPicker
import io.github.loopholetv.ui.files.launchOrFallback
import io.github.loopholetv.ui.theme.Tokens

private fun rowKey(label: String) = "rc:$label"

/** Settings → Recordings & reminders: where to record, the reminder and padding choices, and two permissions. */
@Composable
fun RecordingsPage(ui: SettingsUi, vm: SettingsViewModel, focusKey: String?, onFocusConsumed: () -> Unit) {
    val host = rememberChoiceHost()
    var message by remember { mutableStateOf<String?>(null) }
    val pickUsb = rememberUsbPicker(vm::setUsbTree) { message = RC_NO_USB_PICKER }
    LaunchedEffect(Unit) {
        focusSoon(host.requester(focusKey ?: rowKey(RC_WHERE)))
        onFocusConsumed()
    }
    ReadPermissionsOnResume(vm)
    LaunchedEffect(ui.options.recordStorage) { vm.refreshStorageInfo() }
    host.Effects()
    host.spec?.let { ChoiceList(it.title, it.options, it.onPick); return }

    val o = ui.options
    val typing = rememberTypingState()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        WhereRow(host, ui, vm, pickUsb) { message = it }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Error) }
        when (o.recordStorage) {
            StorageKind.SMB -> SmbFormRows(o, vm, host, typing)
            StorageKind.NFS -> NfsFormRows(o, vm, host, typing)
            else -> Unit
        }
        ActionRow(
            RC_TEST,
            vm::testStorage,
            Modifier.focusRequester(host.requester(rowKey(RC_TEST))),
            supporting = if (ui.storageTest.running) RC_TESTING else ui.storageTest.text,
            supportingIsError = ui.storageTest.failed,
        )
        ActionRow(RC_FREE, vm::refreshStorageInfo, trailing = freeSpaceText(ui.freeBytes))
        ReminderRows(ui, vm, host)
        PermissionRows(ui)
    }
}

@Composable
private fun WhereRow(host: ChoiceHost, ui: SettingsUi, vm: SettingsViewModel, pickUsb: () -> Unit, say: (String?) -> Unit) {
    val o = ui.options
    host.Row(
        RC_WHERE,
        RC_WHERE_CHOICES,
        o.recordStorage,
        { storageKindText(it, ui.internalFreeBytes) },
        key = rowKey(RC_WHERE),
        supporting = RC_NOT_SET_HINT.takeIf { o.recordStorage == StorageKind.NONE },
    ) { kind ->
        say(null)
        if (kind == StorageKind.USB) pickUsb() else vm.setStorageKind(kind)
    }
}

/** Drafts are kept here and saved with "Save", so typing does not write the settings on every key. */
@Composable
private fun SmbFormRows(o: AppSettings, vm: SettingsViewModel, host: ChoiceHost, typing: TypingState) {
    var form by remember(o.smbHost, o.smbShare, o.smbFolder, o.smbUser, o.smbPassword, o.smbDomain) { mutableStateOf(o.smbForm()) }
    val fields = listOf<Triple<String, String, (String) -> Unit>>(
        Triple(RC_SMB_FIELDS[0], form.host) { form = form.copy(host = it) },
        Triple(RC_SMB_FIELDS[1], form.share) { form = form.copy(share = it) },
        Triple(RC_SMB_FIELDS[2], form.folder) { form = form.copy(folder = it) },
        Triple(RC_SMB_FIELDS[3], form.user) { form = form.copy(user = it) },
        Triple(RC_SMB_FIELDS[4], form.password) { form = form.copy(password = it) },
        Triple(RC_SMB_FIELDS[5], form.domain) { form = form.copy(domain = it) },
    )
    fields.forEach { (label, value, change) ->
        FormField(label, value, change, host, typing, masked = label == RC_SMB_FIELDS[4]) { vm.saveSmb(form) }
    }
    SaveButton(typing) { vm.saveSmb(form) }
}

@Composable
private fun NfsFormRows(o: AppSettings, vm: SettingsViewModel, host: ChoiceHost, typing: TypingState) {
    var form by remember(o.nfsHost, o.nfsExport, o.nfsFolder) { mutableStateOf(o.nfsForm()) }
    FormField(RC_NFS_FIELDS[0], form.host, { form = form.copy(host = it) }, host, typing) { vm.saveNfs(form) }
    FormField(RC_NFS_FIELDS[1], form.export, { form = form.copy(export = it) }, host, typing) { vm.saveNfs(form) }
    FormField(RC_NFS_FIELDS[2], form.folder, { form = form.copy(folder = it) }, host, typing) { vm.saveNfs(form) }
    SaveButton(typing) { vm.saveNfs(form) }
}

private const val MAX_FIELD_LENGTH = 200

@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    host: ChoiceHost,
    typing: TypingState,
    masked: Boolean = false,
    onDone: () -> Unit,
) = TypingField(
    state = typing,
    label = label,
    value = value,
    onChange = { onChange(it.take(MAX_FIELD_LENGTH)) },
    modifier = Modifier.focusRequester(host.requester(rowKey(label))),
    keyboardType = if (masked) KeyboardType.Password else KeyboardType.Text,
    masked = masked,
    onDone = onDone,
)

@Composable
private fun SaveButton(typing: TypingState, onSave: () -> Unit) {
    Spacer(Modifier.height(2.dp))
    PillButton(RC_SAVE, onFocus = typing::stop, onClick = { typing.stop(); onSave() })
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun ReminderRows(ui: SettingsUi, vm: SettingsViewModel, host: ChoiceHost) {
    val o = ui.options
    host.Row(RC_REMIND, REMINDER_LEAD_CHOICES, o.reminderLeadMin, ::reminderLeadText, rowKey(RC_REMIND)) { v ->
        vm.setOption { it.copy(reminderLeadMin = v) }
    }
    ToggleRow(
        RC_AUTO_SWITCH,
        o.reminderAutoSwitch,
        { vm.setOption { it.copy(reminderAutoSwitch = !it.reminderAutoSwitch) } },
        Modifier.focusRequester(host.requester(rowKey(RC_AUTO_SWITCH))),
        supporting = RC_AUTO_SWITCH_HINT.takeIf { !ui.permissions.overlay },
    )
    host.Row(RC_PAD_BEFORE, AppSettings.PAD_CHOICES, o.padBeforeMin, ::padText, rowKey(RC_PAD_BEFORE)) { v ->
        vm.setOption { it.copy(padBeforeMin = v) }
    }
    host.Row(RC_PAD_AFTER, AppSettings.PAD_CHOICES, o.padAfterMin, ::padText, rowKey(RC_PAD_AFTER)) { v ->
        vm.setOption { it.copy(padAfterMin = v) }
    }
}

@Composable
private fun PermissionRows(ui: SettingsUi) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    ActionRow(
        RC_POPUPS,
        { message = openScreen(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
        trailing = allowedText(ui.permissions.overlay),
    )
    ActionRow(
        RC_STANDBY,
        { message = openScreen(context, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) },
        trailing = allowedText(ui.permissions.batteryUnrestricted),
    )
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tokens.Error) }
}

/** Opens a system screen for this app; the message says so when this TV has no such screen. */
private fun openScreen(context: Context, action: String): String? {
    val intent = Intent(action, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        RC_NO_SCREEN
    } catch (_: SecurityException) {
        RC_NO_SCREEN
    }
}

/** The two permissions are changed in system screens: read them again when this one is back. */
@Composable
private fun ReadPermissionsOnResume(vm: SettingsViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermissions() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Launches the system folder picker and keeps the read and write grant; [onNoPicker] when this TV
 * has none (many ship without one): a drive can't be chosen then, and nothing is changed.
 */
@Composable
private fun rememberUsbPicker(onPicked: (String) -> Unit, onNoPicker: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onPicked(uri.toString())
        }
    }
    return remember(launcher) {
        {
            launchOrFallback(
                available = isTreePickerAvailable(context.packageManager),
                launch = { launcher.launch(null) },
                onUnavailable = onNoPicker,
            )
        }
    }
}

private fun isTreePickerAvailable(pm: PackageManager): Boolean {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    @Suppress("DEPRECATION") // The flags overload is API 33+; minSdk is 25.
    val handlers = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
    return isRealDocumentPicker(handlers.mapNotNull { it.activityInfo?.packageName })
}

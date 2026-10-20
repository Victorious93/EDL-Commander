package ai.edlcommander.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.edlcommander.companion.command.CommandResult
import ai.edlcommander.companion.command.CommandRouter
import ai.edlcommander.companion.CompanionFactory
import ai.edlcommander.companion.CompanionRuntime
import ai.edlcommander.companion.bridge.BackupOutcome
import ai.edlcommander.companion.bridge.BridgeGate
import ai.edlcommander.companion.bridge.ConfigBackup
import ai.edlcommander.companion.bridge.PreferencesRemoteControlSetting
import ai.edlcommander.companion.command.Status
import ai.edlcommander.companion.EdlCompanionService
import ai.edlcommander.companion.usb.QdLoader
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val router by lazy { CompanionFactory.createRouter(this) }
    private val remoteControl by lazy { CompanionRuntime.get(this).remoteControl }
    private val configBackup by lazy { CompanionRuntime.get(this).configBackup }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding -> CompanionScreen(router, remoteControl, configBackup, Modifier.padding(padding)) }
            }
        }
    }
}

private val worker = Executors.newSingleThreadExecutor()

@Composable
fun CompanionScreen(
    router: CommandRouter,
    remoteControl: PreferencesRemoteControlSetting,
    configBackup: ConfigBackup,
    modifier: Modifier = Modifier,
) {
    var importText by remember { mutableStateOf("") }
    var confirmImport by remember { mutableStateOf(false) }
    var remoteEnabled by remember { mutableStateOf(remoteControl.isEnabled()) }
    var lastResult by remember { mutableStateOf<CommandResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Device I/O can block for seconds, so commands never run on the main thread.
    fun run(name: String, args: String = "{}", dryRun: Boolean = true) {
        busy = true
        worker.execute {
            val res = router.handleRaw(CommandRouter.SCHEMA_VERSION, "ui-$name", name, args, dryRun)
            lastResult = res
            busy = false
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("EDL Commander companion", style = MaterialTheme.typography.headlineSmall)
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Phase 3 in progress - not validated on hardware", style = MaterialTheme.typography.titleMedium)
                Text(
                    "This build can list USB devices, ask you for USB permission, and run a read-only " +
                        "Sahara identify (serial, hardware ID, OEM PK hash). It never uploads a programmer, " +
                        "never uses Firehose and cannot write, flash, erase or unlock anything. " +
                        "No device is supported or verified yet.",
                )
            }
        }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Droidcommand-AI binding", style = MaterialTheme.typography.titleMedium)
                Text("Bind action: ${EdlCompanionService.ACTION_BIND}")
                Text("Requires the same signing key as the hub (signature permission).")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Allow remote control", Modifier.weight(1f))
                    Switch(
                        checked = remoteEnabled,
                        onCheckedChange = {
                            remoteControl.setEnabled(it)
                            remoteEnabled = remoteControl.isEnabled()
                        },
                    )
                }
                Text(
                    if (remoteEnabled) {
                        "On: a bound fleet controller may run read-only commands and request USB permission prompts. " +
                            "Flashing, writing, erasing and unlocking can never be triggered remotely."
                    } else {
                        "Off (default): remote callers only get capabilities; everything else returns external_control_disabled."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "Detects USB ${"%04x".format(QdLoader.QUALCOMM_VID)}:${"%04x".format(QdLoader.QDLOADER_9008_PID)} " +
                "(QDLoader 9008) by VID/PID only.",
        )
        Button(enabled = !busy, onClick = { run("usb.list") }) { Text("List USB devices (refresh state)") }
        Button(enabled = !busy, onClick = { run("usb.request_permission", dryRun = false) }) {
            Text("Request USB permission for a 9008 device")
        }
        Button(enabled = !busy, onClick = { run("sahara.identify", dryRun = false) }) {
            Text("Identify device (read-only, real)")
        }
        OutlinedButton(enabled = !busy, onClick = { run("sahara.identify", """{"simulate":true}""", dryRun = false) }) {
            Text("Identify simulated device (no USB)")
        }
        OutlinedButton(enabled = !busy, onClick = { run("sahara.identify") }) { Text("Dry-run identify (plan only)") }
        OutlinedButton(enabled = !busy, onClick = { run("companion.capabilities") }) { Text("Show capabilities") }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Settings backup (config.export / config.import)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Local only; remote callers are refused. Preferences only: never keys, secrets or loader paths. " +
                        "Export is disabled until Android Keystore encryption is implemented: no unencrypted backup is " +
                        "written, saved, shared or copied. Importing never turns remote control on; an applied import turns it OFF.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(enabled = !busy, onClick = { lastResult = configBackup.exportForDevice().toResult("config.export") }) {
                    Text("Export settings (disabled: needs Keystore encryption)")
                }
                OutlinedTextField(value = importText, onValueChange = { importText = it }, label = { Text("Paste a backup bundle") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(enabled = !busy && importText.isNotBlank(), onClick = {
                    lastResult = configBackup.import(importText, dryRun = true, conflictPolicy = null, confirmedOnDevice = false).toResult("config.import")
                }) { Text("Preview import (dry run)") }
                OutlinedButton(enabled = !busy && importText.isNotBlank(), onClick = { confirmImport = true }) { Text("Apply import...") }
            }
        }
        if (confirmImport) {
            AlertDialog(
                onDismissRequest = { confirmImport = false },
                title = { Text("Apply settings import?") },
                text = { Text("Existing settings are kept on conflict (policy: skip). Remote control will be turned OFF.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmImport = false
                        lastResult = configBackup.import(importText, dryRun = false, conflictPolicy = null, confirmedOnDevice = true).toResult("config.import")
                        remoteEnabled = remoteControl.isEnabled()
                    }) { Text("Import") }
                },
                dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
            )
        }
        lastResult?.let {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${it.status} / ${it.code}${if (it.simulated) " (simulated)" else ""}", style = MaterialTheme.typography.titleSmall)
                    Text(it.message)
                    Text(it.payload.toString(2), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun BackupOutcome.toResult(name: String) = CommandResult(
    "ui-$name",
    error?.let { BridgeGate.statusFor(it) } ?: if (code == "dry_run") Status.DRY_RUN else Status.OK,
    code, message, payload,
    dryRun = code == "dry_run",
)

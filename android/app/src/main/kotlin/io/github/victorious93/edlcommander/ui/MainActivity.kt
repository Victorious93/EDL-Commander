package io.github.victorious93.edlcommander.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.victorious93.edlcommander.command.CommandResult
import io.github.victorious93.edlcommander.command.CommandRouter
import io.github.victorious93.edlcommander.companion.CompanionFactory
import io.github.victorious93.edlcommander.companion.EdlCompanionService
import io.github.victorious93.edlcommander.usb.QdLoader

class MainActivity : ComponentActivity() {
    private val router by lazy { CompanionFactory.createRouter(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Scaffold { padding -> CompanionScreen(router, Modifier.padding(padding)) }
            }
        }
    }
}

@Composable
fun CompanionScreen(router: CommandRouter, modifier: Modifier = Modifier) {
    var lastResult by remember { mutableStateOf<CommandResult?>(null) }

    fun run(name: String, args: String = "{}", dryRun: Boolean = true) {
        lastResult = router.handleRaw(CommandRouter.SCHEMA_VERSION, "ui-$name", name, args, dryRun)
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
                Text("Scaffold only", style = MaterialTheme.typography.titleMedium)
                Text(
                    "No hardware is supported or validated. USB access is list-only; there is no " +
                        "Sahara, Firehose or flashing in this build.",
                )
            }
        }
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Droidcommand-AI binding", style = MaterialTheme.typography.titleMedium)
                Text("Bind action: ${EdlCompanionService.ACTION_BIND}")
                Text("Requires the same signing key as the hub (signature permission).")
            }
        }
        Text(
            "Detects USB ${"%04x".format(QdLoader.QUALCOMM_VID)}:${"%04x".format(QdLoader.QDLOADER_9008_PID)} " +
                "(QDLoader 9008) by VID/PID only.",
        )
        Button(onClick = { run("usb.list") }) { Text("List USB devices") }
        Button(onClick = { run("sahara.identify") }) { Text("Dry-run Sahara identify (plan only)") }
        Button(onClick = { run("companion.capabilities") }) { Text("Show capabilities") }
        lastResult?.let {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${it.status} / ${it.code}", style = MaterialTheme.typography.titleSmall)
                    Text(it.message)
                    Text(it.payload.toString(2), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

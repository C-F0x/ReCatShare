package moe.reimu.catshare

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import moe.reimu.catshare.ui.DefaultCard
import moe.reimu.catshare.ui.theme.CatShareTheme
import moe.reimu.catshare.utils.ServiceState
import java.io.File

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CatShareTheme {
                SettingsActivityContent()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsActivityContent() {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val settings = remember(activity) { AppSettings(context) }

    var deviceNameValue by remember { mutableStateOf(settings.deviceName) }
    var verboseValue by remember { mutableStateOf(settings.verbose) }
    var autoAcceptValue by remember { mutableStateOf(settings.autoAccept) }
    val originalShutdownMode = remember { settings.autoShutdownMode }
    var autoShutdownModeValue by remember { mutableIntStateOf(settings.autoShutdownMode) }
    var autoShutdownMinutesValue by remember { mutableStateOf(settings.autoShutdownMinutes.toString()) }
    var autoShutdownCountValue by remember { mutableStateOf(settings.autoShutdownCount.toString()) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(text = stringResource(R.string.title_activity_settings)) },
            actions = {
                IconButton(onClick = {
                    if (deviceNameValue.isNotBlank()) {
                        settings.deviceName = deviceNameValue
                    }
                    settings.verbose = verboseValue
                    settings.autoAccept = autoAcceptValue
                    settings.autoShutdownMode = autoShutdownModeValue
                    autoShutdownMinutesValue.toIntOrNull()?.let { if (it > 0) settings.autoShutdownMinutes = it }
                    autoShutdownCountValue.toIntOrNull()?.let { if (it > 0) settings.autoShutdownCount = it }

                    if (autoShutdownModeValue != originalShutdownMode) {
                        context.sendBroadcast(ServiceState.getStopIntent())
                    }

                    activity?.finish()
                }) {
                    Icon(imageVector = Icons.Outlined.Check, contentDescription = "Save")
                }
            })
    }) { innerPadding ->
        val listState = rememberLazyListState()

        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            item {
                DefaultCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = deviceNameValue,
                            onValueChange = { deviceNameValue = it },
                            label = { Text(stringResource(R.string.device_name)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            item {
                DefaultCard {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.verbose_name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.weight(1.0f))
                        Switch(checked = verboseValue, onCheckedChange = { verboseValue = it })
                    }
                }
            }
            item {
                DefaultCard {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.auto_accept_name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.weight(1.0f))
                        Switch(checked = autoAcceptValue, onCheckedChange = { autoAcceptValue = it })
                    }
                }
            }
            item {
                DefaultCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.auto_shutdown_name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = autoShutdownModeValue == 0,
                                onClick = { autoShutdownModeValue = 0 },
                                label = { Text(stringResource(R.string.auto_shutdown_off)) }
                            )
                            FilterChip(
                                selected = autoShutdownModeValue == 1,
                                onClick = { autoShutdownModeValue = 1 },
                                label = { Text(stringResource(R.string.auto_shutdown_timed)) }
                            )
                            FilterChip(
                                selected = autoShutdownModeValue == 2,
                                onClick = { autoShutdownModeValue = 2 },
                                label = { Text(stringResource(R.string.auto_shutdown_count)) }
                            )
                        }
                        if (autoShutdownModeValue == 1) {
                            OutlinedTextField(
                                value = autoShutdownMinutesValue,
                                onValueChange = { autoShutdownMinutesValue = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.auto_shutdown_minutes_label)) },
                                suffix = { Text("min") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        if (autoShutdownModeValue == 2) {
                            OutlinedTextField(
                                value = autoShutdownCountValue,
                                onValueChange = { autoShutdownCountValue = it.filter { c -> c.isDigit() } },
                                label = { Text(stringResource(R.string.auto_shutdown_count_label)) },
                                suffix = { Text(stringResource(R.string.auto_shutdown_count_unit)) },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    }
                }
            }
            item {
                DefaultCard(onClick = {
                    Thread {
                        try {
                            val context = MyApplication.getInstance()
                            val logDir = File(context.cacheDir, "logs")
                            logDir.mkdirs()
                            val logFile = File(logDir, "logcat.txt")

                            logFile.outputStream().use {
                                val proc = Runtime.getRuntime().exec("logcat -d")
                                try {
                                    proc.inputStream.copyTo(it)
                                } finally {
                                    proc.destroy()
                                }
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${BuildConfig.APPLICATION_ID}.fileProvider",
                                logFile
                            )
                            val intent = Intent(Intent.ACTION_SEND)
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .setType("text/plain")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("LogcatCapture", "Failed to save logs", e)
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.log_capture_failed),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }.start()
                }) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.capture_logs),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(text = stringResource(R.string.capture_logs_desc))
                        }
                    }
                }
            }
        }
    }
}
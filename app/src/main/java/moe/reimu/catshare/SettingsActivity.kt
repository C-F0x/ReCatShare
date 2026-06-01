package moe.reimu.catshare

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import moe.reimu.catshare.ui.DefaultCard
import moe.reimu.catshare.ui.theme.CatShareTheme
import moe.reimu.catshare.utils.DeviceUtils
import moe.reimu.catshare.utils.ServiceState
import moe.reimu.catshare.utils.isIgnoringBatteryOptimizations
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
    val originalBrandId = remember { settings.brandId }
    var autoShutdownModeValue by remember { mutableIntStateOf(settings.autoShutdownMode) }
    
    val initialSeconds = settings.autoShutdownSeconds
    var hoursValue by remember { mutableStateOf((initialSeconds / 3600).toString()) }
    var minutesValue by remember { mutableStateOf(((initialSeconds % 3600) / 60).toString()) }
    var secondsValue by remember { mutableStateOf((initialSeconds % 60).toString()) }

    var downloadUriValue by remember { mutableStateOf(settings.downloadUri) }

    var autoShutdownCountValue by remember { mutableStateOf(settings.autoShutdownCount.toString()) }
    
    // Developer Mode states
    var devModeVisible by remember { mutableStateOf(settings.devMode) }
    var devModeEnabled by remember { mutableStateOf(false) } // Expansion toggle
    var overwriteBrandIdValue by remember { mutableStateOf(settings.overwriteBrandId) }
    var brandIdValue by remember { mutableIntStateOf(settings.brandId) }
    var customBrandIdValue by remember { mutableStateOf(settings.customBrandId.toString()) }
    
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(context.isIgnoringBatteryOptimizations()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = context.isIgnoringBatteryOptimizations()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                    settings.brandId = brandIdValue
                    settings.devMode = devModeVisible
                    settings.overwriteBrandId = overwriteBrandIdValue
                    customBrandIdValue.toIntOrNull()?.let { settings.customBrandId = it }
                    
                    val h = hoursValue.toIntOrNull() ?: 0
                    val m = minutesValue.toIntOrNull() ?: 0
                    val s = secondsValue.toIntOrNull() ?: 0
                    val totalSeconds = (h * 3600) + (m * 60) + s
                    settings.autoShutdownSeconds = if (totalSeconds > 0) totalSeconds else (11 * 3600 + 45 * 60 + 14)
                    
                    settings.downloadUri = downloadUriValue

                    val count = autoShutdownCountValue.toIntOrNull() ?: 0
                    settings.autoShutdownCount = if (count > 0) count else 114514

                    if (autoShutdownModeValue != originalShutdownMode || brandIdValue != originalBrandId || overwriteBrandIdValue) {
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            // About Banner
            item {
                AboutBanner(
                    isDevMode = devModeVisible,
                    onDevModeEnabled = { 
                        devModeVisible = true
                        settings.devMode = true
                    }
                )
            }

            item {
                val safLauncher = rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    uri?.let {
                        context.contentResolver.takePersistableUriPermission(
                            it,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        downloadUriValue = it.toString()
                        settings.downloadUri = it.toString()
                    }
                }

                DefaultCard(onClick = {
                    safLauncher.launch(null)
                }) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.download_path),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (downloadUriValue != null) {
                                Uri.parse(downloadUriValue).path ?: downloadUriValue!!
                            } else {
                                stringResource(R.string.default_path)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = hoursValue,
                                    onValueChange = { hoursValue = it.filter { c -> c.isDigit() } },
                                    label = { Text(stringResource(R.string.unit_h)) },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = minutesValue,
                                    onValueChange = { 
                                        val filtered = it.filter { c -> c.isDigit() }
                                        val num = filtered.toIntOrNull() ?: 0
                                        if (num < 60) minutesValue = filtered
                                    },
                                    label = { Text(stringResource(R.string.unit_m)) },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                                OutlinedTextField(
                                    value = secondsValue,
                                    onValueChange = { 
                                        val filtered = it.filter { c -> c.isDigit() }
                                        val num = filtered.toIntOrNull() ?: 0
                                        if (num < 60) secondsValue = filtered
                                    },
                                    label = { Text(stringResource(R.string.unit_s)) },
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                                )
                            }
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
            /*item {
                DefaultCard(onClick = {
                    context.requestIgnoreBatteryOptimizations()
                }) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.battery_optimization),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(text = stringResource(R.string.battery_optimization_desc))
                        }
                        Switch(
                            checked = isIgnoringBatteryOptimizations,
                            onCheckedChange = { context.requestIgnoreBatteryOptimizations() }
                        )
                    }
                }
            }*/
            item {
                DefaultCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.device_brand),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val brands = remember { DeviceUtils.getBrandList() }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (overwriteBrandIdValue) 0.5f else 1f)
                        ) {
                            items(brands) { (id, name) ->
                                FilterChip(
                                    selected = brandIdValue == id,
                                    onClick = { brandIdValue = id },
                                    label = { Text(name) },
                                    enabled = !overwriteBrandIdValue
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Overwrite with custom BrandID",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = overwriteBrandIdValue,
                                onCheckedChange = { overwriteBrandIdValue = it }
                            )
                        }

                        AnimatedVisibility(visible = overwriteBrandIdValue) {
                            OutlinedTextField(
                                value = customBrandIdValue,
                                onValueChange = { customBrandIdValue = it.filter { c -> c.isDigit() } },
                                label = { Text("Custom Brand ID") },
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
                            val ctx = MyApplication.getInstance()
                            val logDir = File(ctx.cacheDir, "logs")
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
                                ctx,
                                "${BuildConfig.APPLICATION_ID}.fileProvider",
                                logFile
                            )
                            val intent = Intent(Intent.ACTION_SEND)
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .setType("text/plain")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(intent)
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

            // Developer Options at the very bottom
            if (devModeVisible) {
                item {
                    DefaultCard {
                        Column(
                            modifier = Modifier
                                .animateContentSize()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "DEV Options",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Switch(
                                    checked = devModeEnabled,
                                    onCheckedChange = { devModeEnabled = it }
                                )
                            }
                            
                            if (devModeEnabled) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 300.dp) // Limits height for internal scrolling
                                        .padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Other dev options could go here
                                    Text(
                                        text = "No other dev options available",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutBanner(isDevMode: Boolean, onDevModeEnabled: () -> Unit) {
    var clickCount by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    DefaultCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "C-F0x @ GitHub",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/C-F0x/ReCatShare/"))
                    context.startActivity(intent)
                }
            )
            Text(
                text = "Builder & Developer",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.padding(8.dp))
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable {
                        if (!isDevMode) {
                            clickCount++
                            if (clickCount >= 7) {
                                onDevModeEnabled()
                                Toast.makeText(context, "You are now a developer!", Toast.LENGTH_SHORT).show()
                            } else if (clickCount > 3) {
                                Toast.makeText(context, "You are now ${7 - clickCount} steps away from being a developer.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(8.dp)
            )
        }
    }
}

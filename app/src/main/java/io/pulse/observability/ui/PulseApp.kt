package io.pulse.observability.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.pulse.observability.export.ExportFormat
import io.pulse.observability.probe.Measurement
import io.pulse.observability.probe.Outcome
import io.pulse.observability.probe.RedirectKind
import io.pulse.observability.probe.StageName
import io.pulse.observability.probe.formatMilliseconds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PulseColors = darkColorScheme(
    primary = Color(0xFFA1E9E2),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF0B5164),
    onPrimaryContainer = Color(0xFFC4F4EE),
    background = Color(0xFF0B1417),
    surface = Color(0xFF101D21),
    surfaceVariant = Color(0xFF17272C),
    onBackground = Color(0xFFE1E9EA),
    onSurface = Color(0xFFE1E9EA),
    onSurfaceVariant = Color(0xFFADBBBF),
    error = Color(0xFFFFB4AB),
)

@Composable
fun PulseApp(viewModel: MeasurementViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingExport by remember { mutableStateOf<ExportFormat?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val format = pendingExport
        pendingExport = null
        if (uri != null && format != null) viewModel.export(uri, format)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    MaterialTheme(colorScheme = PulseColors) {
        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear local history?") },
                text = { Text("This deletes all locally stored measurement records. It cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = { confirmClear = false; viewModel.clearHistory() }) { Text("Clear history") }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep history") } },
            )
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(
                    title = {
                        Column {
                            Text("Pulse", fontWeight = FontWeight.Bold)
                            Text("On-demand network observability", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = state.selectedTab == 0,
                        onClick = { viewModel.selectTab(0) },
                        icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
                        label = { Text("Measure") },
                        modifier = Modifier.testTag("measure_tab"),
                    )
                    NavigationBarItem(
                        selected = state.selectedTab == 1,
                        onClick = { viewModel.selectTab(1) },
                        icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                        label = { Text("History") },
                        modifier = Modifier.testTag("history_tab"),
                    )
                }
            },
        ) { padding ->
            if (state.selectedTab == 0) {
                MeasureScreen(
                    state = state,
                    onTarget = viewModel::setTarget,
                    onRun = viewModel::run,
                    onCancel = viewModel::cancel,
                    modifier = Modifier.padding(padding),
                )
            } else {
                HistoryScreen(
                    measurements = state.history,
                    disabled = state.running,
                    onExport = { format ->
                        pendingExport = format
                        exportLauncher.launch("pulse-${Instant.now().toString().replace(':', '-')}.${format.extension}")
                    },
                    onClear = { confirmClear = true },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
fun MeasureScreen(
    state: PulseUiState,
    onTarget: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text("Measure a real connection", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Resolve a host, connect over TCP, verify TLS, and read a bounded HTTP response. Nothing runs in the background.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            OutlinedTextField(
                value = state.targetInput,
                onValueChange = onTarget,
                modifier = Modifier.fillMaxWidth().testTag("target_input"),
                enabled = !state.running,
                label = { Text("HTTP or HTTPS URL") },
                placeholder = { Text("https://example.com/") },
                supportingText = { Text(state.validationError ?: "Single request · no redirects · 64 KiB response limit") },
                isError = state.validationError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onRun() }),
            )
        }
        item {
            if (state.running) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("cancel_probe")) {
                    Icon(Icons.Outlined.Cancel, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel measurement")
                }
            } else {
                Button(onClick = onRun, modifier = Modifier.fillMaxWidth().testTag("run_probe")) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Run measurement")
                }
            }
        }
        item {
            when {
                state.running -> RunningCard()
                state.latest != null -> MeasurementCard(state.latest!!)
                else -> Card(modifier = Modifier.fillMaxWidth()) {
                    Text("No measurement yet", modifier = Modifier.padding(20.dp), fontWeight = FontWeight.SemiBold)
                    Text("Completed attempts appear here and in local history. There are no sample readings.",
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("Timing uses the device monotonic clock. TLS uses platform trust, SNI, and hostname verification. Only a selected IP address is attempted; this is not a browser reachability test.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun RunningCard() {
    Card(modifier = Modifier.fillMaxWidth().testTag("running_card")) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Measuring live connection", fontWeight = FontWeight.SemiBold)
                Text("DNS → TCP → TLS → HTTP headers → bounded body", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    measurements: List<Measurement>,
    disabled: Boolean,
    onExport: (ExportFormat) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text("Local history", style = MaterialTheme.typography.headlineMedium)
            Text("${measurements.size} stored on this device · maximum 2,000", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onExport(ExportFormat.JSONL) },
                    enabled = !disabled && measurements.isNotEmpty(),
                    modifier = Modifier.testTag("export_jsonl"),
                ) { Text("Export JSONL") }
                OutlinedButton(
                    onClick = { onExport(ExportFormat.CSV) },
                    enabled = !disabled && measurements.isNotEmpty(),
                    modifier = Modifier.testTag("export_csv"),
                ) { Text("Export CSV") }
                IconButton(onClick = onClear, enabled = !disabled && measurements.isNotEmpty()) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Clear local history")
                }
            }
        }
        if (measurements.isEmpty()) {
            item {
                Card { Text("No local measurements yet. Run a probe from the Measure tab.",
                    modifier = Modifier.padding(20.dp)) }
            }
        } else {
            items(measurements, key = { it.id }) { measurement -> MeasurementCard(measurement, compact = true) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun MeasurementCard(measurement: Measurement, compact: Boolean = false) {
    var expanded by remember(measurement.id) { mutableStateOf(!compact) }
    val success = measurement.outcome == Outcome.SUCCESS
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().testTag(if (compact) "history_measurement_${measurement.id}" else "result_status"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (success) "HTTP ${measurement.responseCode}" else "Measurement failed",
                    color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold)
                Text(measurement.totalNanos.formatMilliseconds(), fontWeight = FontWeight.Bold)
            }
            Text(measurement.target, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (compact) {
                Text(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(measurement.startedAtEpochMillis)), style = MaterialTheme.typography.labelSmall)
            }
            if (!success) {
                Text(measurement.failureMessage.orEmpty(), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
                Text("Stage: ${measurement.failureStage?.wireName} · ${measurement.failureCode?.wireName}",
                    style = MaterialTheme.typography.bodySmall)
            }
            measurement.redirectKind?.let { kind ->
                val boundary = redirectBoundaryLabel(kind)
                Text(
                    boundary,
                    color = if (kind == RedirectKind.DOWNGRADE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (kind == RedirectKind.DOWNGRADE) FontWeight.SemiBold else null,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(if (compact) "history_redirect_${measurement.id}" else "result_redirect"),
                )
                measurement.redirectTarget?.let {
                    Text("Redirect not followed · $it", style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (expanded) {
                HorizontalDivider()
                StageName.entries.forEach { stage ->
                    measurement.duration(stage)?.let { DurationRow(stage.wireName, it.formatMilliseconds()) }
                }
                measurement.resolvedAddress?.let { DurationRow("resolved address", it) }
                measurement.firstResponseByteNanos?.let { DurationRow("first response byte", it.formatMilliseconds()) }
                measurement.httpVersion?.let { DurationRow("protocol", "HTTP/$it") }
                measurement.interimResponses?.takeIf { it > 0 }?.let { DurationRow("interim 1xx skipped", it.toString()) }
                measurement.redirectKind?.let { DurationRow("redirect boundary", it.wireName) }
                measurement.tlsProtocol?.let { DurationRow("TLS protocol", it) }
                measurement.tlsCipherSuite?.let { DurationRow("TLS cipher", it) }
                measurement.bodyBytes?.let { DurationRow("response bytes discarded", it.toString()) }
            }
        }
    }
}

/**
 * A redirect is never followed, so the card states the boundary instead of implying that the
 * final destination was measured. A downgrade is the only case rendered as an error.
 */
internal fun redirectBoundaryLabel(kind: RedirectKind): String = when (kind) {
    RedirectKind.SAME_ORIGIN -> "Redirect to the same origin was not followed"
    RedirectKind.CROSS_ORIGIN -> "Redirect to a different origin was not followed"
    RedirectKind.DOWNGRADE -> "Redirect would downgrade HTTPS to unencrypted HTTP"
    RedirectKind.UPGRADE -> "Redirect would upgrade HTTP to HTTPS"
    RedirectKind.UNRESOLVED -> "Redirect location could not be safely classified"
}

@Composable
private fun DurationRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

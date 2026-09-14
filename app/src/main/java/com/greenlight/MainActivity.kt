package com.greenlight

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.greenlight.data.DebugLog
import com.greenlight.data.GeocodeResult
import com.greenlight.data.GreenLightDb
import com.greenlight.data.geocode
import com.greenlight.model.GlosaAdvice
import com.greenlight.nav.EngineStatus
import com.greenlight.service.GlosaService
import com.greenlight.ui.AdviceCard
import com.greenlight.ui.GreenLightTheme
import com.greenlight.ui.StatRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Counters read straight from SQLite so they survive the service being stopped. */
private data class DbStats(
    val cachedSignals: Int = 0,
    val passes: Int = 0,
    val stoppedPasses: Int = 0,
    val learnedSignals: Int = 0,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GreenLightTheme {
                Surface(modifier = Modifier.fillMaxSize()) { HomeScreen() }
            }
        }
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Polling the holder is enough: the service is a singleton and the UI is not the
    // critical path. It avoids a bound-service dance for something checked once a second.
    var engineRef by remember { mutableStateOf(GlosaService.Holder.engine) }
    LaunchedEffect(Unit) {
        while (true) {
            engineRef = GlosaService.Holder.engine
            delay(1000)
        }
    }

    val advice: GlosaAdvice = engineRef?.advice?.collectAsState()?.value
        ?: GlosaAdvice.noAdvice("Service not running")
    val status: EngineStatus = engineRef?.status?.collectAsState()?.value ?: EngineStatus()

    var hasLocation by remember { mutableStateOf(hasLocationPermission(context)) }
    var canOverlay by remember { mutableStateOf(canDrawOverlays(context)) }
    LaunchedEffect(engineRef) {
        hasLocation = hasLocationPermission(context)
        canOverlay = canDrawOverlays(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasLocation = hasLocationPermission(context) }

    val db = remember { GreenLightDb(context.applicationContext) }
    var stats by remember { mutableStateOf(DbStats()) }
    var statsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(statsTick) {
        DebugLog.attach(db)
        while (true) {
            stats = withContext(Dispatchers.IO) {
                DbStats(
                    cachedSignals = db.cachedSignalCount(),
                    passes = db.observationCount(),
                    stoppedPasses = db.stoppedObservationCount(),
                    learnedSignals = db.learnedSignalCount(),
                )
            }
            delay(2000)
        }
    }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "GreenLight",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Speed advice for catching the next green",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))

            AdviceCard(advice = advice, currentMps = status.lastFix?.speedMps ?: 0.0)
            Spacer(Modifier.height(16.dp))

            // --- Controls -------------------------------------------------------
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!status.running) {
                    Button(
                        onClick = {
                            if (!hasLocation) {
                                permissionLauncher.launch(locationPermissions())
                            } else {
                                GlosaService.start(context)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (hasLocation) "Start" else "Grant location") }
                } else {
                    OutlinedButton(
                        onClick = { GlosaService.stop(context) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Stop") }
                }

                if (!canOverlay) {
                    OutlinedButton(
                        onClick = { requestOverlayPermission(context) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Enable overlay") }
                }
            }

            if (!canOverlay) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "The overlay is what lets the advice sit on top of Google Maps. " +
                        "Without it you only get the notification.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // --- Destination ----------------------------------------------------
            Text("Destination", fontWeight = FontWeight.SemiBold)
            Text(
                "Optional. Without one, GreenLight watches the road ahead and picks up " +
                    "signals as they come - which is what you want when Google Maps is navigating.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search an address or place") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    scope.launch {
                        searching = true
                        searchError = null
                        runCatching { geocode(query, status.lastFix?.position) }
                            .onSuccess { results = it }
                            .onFailure { searchError = it.message }
                        searching = false
                    }
                }),
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            searching = true
                            searchError = null
                            runCatching { geocode(query, status.lastFix?.position) }
                                .onSuccess { results = it }
                                .onFailure { searchError = it.message }
                            searching = false
                        }
                    },
                    enabled = query.isNotBlank() && !searching,
                ) { Text("Search") }

                if (status.destinationLabel != null) {
                    OutlinedButton(onClick = { GlosaService.clearDestination(context) }) {
                        Text("Clear route")
                    }
                }
                if (searching) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                }
            }

            searchError?.let {
                Spacer(Modifier.height(6.dp))
                Text("Search failed: $it", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            if (results.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    LazyColumn(modifier = Modifier.height(190.dp)) {
                        items(results) { r ->
                            TextButton(
                                onClick = {
                                    GlosaService.start(context)
                                    GlosaService.setDestination(
                                        context, r.position.lat, r.position.lon, r.label,
                                    )
                                    results = emptyList()
                                    query = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    r.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }

            status.destinationLabel?.let {
                Spacer(Modifier.height(10.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Routing to", style = MaterialTheme.typography.labelMedium)
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        status.routeDistanceMeters?.let { d ->
                            Text(
                                "%.1f km".format(d / 1000.0),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // --- Learning status -------------------------------------------------
            Text("Learning", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            StatRow("Mode", if (status.mode == EngineStatus.Mode.ROUTE) "On route" else "Free drive")
            // Read from the database rather than the engine: the engine dies with the
            // service, and reading it would reset every counter to zero on Stop.
            StatRow("Signals cached", "${stats.cachedSignals}")
            StatRow("Passes recorded", "${stats.passes}")
            StatRow("Passes with a stop", "${stats.stoppedPasses}")
            StatRow("Signals with timing data", "${stats.learnedSignals}")
            status.lastFix?.let {
                StatRow("GPS accuracy", "${it.accuracyMeters.roundToInt()} m")
                StatRow("Speed", "${(it.speedMps * 3.6).roundToInt()} km/h")
            }
            status.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            // --- Diagnostics -----------------------------------------------------
            Text("Diagnostics", fontWeight = FontWeight.SemiBold)
            Text(
                "The app records what the engine did on each drive. Share the report if " +
                    "something looks wrong - it distinguishes \"no signals here\" from " +
                    "\"the signal server was down\" from \"the detector never fired\".",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { DebugLog.report(db) }
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "GreenLight diagnostics")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                },
                                "Share diagnostics",
                            )
                        )
                    }
                }) { Text("Share report") }

                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { db.clearLogs() }
                        statsTick++
                    }
                }) { Text("Clear log") }
            }

            Spacer(Modifier.height(14.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("How this works", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Porsche and Audi get live signal timing from a commercial feed licensed " +
                            "to the car. A phone cannot buy into that, so GreenLight learns each " +
                            "light's cycle from your own stops. Give a junction four or five " +
                            "passes before trusting the number, and expect it to stay quiet on " +
                            "sensor-actuated lights that have no fixed cycle to learn.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun locationPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

private fun canDrawOverlays(context: Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    context.startActivity(
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

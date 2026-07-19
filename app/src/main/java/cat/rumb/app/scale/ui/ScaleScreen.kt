package cat.rumb.app.scale.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.rumb.app.R
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.manager.screens.DetailScaffold
import cat.rumb.app.manager.screens.formatDayMonthYearShort
import cat.rumb.app.scale.BodyMetrics
import cat.rumb.app.scale.Sex
import cat.rumb.app.scale.WeighInEntity
import cat.rumb.app.scale.ble.BleScaleClient
import cat.rumb.app.scale.ble.ScaleState
import cat.rumb.app.viewer.hud.drawSeries
import kotlinx.coroutines.launch

/**
 * The weight-control module's whole UI: three tabs (live reading, stats, history) plus a Bluetooth
 * pairing panel. Self-contained under `cat.rumb.app.scale` — nothing here is referenced from the
 * rest of the app except the route that opens it.
 */
@Composable
fun ScaleScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val prefs = remember { ViewerPreferences.get(context) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showBt by remember { mutableStateOf(prefs.scaleAddress == null) }

    val weighIns by remember { app.weightRepository.observeAll() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    DetailScaffold(
        title = stringResource(R.string.scale_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showBt = !showBt }) {
                Icon(Icons.Filled.Bluetooth, contentDescription = stringResource(R.string.scale_bt_manage))
            }
        },
    ) { modifier ->
        Column(modifier.fillMaxSize()) {
            if (showBt) {
                BluetoothPanel(prefs = prefs, onDone = { showBt = false })
            } else {
                val tabs = listOf(R.string.scale_tab_live, R.string.scale_tab_stats, R.string.scale_tab_history)
                TabRow(selectedTabIndex = tab) {
                    tabs.forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label)) })
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (tab) {
                        0 -> LiveTab(prefs = prefs, onPairNeeded = { showBt = true })
                        1 -> StatsTab(weighIns)
                        else -> HistoryTab(weighIns, onDelete = { id ->
                            scope.launch { app.weightRepository.delete(id) }
                        })
                    }
                }
            }
        }
    }
}

// --- Live reading -------------------------------------------------------------------------------

private enum class Who { SELF, GUEST }

@Composable
private fun LiveTab(prefs: ViewerPreferences, onPairNeeded: () -> Unit) {
    val context = LocalContext.current
    val app = remember { RumbApplication.from(context) }
    val scope = rememberCoroutineScope()

    var who by remember { mutableStateOf(Who.SELF) }
    // Guest profile is transient — it never touches the stored profile and is discarded on leave.
    var guestHeight by remember { mutableStateOf(170) }
    var guestAge by remember { mutableStateOf(30) }
    var guestSex by remember { mutableStateOf("M") }

    val address = prefs.scaleAddress
    var client by remember { mutableStateOf<BleScaleClient?>(null) }
    var state by remember { mutableStateOf<ScaleState>(ScaleState.Idle) }
    var saved by remember { mutableStateOf(false) }

    fun heightAgeSex(): Triple<Int, Int, String> =
        if (who == Who.SELF) Triple(prefs.userHeightCm, prefs.userAge, prefs.userSex)
        else Triple(guestHeight, guestAge, guestSex)

    // Collect the client's state, and always tear the connection down when it changes or we leave —
    // no background BLE. Collecting unconditionally (not client?.state?…) keeps the call order stable.
    DisposableEffect(client) {
        val c = client
        val job = c?.let { scope.launch { it.state.collect { s -> state = s } } }
        onDispose { job?.cancel(); c?.stop(); state = ScaleState.Idle }
    }

    // Save your own stabilized reading exactly once — in an effect, not during composition.
    LaunchedEffect(state, who) {
        val s = state
        if (who == Who.SELF && s is ScaleState.Done && !saved) {
            saved = true
            val (h, a, sx) = heightAgeSex()
            app.weightRepository.add(System.currentTimeMillis(), s.frame.weightKg, s.frame.impedanceOhm, h, a, sx)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(who == Who.SELF, { who = Who.SELF; saved = false }, label = { Text(stringResource(R.string.scale_who_me)) })
            FilterChip(who == Who.GUEST, { who = Who.GUEST; saved = false }, label = { Text(stringResource(R.string.scale_who_guest)) })
        }
        if (who == Who.GUEST) {
            Text(stringResource(R.string.scale_guest_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            GuestProfileRow(guestHeight, guestAge, guestSex, { guestHeight = it }, { guestAge = it }, { guestSex = it })
        }

        if (address == null) {
            Text(stringResource(R.string.scale_not_paired), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onPairNeeded, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scale_pair)) }
        } else {
            Button(
                onClick = {
                    saved = false
                    client?.stop()
                    client = BleScaleClient(context, address).also { it.start() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.scale_weigh)) }

            when (val s = state) {
                is ScaleState.Connecting -> StatusLine(R.string.scale_state_connecting)
                is ScaleState.Live -> BigWeight(s.weightKg, live = true)
                is ScaleState.Error -> Text(stringResource(errorRes(s.message)), color = MaterialTheme.colorScheme.error)
                is ScaleState.Done -> {
                    val (h, a, sx) = heightAgeSex()
                    val metrics = cat.rumb.app.scale.BodyComposition.compute(s.frame.weightKg, s.frame.impedanceOhm, h, a, sexOf(sx))
                    BigWeight(metrics.weightKg, live = false)
                    if (who == Who.SELF) Text(stringResource(R.string.scale_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    MetricList(metrics, sexOf(sx), previous = null)
                }
                ScaleState.Idle -> StatusLine(R.string.scale_state_idle)
            }
        }
    }
}

@Composable
private fun GuestProfileRow(height: Int, age: Int, sex: String, onHeight: (Int) -> Unit, onAge: (Int) -> Unit, onSex: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Stepper(stringResource(R.string.settings_height, height), { onHeight((height - 1).coerceIn(80, 220)) }, { onHeight((height + 1).coerceIn(80, 220)) })
        Stepper(stringResource(R.string.settings_age, age), { onAge((age - 1).coerceIn(1, 99)) }, { onAge((age + 1).coerceIn(1, 99)) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(sex == "M", { onSex("M") }, label = { Text(stringResource(R.string.settings_sex_male)) })
            FilterChip(sex == "F", { onSex("F") }, label = { Text(stringResource(R.string.settings_sex_female)) })
        }
    }
}

@Composable
private fun Stepper(label: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onMinus) { Text("−") }
        Text(label, modifier = Modifier.weight(1f))
        TextButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun BigWeight(weightKg: Double, live: Boolean) {
    Text(
        "%.1f kg".format(weightKg),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = if (live) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun StatusLine(res: Int) = Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)

// --- Metric model + list ------------------------------------------------------------------------

private data class MetricSpec(
    val nameRes: Int,
    val unit: String,
    val decimals: Int,
    val meaningRes: Int,
    val value: (BodyMetrics) -> Double?,
    /** Healthy [min,max] for a status chip; sex-dependent for some. */
    val range: (Sex?) -> Pair<Double, Double>?,
)

private val METRICS = listOf(
    MetricSpec(R.string.scale_m_bmi, "", 1, R.string.scale_meaning_bmi, { it.bmi }, { 18.5 to 24.9 }),
    MetricSpec(R.string.scale_m_fat, "%", 1, R.string.scale_meaning_fat, { it.bodyFatPct }, { s -> if (s == Sex.FEMALE) 18.0 to 28.0 else 10.0 to 20.0 }),
    MetricSpec(R.string.scale_m_water, "%", 1, R.string.scale_meaning_water, { it.waterPct }, { s -> if (s == Sex.FEMALE) 45.0 to 60.0 else 50.0 to 65.0 }),
    MetricSpec(R.string.scale_m_muscle, "kg", 1, R.string.scale_meaning_muscle, { it.muscleMassKg }, { null }),
    MetricSpec(R.string.scale_m_bone, "kg", 1, R.string.scale_meaning_bone, { it.boneMassKg }, { null }),
    MetricSpec(R.string.scale_m_visceral, "", 0, R.string.scale_meaning_visceral, { it.visceralFat }, { 1.0 to 9.0 }),
    MetricSpec(R.string.scale_m_bmr, "kcal", 0, R.string.scale_meaning_bmr, { it.bmrKcal }, { null }),
    MetricSpec(R.string.scale_m_protein, "%", 1, R.string.scale_meaning_protein, { it.proteinPct }, { 16.0 to 20.0 }),
    MetricSpec(R.string.scale_m_metabolic_age, "", 0, R.string.scale_meaning_metabolic_age, { it.metabolicAge }, { null }),
)

@Composable
private fun MetricList(metrics: BodyMetrics, sex: Sex?, previous: BodyMetrics?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        METRICS.forEach { spec ->
            val v = spec.value(metrics) ?: return@forEach
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(spec.nameRes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(fmt(v, spec.decimals) + spec.unit, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        previous?.let { p -> spec.value(p)?.let { DeltaBadge(v - it, spec.decimals, spec.unit) } }
                    }
                    Text(stringResource(spec.meaningRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    spec.range(sex)?.let { (lo, hi) -> RangeChip(v, lo, hi, spec.decimals, spec.unit) }
                }
            }
        }
    }
}

@Composable
private fun RangeChip(v: Double, lo: Double, hi: Double, decimals: Int, unit: String) {
    val status = when {
        v < lo -> R.string.scale_status_low to Color(0xFF3A86FF)
        v > hi -> R.string.scale_status_high to Color(0xFFE63946)
        else -> R.string.scale_status_normal to Color(0xFF2A9D8F)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(status.first),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(status.second).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            stringResource(R.string.scale_healthy_range, fmt(lo, decimals) + unit, fmt(hi, decimals) + unit),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun DeltaBadge(delta: Double, decimals: Int, unit: String) {
    if (kotlin.math.abs(delta) < 0.05) return
    val text = (if (delta >= 0) "+" else "") + fmt(delta, decimals) + unit
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
}

// --- Stats --------------------------------------------------------------------------------------

@Composable
private fun StatsTab(weighIns: List<WeighInEntity>) {
    if (weighIns.size < 2) {
        EmptyHint(R.string.scale_stats_empty)
        return
    }
    val metricsSeq = remember(weighIns) { weighIns.map { it.metrics() } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        METRICS.forEach { spec ->
            val series = metricsSeq.mapNotNull { spec.value(it)?.toFloat() }
            if (series.size < 2) return@forEach
            val first = series.first()
            val last = series.last()
            Card {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(spec.nameRes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(fmt(last.toDouble(), spec.decimals) + spec.unit, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        DeltaBadge((last - first).toDouble(), spec.decimals, spec.unit)
                    }
                    val accent = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                        drawSeries(series, size.width, size.height, accent, baselineZero = false)
                    }
                }
            }
        }
    }
}

// --- History ------------------------------------------------------------------------------------

@Composable
private fun HistoryTab(weighIns: List<WeighInEntity>, onDelete: (Long) -> Unit) {
    if (weighIns.isEmpty()) {
        EmptyHint(R.string.scale_history_empty)
        return
    }
    // Newest first for the list; keep chronological order to frame each against the previous one.
    val chronological = remember(weighIns) { weighIns.sortedBy { it.timestamp } }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<WeighInEntity?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chronological.reversed().forEach { w ->
            val prev = chronological.getOrNull(chronological.indexOf(w) - 1)?.metrics()
            Card {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { expandedId = if (expandedId == w.id) null else w.id }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(formatDayMonthYearShort(w.timestamp), style = MaterialTheme.typography.bodyMedium)
                            Text("%.1f kg".format(w.weightKg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { pendingDelete = w }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.home_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (expandedId == w.id) {
                        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            // Breakdown framed with the change vs the previous weigh-in.
                            MetricList(w.metrics(), sexOf(w.sex), previous = prev)
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { w ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(formatDayMonthYearShort(w.timestamp)) },
            text = { Text(stringResource(R.string.scale_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { onDelete(w.id); pendingDelete = null }) {
                    Text(stringResource(R.string.home_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.home_cancel)) } },
        )
    }
}

@Composable
private fun EmptyHint(res: Int) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(res), color = MaterialTheme.colorScheme.outline)
    }
}

// --- Bluetooth pairing --------------------------------------------------------------------------

@SuppressLint("MissingPermission") // scanning only starts after the runtime permission is granted
@Composable
private fun BluetoothPanel(prefs: ViewerPreferences, onDone: () -> Unit) {
    val context = LocalContext.current
    var paired by remember { mutableStateOf(prefs.scaleAddress) }
    val found = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val scanner = remember {
        (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
    }
    val callback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found[result.device.address] = result.device.name ?: result.scanRecord?.deviceName ?: context.getString(R.string.scale_default_name)
            }
            override fun onScanFailed(errorCode: Int) { scanError = errorCode.toString() }
        }
    }
    fun startScan() {
        val s = scanner ?: run { scanError = context.getString(R.string.sensors_bluetooth_off); return }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleScaleClient.SCAN_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // Unfiltered fallback too: some units don't advertise the service UUID in the primary packet.
        runCatching { s.startScan(filters, settings, callback); scanning = true; scanError = null }.onFailure { scanError = it.message }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { g -> if (g.values.all { it }) startScan() }
    fun scanWithPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permLauncher.launch(arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT))
        } else startScan()
    }
    DisposableEffect(Unit) { onDispose { runCatching { scanner?.stopScan(callback) } } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.scale_bt_intro), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = { if (scanning) { runCatching { scanner?.stopScan(callback) }; scanning = false } else scanWithPermissions() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (scanning) R.string.sensors_stop_scan else R.string.scale_bt_scan)) }
        scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        val all = (found.keys + listOfNotNull(paired)).distinct().sorted()
        if (all.isEmpty()) Text(stringResource(R.string.scale_bt_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        all.forEach { address ->
            Card {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(found[address] ?: stringResource(R.string.scale_default_name), style = MaterialTheme.typography.bodyMedium)
                        Text(address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    val isPaired = paired == address
                    FilterChip(
                        selected = isPaired,
                        onClick = { paired = if (isPaired) null else address; prefs.scaleAddress = paired },
                        label = { Text(stringResource(if (isPaired) R.string.scale_bt_paired else R.string.scale_bt_pair)) },
                    )
                }
            }
        }
        if (paired != null) Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scale_bt_done)) }
    }
}

// --- helpers ------------------------------------------------------------------------------------

private fun fmt(v: Double, decimals: Int): String = "%.${decimals}f".format(v)

private fun sexOf(code: String): Sex? = when (code) {
    "M" -> Sex.MALE
    "F" -> Sex.FEMALE
    else -> null
}

private fun errorRes(message: String): Int = when (message) {
    "no-permission" -> R.string.scale_err_permission
    "bluetooth-off" -> R.string.scale_err_bluetooth
    "unknown-scale" -> R.string.scale_err_unknown
    else -> R.string.scale_err_generic
}

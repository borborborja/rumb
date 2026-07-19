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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.rumb.app.R
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.manager.screens.DetailScaffold
import cat.rumb.app.manager.screens.formatDayMonthYearShort
import cat.rumb.app.scale.BodyComposition
import cat.rumb.app.scale.BodyMetrics
import cat.rumb.app.scale.Sex
import cat.rumb.app.scale.WeighInEntity
import cat.rumb.app.scale.ble.BleScaleClient
import cat.rumb.app.scale.ble.ScaleState
import cat.rumb.app.viewer.hud.drawSeries
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Semantic zone colours (not the app accent): where a value falls against its healthy band.
private val ZoneLow = Color(0xFF3577C2)
private val ZoneGood = Color(0xFF1F997C)
private val ZoneHigh = Color(0xFFD94B33)

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
                        0 -> LiveTab(prefs = prefs, weighIns = weighIns, onPairNeeded = { showBt = true })
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
private fun LiveTab(prefs: ViewerPreferences, weighIns: List<WeighInEntity>, onPairNeeded: () -> Unit) {
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
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
                    val sex = sexOf(sx)
                    val metrics = BodyComposition.compute(s.frame.weightKg, s.frame.impedanceOhm, h, a, sex)
                    // Previous weigh-in (yours only) frames the change; the just-saved reading is the
                    // list's newest, so step back one when it's already there.
                    val prior = if (who == Who.SELF) priorTo(weighIns, metrics.weightKg) else null
                    ReadingHero(metrics, sex, prior?.metrics()?.weightKg)
                    if (who == Who.SELF) Text(stringResource(R.string.scale_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    SectionHeader(R.string.scale_section_composition, R.string.scale_estimate_short)
                    if (metrics.bodyFatPct == null) {
                        Text(stringResource(R.string.scale_needs_profile), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    CompositionGrid(metrics, sex, previous = prior?.metrics(), showEstimate = true)
                }
                ScaleState.Idle -> StatusLine(R.string.scale_state_idle)
            }
        }

        // How to weigh so readings line up — the single biggest lever on BIA accuracy. Collapsed by
        // default so it doesn't crowd repeat weigh-ins, always one tap away.
        WeighTips()
    }
}

/** Newest saved weigh-in that isn't the reading currently on screen (matched by weight). */
private fun priorTo(weighIns: List<WeighInEntity>, currentWeight: Double): WeighInEntity? {
    val sorted = weighIns.sortedBy { it.timestamp }
    val last = sorted.lastOrNull() ?: return null
    return if (abs(last.weightKg - currentWeight) < 0.05) sorted.getOrNull(sorted.size - 2) else last
}

/** Collapsible card with standardized-weigh-in guidance. */
@Composable
private fun WeighTips() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card {
        Column(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.scale_tips_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (expanded) {
                Text(stringResource(R.string.scale_tips_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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

@Composable
private fun SectionHeader(titleRes: Int, hintRes: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        hintRes?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
    }
}

// --- The hero reading ---------------------------------------------------------------------------

@Composable
private fun ReadingHero(metrics: BodyMetrics, sex: Sex?, priorWeight: Double?) {
    val accentSoft = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val surface = MaterialTheme.colorScheme.surface
    Card {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(accentSoft, surface)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.scale_weight_label).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.1f".format(metrics.weightKg), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                        Text(" kg", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                priorWeight?.let { DeltaPill(metrics.weightKg - it, "kg") }
            }

            val fat = metrics.bodyFatPct
            if (fat != null) {
                val (lo, hi) = fatRange(sex)
                BodyFatGauge(fat, lo, hi)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    metrics.bmi?.let { HeroChip(R.string.scale_m_bmi, it, "") }
                    metrics.waterPct?.let { HeroChip(R.string.scale_m_water, it, "%") }
                    metrics.muscleMassKg?.let { HeroChip(R.string.scale_m_muscle, it, "kg") }
                }
            } else {
                metrics.bmi?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        HeroChip(R.string.scale_m_bmi, it, "")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeltaPill(delta: Double, unit: String) {
    val arrow = if (delta >= 0) "▲" else "▼"
    Text(
        "$arrow ${fmt(abs(delta), 1)} $unit".trim(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun RowScope.HeroChip(nameRes: Int, value: Double, unit: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.weight(1f),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(stringResource(nameRes).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt(value, 1), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Body-fat as a 270° zoned dial (low/healthy/high) with a marker and the value in the centre. */
@Composable
private fun BodyFatGauge(fatPct: Double, lo: Double, hi: Double) {
    val domainMin = 5.0
    val domainMax = 40.0
    val frac = ((fatPct - domainMin) / (domainMax - domainMin)).toFloat().coerceIn(0f, 1f)
    val floLo = ((lo - domainMin) / (domainMax - domainMin)).toFloat().coerceIn(0f, 1f)
    val floHi = ((hi - domainMin) / (domainMax - domainMin)).toFloat().coerceIn(0f, 1f)

    var appear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appear = true }
    val animFrac by animateFloatAsState(if (appear) frac else 0f, tween(900), label = "gaugeMarker")

    val markerColor = MaterialTheme.colorScheme.onSurface
    val ringBg = MaterialTheme.colorScheme.surface
    val zone = zoneFor(fatPct, lo, hi)

    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().aspectRatio(1f)) {
                val sw = size.minDimension * 0.11f
                val inset = sw / 2f + 2f
                val box = size.minDimension - 2f * inset
                val cx = size.width / 2f
                val cy = size.height / 2f
                val ringR = box / 2f
                val topLeft = Offset(cx - ringR, cy - ringR)
                val arcSize = Size(box, box)
                fun startOf(f: Float) = 135f + f * 270f
                // Zones tile edge-to-edge → butt caps so they don't bleed into each other.
                drawArc(ZoneLow, startOf(0f), floLo * 270f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Butt))
                drawArc(ZoneGood, startOf(floLo), (floHi - floLo) * 270f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Butt))
                drawArc(ZoneHigh, startOf(floHi), (1f - floHi) * 270f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Butt))
                // Marker knob riding the ring at the value angle.
                val ang = (startOf(animFrac)) * (Math.PI / 180f)
                val mx = cx + ringR * cos(ang).toFloat()
                val my = cy + ringR * sin(ang).toFloat()
                drawCircle(ringBg, radius = sw * 0.62f, center = Offset(mx, my))
                drawCircle(markerColor, radius = sw * 0.42f, center = Offset(mx, my))
                drawCircle(zoneColorFor(fatPct, lo, hi), radius = sw * 0.20f, center = Offset(mx, my))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmt(fatPct, 1), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(" %", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text(stringResource(R.string.scale_m_fat), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                ZoneTag(zone)
            }
        }
    }
}

// --- Metric model + grid ------------------------------------------------------------------------

private data class MetricSpec(
    val nameRes: Int,
    val unit: String,
    val decimals: Int,
    val meaningRes: Int,
    val value: (BodyMetrics) -> Double?,
    /** Healthy [min,max] for a status chip; sex-dependent for some. Null → no range (trend only). */
    val range: (Sex?) -> Pair<Double, Double>?,
    /** Display domain for the range bar. */
    val domain: Pair<Double, Double>? = null,
    /** true = lower is better, false = higher is better, null = no judgement (neutral trend). */
    val betterDown: Boolean? = null,
)

// Body fat is the hero gauge, so it's absent here; the grid details everything else.
private val METRICS = listOf(
    MetricSpec(R.string.scale_m_water, "%", 1, R.string.scale_meaning_water, { it.waterPct }, { s -> if (s == Sex.FEMALE) 45.0 to 60.0 else 50.0 to 65.0 }, 35.0 to 72.0, betterDown = false),
    MetricSpec(R.string.scale_m_muscle, "kg", 1, R.string.scale_meaning_muscle, { it.muscleMassKg }, { null }, betterDown = false),
    MetricSpec(R.string.scale_m_visceral, "", 0, R.string.scale_meaning_visceral, { it.visceralFat }, { 1.0 to 9.0 }, 0.0 to 16.0, betterDown = true),
    MetricSpec(R.string.scale_m_bmr, "kcal", 0, R.string.scale_meaning_bmr, { it.bmrKcal }, { null }),
    MetricSpec(R.string.scale_m_protein, "%", 1, R.string.scale_meaning_protein, { it.proteinPct }, { 16.0 to 20.0 }, 10.0 to 26.0, betterDown = false),
    MetricSpec(R.string.scale_m_bone, "kg", 1, R.string.scale_meaning_bone, { it.boneMassKg }, { null }),
    MetricSpec(R.string.scale_m_metabolic_age, "", 0, R.string.scale_meaning_metabolic_age, { it.metabolicAge }, { null }, betterDown = true),
    MetricSpec(R.string.scale_m_bmi, "", 1, R.string.scale_meaning_bmi, { it.bmi }, { 18.5 to 24.9 }, 14.0 to 34.0),
)

private fun fatRange(sex: Sex?): Pair<Double, Double> = if (sex == Sex.FEMALE) 18.0 to 28.0 else 10.0 to 20.0

@Composable
private fun CompositionGrid(metrics: BodyMetrics, sex: Sex?, previous: BodyMetrics?, showEstimate: Boolean) {
    val shown = METRICS.filter { it.value(metrics) != null }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { spec ->
                    Box(Modifier.weight(1f)) { MetricTile(spec, metrics, sex, previous) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (showEstimate && metrics.bodyFatPct != null) {
            Text(
                stringResource(R.string.scale_estimate_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun MetricTile(spec: MetricSpec, metrics: BodyMetrics, sex: Sex?, previous: BodyMetrics?) {
    val v = spec.value(metrics) ?: return
    val range = spec.range(sex)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(spec.nameRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (range != null) ZoneTag(zoneFor(v, range.first, range.second))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt(v, spec.decimals), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (spec.unit.isNotEmpty()) Text(" ${spec.unit}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (range != null && spec.domain != null) {
                RangeBar(v, range.first, range.second, spec.domain.first, spec.domain.second)
            } else {
                previous?.let { p -> spec.value(p)?.let { TrendLine(v - it, spec.decimals, spec.unit, spec.betterDown) } }
            }
            Text(stringResource(spec.meaningRes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 2)
        }
    }
}

@Composable
private fun RangeBar(value: Double, lo: Double, hi: Double, dMin: Double, dMax: Double) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val band = ZoneGood.copy(alpha = 0.22f)
    val marker = MaterialTheme.colorScheme.onSurface
    fun frac(x: Double) = ((x - dMin) / (dMax - dMin)).toFloat().coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val h = size.height
        val trackH = h * 0.5f
        val top = (h - trackH) / 2f
        val r = trackH / 2f
        drawRoundRect(track, Offset(0f, top), Size(size.width, trackH), cornerRadius(r))
        val bl = frac(lo) * size.width
        val bw = (frac(hi) - frac(lo)) * size.width
        drawRoundRect(band, Offset(bl, top), Size(bw, trackH), cornerRadius(r))
        val mx = frac(value) * size.width
        drawRoundRect(marker, Offset((mx - 2f).coerceIn(0f, size.width - 4f), 0f), Size(4f, h), cornerRadius(2f))
    }
}

@Composable
private fun TrendLine(delta: Double, decimals: Int, unit: String, betterDown: Boolean?) {
    val arrow = when {
        abs(delta) < 0.05 -> "→"
        delta > 0 -> "▲"
        else -> "▼"
    }
    val color = when {
        betterDown == null || abs(delta) < 0.05 -> MaterialTheme.colorScheme.onSurfaceVariant
        (delta < 0) == betterDown -> ZoneGood
        else -> ZoneHigh
    }
    val sign = if (delta >= 0) "+" else "−"
    Text(
        "$arrow $sign${fmt(abs(delta), decimals)}${if (unit.isNotEmpty()) " $unit" else ""}",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

@Composable
private fun ZoneTag(zone: Zone) {
    Text(
        stringResource(zone.labelRes),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = zone.color,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(zone.color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private data class Zone(val labelRes: Int, val color: Color)

private fun zoneFor(v: Double, lo: Double, hi: Double): Zone = when {
    v < lo -> Zone(R.string.scale_status_low, ZoneLow)
    v > hi -> Zone(R.string.scale_status_high, ZoneHigh)
    else -> Zone(R.string.scale_status_normal, ZoneGood)
}

private fun zoneColorFor(v: Double, lo: Double, hi: Double): Color = when {
    v < lo -> ZoneLow
    v > hi -> ZoneHigh
    else -> ZoneGood
}

// --- Stats --------------------------------------------------------------------------------------

private data class StatMetric(
    val nameRes: Int,
    val unit: String,
    val decimals: Int,
    val betterDown: Boolean,
    val value: (BodyMetrics) -> Double?,
)

private val STAT_METRICS = listOf(
    StatMetric(R.string.scale_weight_label, "kg", 1, true) { it.weightKg },
    StatMetric(R.string.scale_m_fat, "%", 1, true) { it.bodyFatPct },
    StatMetric(R.string.scale_m_muscle, "kg", 1, false) { it.muscleMassKg },
    StatMetric(R.string.scale_m_water, "%", 1, false) { it.waterPct },
    StatMetric(R.string.scale_m_bmi, "", 1, true) { it.bmi },
)

@Composable
private fun StatsTab(weighIns: List<WeighInEntity>) {
    if (weighIns.size < 2) {
        EmptyHint(R.string.scale_stats_empty)
        return
    }
    val entries = remember(weighIns) { weighIns.sortedBy { it.timestamp } }
    val metricsSeq = remember(entries) { entries.map { it.metrics() } }
    // Each stat metric with the values it actually has (composition metrics skip weight-only reads).
    val available = remember(metricsSeq) {
        STAT_METRICS.mapNotNull { sm ->
            val series = metricsSeq.mapNotNull { sm.value(it)?.toFloat() }
            if (series.size >= 2) sm to series else null
        }
    }
    if (available.isEmpty()) {
        EmptyHint(R.string.scale_stats_empty)
        return
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val selectedIdx = selected.coerceIn(0, available.size - 1)
    val (sm, series) = available[selectedIdx]

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEachIndexed { i, (m, _) ->
                FilterChip(selectedIdx == i, { selected = i }, label = { Text(stringResource(m.nameRes)) })
            }
        }

        val accent = MaterialTheme.colorScheme.primary
        val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
        val surface = MaterialTheme.colorScheme.surface
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(sm.nameRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(fmt(series.last().toDouble(), sm.decimals), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            if (sm.unit.isNotEmpty()) Text(" ${sm.unit}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
                        }
                    }
                    val diff = (series.last() - series.first()).toDouble()
                    StatDeltaPill(diff, sm.unit, improving = (diff < 0) == sm.betterDown)
                }
                Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                    drawAreaChart(series, accent, grid, surface)
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(formatDayMonthYearShort(entries.first().timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f))
                    Text(formatDayMonthYearShort(entries.last().timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.End)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryCell(R.string.scale_stat_start, series.first().toDouble(), sm.decimals)
            SummaryCell(R.string.scale_stat_min, series.min().toDouble(), sm.decimals)
            SummaryCell(R.string.scale_stat_max, series.max().toDouble(), sm.decimals)
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.scale_stats_trend_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Small multiples: every other available metric at a glance.
        val others = available.filterIndexed { i, _ -> i != selectedIdx }
        if (others.isNotEmpty()) {
            SectionHeader(R.string.scale_all_metrics)
            others.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (m, s) -> Box(Modifier.weight(1f)) { SmallMultiple(m, s) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RowScope.SummaryCell(labelRes: Int, value: Double, decimals: Int) {
    Card(Modifier.weight(1f)) {
        Column(Modifier.padding(11.dp)) {
            Text(stringResource(labelRes).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Text(fmt(value, decimals), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatDeltaPill(diff: Double, unit: String, improving: Boolean) {
    val color = if (improving) ZoneGood else ZoneHigh
    val arrow = if (diff >= 0) "▲" else "▼"
    Text(
        "$arrow ${fmt(abs(diff), 1)}${if (unit.isNotEmpty()) " $unit" else ""}",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun SmallMultiple(sm: StatMetric, series: List<Float>) {
    val diff = (series.last() - series.first()).toDouble()
    val improving = (diff < 0) == sm.betterDown
    val color = if (improving) ZoneGood else ZoneHigh
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(sm.nameRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("${if (diff >= 0) "+" else "−"}${fmt(abs(diff), sm.decimals)}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color)
            }
            Text(fmt(series.last().toDouble(), sm.decimals) + if (sm.unit.isNotEmpty()) " ${sm.unit}" else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                drawSeries(series, size.width, size.height, color, baselineZero = false)
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
    val chronological = remember(weighIns) { weighIns.sortedBy { it.timestamp } }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<WeighInEntity?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        chronological.reversed().forEach { w ->
            val idx = chronological.indexOf(w)
            val prev = chronological.getOrNull(idx - 1)
            val delta = prev?.let { w.weightKg - it.weightKg }
            val expanded = expandedId == w.id
            Card {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { expandedId = if (expanded) null else w.id }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CalBox(w.timestamp)
                        Column(Modifier.weight(1f)) {
                            Text("%.1f kg".format(w.weightKg), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(if (expanded) R.string.scale_section_composition else R.string.scale_tap_breakdown),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        delta?.let {
                            Text(
                                "${if (it <= 0) "▼" else "▲"} ${fmt(abs(it), 1)}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { pendingDelete = w }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.home_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (expanded) {
                        Box(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                            CompositionGrid(w.metrics(), sexOf(w.sex), previous = prev?.metrics(), showEstimate = false)
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
private fun CalBox(timestamp: Long) {
    Column(
        Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(dayOf(timestamp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(monthOf(timestamp).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun EmptyHint(res: Int) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(res), color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
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

private fun dayOf(ts: Long): String =
    java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).dayOfMonth.toString()

private fun monthOf(ts: Long): String =
    java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
        .month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        .trimEnd('.')

private fun errorRes(message: String): Int = when (message) {
    "no-permission" -> R.string.scale_err_permission
    "bluetooth-off" -> R.string.scale_err_bluetooth
    "unknown-scale" -> R.string.scale_err_unknown
    else -> R.string.scale_err_generic
}

/** Line + gradient-filled area with a faint grid and an emphasized endpoint. */
private fun DrawScope.drawAreaChart(values: List<Float>, accent: Color, grid: Color, surface: Color) {
    if (values.size < 2) return
    val w = size.width
    val h = size.height
    val pad = 8f
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0.0001f } ?: 1f
    fun x(i: Int) = pad + i.toFloat() / (values.size - 1) * (w - 2 * pad)
    fun y(v: Float) = pad + (1f - (v - min) / range) * (h - 2 * pad)
    for (i in 0..3) {
        val gy = pad + i / 3f * (h - 2 * pad)
        drawLine(grid, Offset(pad, gy), Offset(w - pad, gy), strokeWidth = 1f)
    }
    val line = Path().apply {
        moveTo(x(0), y(values[0]))
        for (i in 1 until values.size) lineTo(x(i), y(values[i]))
    }
    val area = Path().apply {
        addPath(line)
        lineTo(x(values.size - 1), h - pad)
        lineTo(x(0), h - pad)
        close()
    }
    drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0f)), startY = pad, endY = h))
    drawPath(line, accent, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    val end = Offset(x(values.size - 1), y(values.last()))
    drawCircle(surface, radius = 5.5f, center = end)
    drawCircle(accent, radius = 4f, center = end)
}

private fun cornerRadius(r: Float) = androidx.compose.ui.geometry.CornerRadius(r, r)

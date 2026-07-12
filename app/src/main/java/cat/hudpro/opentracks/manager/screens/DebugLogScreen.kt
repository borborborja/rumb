package cat.hudpro.opentracks.manager.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import cat.hudpro.opentracks.data.debug.DebugLog
import java.io.File

@Composable
fun DebugLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var onlyApp by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("Carregant…") }

    fun reload() {
        val dump = DebugLog.logcatDump()
        text = if (onlyApp) {
            dump.lineSequence().filter { it.contains("HUDpro") }.joinToString("\n").ifBlank { "(cap línia de HUD Pro)" }
        } else {
            dump
        }
    }
    LaunchedEffect(onlyApp) { reload() }

    DetailScaffold(
        title = "Registre de debug",
        onBack = onBack,
        actions = {
            IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Actualitzar") }
            IconButton(onClick = {
                runCatching {
                    val dir = File(context.cacheDir, "debug").apply { mkdirs() }
                    val file = File(dir, "hudpro-log.txt")
                    file.writeText(text)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "HUD Pro · registre de debug")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(share, "Compartir registre"))
                }
            }) { Icon(Icons.Filled.Share, contentDescription = "Compartir") }
            IconButton(onClick = { DebugLog.clear(); reload() }) { Icon(Icons.Filled.Delete, contentDescription = "Netejar") }
        },
    ) { modifier ->
        Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(onlyApp, { onlyApp = !onlyApp }, label = { Text("Només HUD Pro") })
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copiar") }
                Text("${text.count { it == '\n' } + 1} línies", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                softWrap = false,
            )
        }
    }
}

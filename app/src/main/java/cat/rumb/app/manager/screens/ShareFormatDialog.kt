package cat.rumb.app.manager.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.data.gpx.ExportFormat

/** Lets the user pick an export/share format; [onPick] receives the choice, then dismisses. */
@Composable
fun ShareFormatDialog(onPick: (ExportFormat) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        ExportFormat.AUTO to R.string.share_format_auto,
        ExportFormat.GPX to R.string.share_format_gpx,
        ExportFormat.TCX to R.string.share_format_tcx,
        ExportFormat.KML to R.string.share_format_kml,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_format_title)) },
        text = {
            Column {
                options.forEach { (format, labelRes) ->
                    Text(
                        stringResource(labelRes),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(format) }
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.routes_cancel)) } },
    )
}

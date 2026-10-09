package cat.rumb.app.manager.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cat.rumb.app.BuildConfig
import cat.rumb.app.R

/** Store builds receive all binary updates through Google Play. */
@Composable
internal fun AppUpdateSection() {
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_app_installed_version), style = MaterialTheme.typography.labelMedium)
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = {
                context.startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/borborborja/rumb/blob/main/PRIVACY.md"),
                ))
            }) { Text(stringResource(R.string.settings_privacy_policy)) }
        }
    }
}

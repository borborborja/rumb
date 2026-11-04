package cat.rumb.app.manager.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cat.rumb.app.R
import cat.rumb.app.data.premium.PremiumManager

@Composable
fun PremiumScreen(@Suppress("UNUSED_PARAMETER") manager: PremiumManager, onBack: () -> Unit) {
    DetailScaffold(title = stringResource(R.string.premium_title), onBack = onBack) { modifier ->
        Text(stringResource(R.string.premium_github), modifier.padding(16.dp))
    }
}

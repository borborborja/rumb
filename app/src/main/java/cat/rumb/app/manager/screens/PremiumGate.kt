package cat.rumb.app.manager.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.rumb.app.R
import cat.rumb.app.RumbApplication
import cat.rumb.app.data.premium.PremiumFeature
import cat.rumb.app.data.premium.PremiumFeaturePolicy

/** Guard the destination itself, including direct intents and restored navigation state. */
@Composable
fun PremiumGate(feature: PremiumFeature, onBack: () -> Unit, content: @Composable () -> Unit) {
    val manager = RumbApplication.from(LocalContext.current).premiumManager
    val state by manager.state.collectAsStateWithLifecycle()
    if (PremiumFeaturePolicy.allows(feature, state.hasPremium)) content()
    else PremiumScreen(manager = manager, onBack = onBack)
}

/** A compact upgrade entry for a premium section on an otherwise free screen. */
@Composable
fun PremiumPrompt(onUpgrade: () -> Unit) {
    Column {
        Text(stringResource(R.string.premium_required))
        OutlinedButton(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.premium_title))
        }
    }
}

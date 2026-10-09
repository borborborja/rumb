package cat.rumb.app.manager.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.rumb.app.R
import cat.rumb.app.data.premium.PremiumManager
import cat.rumb.app.data.premium.PremiumNotice
import cat.rumb.app.data.premium.PremiumPlan

@Composable
fun PremiumScreen(manager: PremiumManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by manager.state.collectAsStateWithLifecycle()
    var accessCode by remember { mutableStateOf("") }
    var invalidCode by remember { mutableStateOf(false) }
    LaunchedEffect(manager) { manager.refresh() }
    DetailScaffold(title = stringResource(R.string.premium_title), onBack = onBack) { modifier ->
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.premium_free), style = MaterialTheme.typography.bodyLarge)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.premium_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.premium_features))
                }
            }
            if (state.subscriptionActive) {
                Text(stringResource(R.string.premium_active), color = MaterialTheme.colorScheme.primary)
            } else {
                state.plans.forEach { offer ->
                    Button(
                        onClick = { context.findActivity()?.let { manager.launchPurchase(it, offer.plan) } },
                        enabled = !state.isPurchasing && context.findActivity() != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(
                            if (offer.plan == PremiumPlan.MONTHLY) R.string.premium_monthly_price
                            else R.string.premium_annual_price,
                            offer.formattedPrice,
                        ))
                    }
                }
                Text(stringResource(R.string.premium_renewal), style = MaterialTheme.typography.bodySmall)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.premium_demo), style = MaterialTheme.typography.titleMedium)
                    if (state.accessCodeActive) {
                        Text(stringResource(R.string.premium_demo_active))
                        OutlinedButton(onClick = { manager.revokeAccessCode() }) {
                            Text(stringResource(R.string.premium_demo_remove))
                        }
                    } else {
                        OutlinedTextField(
                            value = accessCode,
                            onValueChange = { accessCode = it; invalidCode = false },
                            label = { Text(stringResource(R.string.premium_demo_code)) },
                            singleLine = true,
                            isError = invalidCode,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (invalidCode) Text(
                            stringResource(R.string.premium_demo_invalid),
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = {
                            val accepted = manager.redeemAccessCode(accessCode)
                            invalidCode = !accepted
                            if (accepted) accessCode = ""
                        }, enabled = accessCode.isNotBlank()) {
                            Text(stringResource(R.string.premium_demo_activate))
                        }
                    }
                }
            }
            if (state.isLoading || state.isPurchasing) {
                CircularProgressIndicator()
                Text(stringResource(R.string.premium_loading))
            }
            state.notice?.let { Text(stringResource(noticeResource(it))) }
            Text(stringResource(R.string.premium_connection), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { manager.refresh(restore = true) }, enabled = !state.isLoading) {
                Text(stringResource(R.string.premium_restore))
            }
            OutlinedButton(onClick = { manager.openSubscriptions(context) }) {
                Text(stringResource(R.string.premium_manage))
            }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://github.com/borborborja/rumb/blob/main/PRIVACY.md",
                )))
            }) {
                Text(stringResource(R.string.premium_privacy))
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun noticeResource(notice: PremiumNotice): Int = when (notice) {
    PremiumNotice.STORE_UNAVAILABLE -> R.string.premium_store_unavailable
    PremiumNotice.PRICES_UNAVAILABLE -> R.string.premium_prices_unavailable
    PremiumNotice.PURCHASE_CANCELED -> R.string.premium_canceled
    PremiumNotice.PURCHASE_PENDING -> R.string.premium_pending
    PremiumNotice.VERIFICATION_FAILED -> R.string.premium_verification_failed
    PremiumNotice.SUBSCRIPTION_SUSPENDED -> R.string.premium_suspended
    PremiumNotice.ACKNOWLEDGEMENT_RETRY -> R.string.premium_ack_retry
    PremiumNotice.RESTORED -> R.string.premium_restored
    PremiumNotice.NO_SUBSCRIPTION -> R.string.premium_none
    PremiumNotice.PURCHASE_FAILED -> R.string.premium_purchase_failed
}

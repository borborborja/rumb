package cat.rumb.app.manager.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cat.rumb.app.BuildConfig
import cat.rumb.app.R
import cat.rumb.app.data.prefs.ViewerPreferences
import cat.rumb.app.data.update.ApkDownloadWorker
import cat.rumb.app.data.update.ApkInstaller
import cat.rumb.app.data.update.UpdateInfo
import cat.rumb.app.data.update.UpdateRepository
import kotlinx.coroutines.launch

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

@Composable
internal fun AppUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { UpdateRepository() }
    val requestNotif = rememberNotificationPermission()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    // The download runs in a foreground worker, so it survives leaving this screen and the screen
    // going off. Observe it instead of owning it: re-entering settings re-attaches to it.
    val downloadInfos by androidx.work.WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(ApkDownloadWorker.WORK_NAME)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val download = downloadInfos.lastOrNull()
    val downloading = download?.state == androidx.work.WorkInfo.State.RUNNING ||
        download?.state == androidx.work.WorkInfo.State.ENQUEUED
    val progress = (download?.progress?.getInt(ApkDownloadWorker.KEY_PROGRESS, 0) ?: 0) / 100f

    // Hand the finished APK to the system installer once per completed download. The marker MUST be
    // persisted: WorkManager keeps a SUCCEEDED work around, so a remember-scoped flag reset on every
    // navigation and re-launched the installer for an old download each time this screen was opened.
    val prefs = remember { ViewerPreferences.get(context) }
    LaunchedEffect(download?.id, download?.state) {
        val d = download ?: return@LaunchedEffect
        if (d.state != androidx.work.WorkInfo.State.SUCCEEDED) return@LaunchedEffect
        val workId = d.id.toString()
        if (prefs.lastInstalledDownloadId == workId) return@LaunchedEffect
        val path = d.outputData.getString(ApkDownloadWorker.KEY_PATH) ?: return@LaunchedEffect
        prefs.lastInstalledDownloadId = workId
        ApkInstaller.install(context, java.io.File(path))
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_app_installed_version), style = MaterialTheme.typography.labelMedium)
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
        }
    }

    val networkError = stringResource(R.string.settings_update_network_error)
    Button(
        onClick = {
            state = UpdateState.Checking
            scope.launch {
                state = try {
                    repo.checkForUpdate()?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
                } catch (e: Exception) {
                    UpdateState.Error(e.message ?: networkError)
                }
            }
        },
        enabled = state !is UpdateState.Checking && !downloading,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_check_update)) }

    if (downloading) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.settings_update_downloading, (progress * 100).toInt()))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.settings_update_background_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    download?.takeIf { it.state == androidx.work.WorkInfo.State.FAILED }?.let {
        val msg = it.outputData.getString(ApkDownloadWorker.KEY_ERROR)
            ?: stringResource(R.string.settings_update_download_error)
        Text(stringResource(R.string.settings_error, msg), color = MaterialTheme.colorScheme.error)
    }

    when (val s = state) {
        is UpdateState.Checking -> Text(stringResource(R.string.settings_update_checking))
        is UpdateState.UpToDate -> Text(stringResource(R.string.settings_update_up_to_date))
        is UpdateState.Error -> Text(stringResource(R.string.settings_error, s.message), color = MaterialTheme.colorScheme.error)
        is UpdateState.Available -> Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_update_new_version, s.info.version), style = MaterialTheme.typography.titleMedium)
                Text(s.info.changelog, style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        if (!ApkInstaller.canInstall(context)) {
                            ApkInstaller.requestInstallPermission(context)
                            return@Button
                        }
                        requestNotif()
                        ApkDownloadWorker.enqueue(context, s.info.apkUrl)
                    },
                    enabled = !downloading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_update_download_install)) }
            }
        }
        UpdateState.Idle -> {}
    }
}

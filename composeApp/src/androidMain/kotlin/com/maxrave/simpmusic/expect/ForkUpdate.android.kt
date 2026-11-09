package com.maxrave.simpmusic.expect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.update.ForkUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.fork_update_build
import simpmusic.composeapp.generated.resources.fork_update_button
import simpmusic.composeapp.generated.resources.fork_update_downloading
import simpmusic.composeapp.generated.resources.fork_update_failed
import simpmusic.composeapp.generated.resources.fork_update_install_permission
import simpmusic.composeapp.generated.resources.fork_update_message
import simpmusic.composeapp.generated.resources.fork_update_title

private enum class UpdateStep { IDLE, NEEDS_PERMISSION, DOWNLOADING, FAILED }

@Composable
actual fun ForkUpdateScreenIfRequired(): Boolean {
    // Local and branch builds carry no commit and are never forced.
    if (ForkUpdater.currentCommit.isBlank()) return false

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Starts from what an earlier launch already learned, so going offline does not skip it.
    var release by remember { mutableStateOf(ForkUpdater.pending(context)) }
    var step by remember { mutableStateOf(UpdateStep.IDLE) }
    var progress by remember { mutableFloatStateOf(0f) }

    fun startUpdate() {
        val target = release ?: return
        if (!ForkUpdater.canInstall(context)) {
            step = UpdateStep.NEEDS_PERMISSION
            ForkUpdater.openInstallPermissionSettings(context)
            return
        }
        step = UpdateStep.DOWNLOADING
        progress = 0f
        scope.launch {
            try {
                val apk = ForkUpdater.download(context, target) { progress = it }
                ForkUpdater.install(context, apk)
                step = UpdateStep.IDLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w("ForkUpdate", "Update download failed: ${e.message}")
                step = UpdateStep.FAILED
            }
        }
    }

    // Every return to the app asks GitHub again.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        scope.launch { release = ForkUpdater.check(context) }
        // Back from the "install unknown apps" screen with the permission granted: carry on.
        if (step == UpdateStep.NEEDS_PERMISSION && ForkUpdater.canInstall(context)) startUpdate()
    }

    val pending = release ?: return false

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.fork_update_title),
            style = typo().titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.fork_update_message),
            style = typo().bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Text(
            text = stringResource(Res.string.fork_update_build, pending.commit.take(7)),
            style = typo().bodySmall,
            textAlign = TextAlign.Center,
        )
        when (step) {
            UpdateStep.DOWNLOADING -> {
                Text(
                    text = stringResource(Res.string.fork_update_downloading),
                    style = typo().bodySmall,
                    textAlign = TextAlign.Center,
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                )
            }

            UpdateStep.NEEDS_PERMISSION, UpdateStep.FAILED -> {
                Text(
                    text =
                        stringResource(
                            if (step == UpdateStep.FAILED) Res.string.fork_update_failed else Res.string.fork_update_install_permission,
                        ),
                    style = typo().bodySmall,
                    textAlign = TextAlign.Center,
                    color = if (step == UpdateStep.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
            }

            UpdateStep.IDLE -> Unit
        }
        Button(
            onClick = { startUpdate() },
            enabled = step != UpdateStep.DOWNLOADING,
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            // typo() bakes a body colour into its styles; the label must take the button's own.
            Text(
                text = stringResource(Res.string.fork_update_button),
                style = typo().labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
    return true
}

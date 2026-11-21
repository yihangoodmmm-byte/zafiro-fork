package com.niki914.zafiro.app.ui.devtools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.uikit.button.ZafiroTonalButton
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

private enum class ArtifactSlot { BASE, TARGET }

private sealed interface ArtifactSelection {
    data object Empty : ArtifactSelection
    data object Loading : ArtifactSelection
    data class Error(@param:StringRes val message: Int) : ArtifactSelection
    data class Ready(val name: String?, val data: ArtifactMetadata) : ArtifactSelection
}

private data class ArtifactState(
    val base: ArtifactSelection = ArtifactSelection.Empty,
    val target: ArtifactSelection = ArtifactSelection.Empty,
    val diff: ArtifactDiff? = null,
) {
    val loading: Boolean get() = base == ArtifactSelection.Loading || target == ArtifactSelection.Loading

    fun withSelection(slot: ArtifactSlot, value: ArtifactSelection): ArtifactState {
        val next = when (slot) {
            ArtifactSlot.BASE -> copy(base = value)
            ArtifactSlot.TARGET -> copy(target = value)
        }
        val before = (next.base as? ArtifactSelection.Ready)?.data
        val after = (next.target as? ArtifactSelection.Ready)?.data
        return next.copy(diff = if (before != null && after != null) artifactCompare(before, after) else null)
    }
}

private sealed interface ArtifactIntent {
    data class Selected(val slot: ArtifactSlot, val appContext: Context, val uri: Uri) : ArtifactIntent
    data class MissingPicker(val slot: ArtifactSlot) : ArtifactIntent
}

private class ArtifactViewModel : ComposeMVIViewModel<ArtifactIntent, ArtifactState, Nothing>() {
    override fun initUiState() = ArtifactState()

    override suspend fun handleIntent(intent: ArtifactIntent) {
        when (intent) {
            is ArtifactIntent.MissingPicker -> updateState {
                withSelection(intent.slot, ArtifactSelection.Error(R.string.artifact_panel_no_picker))
            }
            is ArtifactIntent.Selected -> {
                updateState { withSelection(intent.slot, ArtifactSelection.Loading) }
                val result = withContext(Dispatchers.IO) {
                    val scope = this
                    try {
                        runInterruptible { artifactReadSelection(intent.appContext, intent.uri) { scope.ensureActive() } }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ArtifactReadException) {
                        ArtifactSelection.Error(when (e.issue) {
                            ArtifactIssue.FORMAT -> R.string.artifact_panel_format_error
                            ArtifactIssue.LIMIT -> R.string.artifact_panel_limit_error
                        })
                    } catch (_: ZipException) {
                        ArtifactSelection.Error(R.string.artifact_panel_format_error)
                    } catch (_: IOException) {
                        ArtifactSelection.Error(R.string.artifact_panel_read_error)
                    } catch (_: SecurityException) {
                        ArtifactSelection.Error(R.string.artifact_panel_read_error)
                    } catch (_: IllegalArgumentException) {
                        ArtifactSelection.Error(R.string.artifact_panel_read_error)
                    }
                }
                updateState { withSelection(intent.slot, result) }
            }
        }
    }
}

@Composable
internal fun ArtifactPanel() {
    val model = pageViewModel<ArtifactViewModel>(factory = artifactViewModelFactory)
    val state by model.uiStateFlow.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val basePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.sendIntent(ArtifactIntent.Selected(ArtifactSlot.BASE, appContext, uri))
    }
    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.sendIntent(ArtifactIntent.Selected(ArtifactSlot.TARGET, appContext, uri))
    }
    val before = (state.base as? ArtifactSelection.Ready)?.data
    val after = (state.target as? ArtifactSelection.Ready)?.data
    SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            item {
                ArtifactSlotPicker(R.string.artifact_panel_choose_base, state.base, !state.loading) {
                    try {
                        basePicker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        model.sendIntent(ArtifactIntent.MissingPicker(ArtifactSlot.BASE))
                    }
                }
            }
            item {
                ArtifactSlotPicker(R.string.artifact_panel_choose_target, state.target, !state.loading) {
                    try {
                        targetPicker.launch(arrayOf("*/*"))
                    } catch (_: ActivityNotFoundException) {
                        model.sendIntent(ArtifactIntent.MissingPicker(ArtifactSlot.TARGET))
                    }
                }
            }
            val diff = state.diff
            if (diff != null && before != null && after != null) {
                item {
                    HorizontalDivider()
                    val delta = if (diff.sizeDelta > 0) "+" + diff.sizeDelta else diff.sizeDelta.toString()
                    Text(stringResource(R.string.artifact_panel_delta, delta), style = MaterialTheme.typography.titleMedium)
                }
                if (before.manifest?.packageName != after.manifest?.packageName) {
                    item { Text(stringResource(R.string.artifact_panel_package_changed)) }
                }
                if (before.kind == ArtifactKind.APK) {
                    artifactSection(R.string.artifact_panel_permissions, diff.permissions) { it.artifactDisplay() }
                    artifactSection(R.string.artifact_panel_components, diff.components) {
                        stringResource(it.kind.artifactLabel(), it.name.artifactDisplay())
                    }
                }
                artifactSection(R.string.artifact_panel_abis, diff.abis) { it }
                artifactSection(R.string.artifact_panel_dependencies, diff.dependencies) {
                    val library = it.library
                    if (library == null) it.name.artifactDisplay()
                    else stringResource(R.string.artifact_panel_library_dependency, library.artifactDisplay(), it.name.artifactDisplay())
                }
            } else if (before != null && after != null) {
                item { Text(stringResource(R.string.artifact_panel_mismatch), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private val artifactViewModelFactory = viewModelFactory {
    initializer { ArtifactViewModel() }
}

@Composable
private fun ArtifactSlotPicker(
    @StringRes label: Int,
    selection: ArtifactSelection,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DisableSelection {
            ZafiroTonalButton(text = stringResource(label), enabled = enabled, onClick = onClick)
        }
        when (selection) {
            ArtifactSelection.Empty -> Unit
            ArtifactSelection.Loading -> Text(stringResource(R.string.artifact_panel_reading))
            is ArtifactSelection.Error -> Text(stringResource(selection.message), color = MaterialTheme.colorScheme.error)
            is ArtifactSelection.Ready -> {
                val data = selection.data
                Text(
                    selection.name ?: stringResource(R.string.artifact_panel_unnamed),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(
                    if (data.kind == ArtifactKind.APK) R.string.artifact_panel_apk_bytes else R.string.artifact_panel_so_bytes,
                    data.bytes,
                ))
                data.manifest?.let { Text(it.packageName.artifactDisplay()) }
            }
        }
    }
}

private fun <T> LazyListScope.artifactSection(
    @StringRes title: Int,
    changes: ArtifactChanges<T>,
    label: @Composable (T) -> String,
) {
    item {
        HorizontalDivider()
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.artifact_panel_change_count, changes.added.size, changes.removed.size))
    }
    items(changes.added) { value ->
        Text(stringResource(R.string.artifact_panel_added, label(value)), style = MaterialTheme.typography.bodySmall)
    }
    items(changes.removed) { value ->
        Text(stringResource(R.string.artifact_panel_removed, label(value)), style = MaterialTheme.typography.bodySmall)
    }
}

@StringRes
private fun ArtifactComponentKind.artifactLabel() = when (this) {
    ArtifactComponentKind.ACTIVITY -> R.string.artifact_panel_activity
    ArtifactComponentKind.SERVICE -> R.string.artifact_panel_service
    ArtifactComponentKind.RECEIVER -> R.string.artifact_panel_receiver
    ArtifactComponentKind.PROVIDER -> R.string.artifact_panel_provider
}

private fun String.artifactDisplay(): String = map {
    if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) '\uFFFD' else it
}.joinToString("")

private fun artifactReadSelection(context: Context, uri: Uri, checkpoint: () -> Unit): ArtifactSelection.Ready {
    if (uri.scheme != "content") throw IOException()
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.take(160)?.artifactDisplay() else null
    }
    val temp = File.createTempFile("artifact-panel-", ".apk", context.cacheDir)
    try {
        (resolver.openInputStream(uri) ?: throw IOException()).buffered().use { input ->
            input.mark(4)
            val first = input.read()
            val second = input.read()
            val third = input.read()
            val fourth = input.read()
            val limit = when {
                first == 0x7f && second == 0x45 && third == 0x4c && fourth == 0x46 -> ARTIFACT_MAX_SO_BYTES.toLong()
                first == 0x50 && second == 0x4b && third == 3 && fourth == 4 -> ARTIFACT_MAX_BYTES
                else -> throw ArtifactReadException(ArtifactIssue.FORMAT)
            }
            input.reset()
            temp.outputStream().buffered().use { output ->
                artifactCopyBounded(input, output, limit, checkpoint)
            }
        }
        val data = artifactRead(temp, checkpoint) { artifactReadManifest(context.packageManager, it) }
        return ArtifactSelection.Ready(name, data)
    } finally {
        temp.delete()
    }
}

@Suppress("DEPRECATION")
private fun artifactReadManifest(pm: PackageManager, file: File): ArtifactManifest {
    val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
        PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
        PackageManager.MATCH_DISABLED_COMPONENTS
    val info = pm.getPackageArchiveInfo(file.path, flags) ?: throw ArtifactReadException(ArtifactIssue.FORMAT)
    return ArtifactManifest(
        packageName = info.packageName,
        permissions = info.requestedPermissions.orEmpty().toSet(),
        components = buildSet {
            info.activities.orEmpty().forEach { add(ArtifactComponent(ArtifactComponentKind.ACTIVITY, it.name)) }
            info.services.orEmpty().forEach { add(ArtifactComponent(ArtifactComponentKind.SERVICE, it.name)) }
            info.receivers.orEmpty().forEach { add(ArtifactComponent(ArtifactComponentKind.RECEIVER, it.name)) }
            info.providers.orEmpty().forEach { add(ArtifactComponent(ArtifactComponentKind.PROVIDER, it.name)) }
        },
    )
}

package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import com.niki914.uikit.infra.LiquidDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.component.SettingNavigationItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ConfigureIntent
import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ConfigureViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailChrome

/**
 * Model Configuration 一级页：System Prompt 卡片（点击进整页编辑）+ Saved Configuration 列表。
 * 编辑/新建走 SavedConfigDetailPage 二级页，Prompt 编辑走 PromptEditPage 二级页。
 */
@Composable
fun ModelConfigSettingsContent(
    onBack: () -> Unit,
    onOpenProviderPick: () -> Unit,
    onOpenConfigDetail: (configId: String, configName: String) -> Unit,
    onOpenPromptEdit: () -> Unit,
) {
    val viewModel = pageViewModel<ConfigureViewModel>(
        key = "settings-configure",
    )
    val uiState by viewModel.uiStateFlow.collectAsState()
    var pendingDeleteConfigId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                scene = ConfigureScene.SettingsEdit,
            ),
        )
    }

    EditableSettingsDetailChrome(
        isCreating = false,
        hasUnsavedChanges = { false },
        onDiscardChanges = onBack,
        rightAction = TopBarActionSpec(
            icon = Icons.Default.Add,
            contentDescription = "Add Button",
            onClick = onOpenProviderPick,
        ),
    ) {
        SettingsListPageContent {
            SettingsGroupCard {
                SettingNavigationItem(
                    title = stringResource(R.string.ui_settings_configure_prompt_label),
                    summary = null,
                    onClick = onOpenPromptEdit,
                )
            }

            val source = uiState.savedConfigs.firstOrNull { it.id == uiState.activeConfigId }
                ?: uiState.savedConfigs.firstOrNull()
            if (source != null) {
                SettingsGroupCard {
                    SettingNavigationItem(
                        title = stringResource(R.string.chat_role_create),
                        summary = stringResource(R.string.chat_role_source, source.name),
                        onClick = { viewModel.sendIntent(ConfigureIntent.StartRole(source.id)) },
                    )
                }
            }

            SavedConfigurationBlock(
                configs = uiState.savedConfigs.map { summary ->
                    summary.copy(isActive = summary.id == uiState.activeConfigId)
                },
                onEditClick = { configId ->
                    val summary = uiState.savedConfigs.firstOrNull { it.id == configId }
                    if (summary != null) {
                        onOpenConfigDetail(configId, summary.name)
                    }
                },
                onActivateClick = { configId ->
                    viewModel.sendIntent(ConfigureIntent.ActivateConfig(configId))
                },
                onDeleteRequest = { configId ->
                    pendingDeleteConfigId = configId
                },
            )
        }
    }

    LiquidDialog(
        visible = uiState.roleSourceId != null,
        onDismissRequest = { viewModel.sendIntent(ConfigureIntent.CancelRole) },
        title = { Text(stringResource(R.string.chat_role_create)) },
        content = {
            OutlinedTextField(
                value = uiState.roleName,
                onValueChange = { viewModel.sendIntent(ConfigureIntent.UpdateRoleName(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.chat_role_name)) },
                enabled = !uiState.roleSaving,
                singleLine = true,
                isError = uiState.roleSaveFailed,
                supportingText = if (uiState.roleSaveFailed) {
                    { Text(stringResource(R.string.chat_role_save_failed)) }
                } else null,
            )
        },
        actions = {
            TextButton(enabled = !uiState.roleSaving, onClick = { viewModel.sendIntent(ConfigureIntent.CancelRole) }) {
                Text(stringResource(R.string.dialog_cancel))
            }
            TextButton(
                enabled = !uiState.roleSaving && uiState.roleName.isNotBlank(),
                onClick = { viewModel.sendIntent(ConfigureIntent.SaveRole) },
            ) { Text(stringResource(R.string.skill_save_action)) }
        },
    )

    ConfirmationLiquidDialog(
        visible = pendingDeleteConfigId != null,
        onDismissRequest = { pendingDeleteConfigId = null },
        title = stringResource(R.string.ui_settings_saved_configuration_delete_title),
        text = stringResource(R.string.ui_settings_saved_configuration_delete_text),
        negativeButtonText = stringResource(R.string.dialog_cancel),
        positiveButtonText = stringResource(R.string.dialog_confirm_delete),
        onNegativeClick = { pendingDeleteConfigId = null },
        onPositiveClick = {
            pendingDeleteConfigId?.let { configId ->
                viewModel.sendIntent(ConfigureIntent.DeleteConfig(configId))
            }
            pendingDeleteConfigId = null
        },
    )
}

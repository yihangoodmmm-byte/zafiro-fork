package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.okia.message.ThinkingLevel
import com.niki914.uikit.infra.component.SettingExpandableSliderItem
import com.niki914.uikit.infra.component.SettingNavigationItem
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ConfigureUiState
import com.niki914.zafiro.app.ui.settings.form.EditableDetailFieldController
import com.niki914.zafiro.app.ui.settings.form.SettingControlledExpandableTextItem
import com.niki914.zafiro.settings.model.LlmProtocol
import kotlin.math.roundToInt

@Composable
internal fun ConfigureIdentitySettingsBlock(
    uiState: ConfigureUiState,
    showNameField: Boolean,
    fieldController: EditableDetailFieldController<ConfigureEditableField>,
    onNameChange: (String) -> Unit,
    onProxyChange: (String) -> Unit,
    onPromptClick: (() -> Unit)? = null,
) {
    SettingsGroupCard {
        if (showNameField) {
            SettingControlledExpandableTextItem(
                field = ConfigureEditableField.Name,
                controller = fieldController,
                title = stringResource(R.string.ui_settings_configure_name_label),
                value = uiState.configNameInput,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.ui_settings_configure_name_placeholder),
                description = uiState.nameErrorResId?.let { stringResource(it) },
                enabled = !uiState.isSaving,
                minLines = 1,
                maxLines = 1,
            )
            SettingsItemDivider()
            if (onPromptClick != null) {
                SettingNavigationItem(
                    title = stringResource(R.string.ui_settings_configure_prompt_label),
                    currentState = if (uiState.promptInput.isNotBlank()) {
                        stringResource(R.string.ui_settings_configure_prompt_state_custom)
                    } else {
                        stringResource(R.string.ui_settings_configure_prompt_state_global)
                    },
                    enabled = !uiState.isSaving,
                    onClick = onPromptClick,
                )
                SettingsItemDivider()
            }
        }
        SettingControlledExpandableTextItem(
            field = ConfigureEditableField.Proxy,
            controller = fieldController,
            title = stringResource(R.string.ui_settings_configure_proxy_label),
            value = uiState.proxyInput,
            onValueChange = onProxyChange,
            placeholder = stringResource(R.string.ui_settings_configure_proxy_placeholder),
            description = uiState.proxyErrorResId?.let { stringResource(it) },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
    }
}

@Composable
internal fun ConfigureConnectionSettingsBlock(
    uiState: ConfigureUiState,
    policy: ConfigurePagePolicy,
    fieldController: EditableDetailFieldController<ConfigureEditableField>,
    onEndpointOverrideChange: (Boolean) -> Unit,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onToggleApiKeyVisibility: () -> Unit,
    onShowModelCatalogSheet: () -> Unit = {},
) {
    val onClearActiveField = fieldController.clearActiveField
    SettingsGroupCard {
        SettingControlledExpandableTextItem(
            field = ConfigureEditableField.ApiKey,
            controller = fieldController,
            title = stringResource(R.string.ui_onboard_configure_api_key_label),
            value = uiState.apiKeyInput,
            onValueChange = onApiKeyChange,
            placeholder = stringResource(R.string.ui_onboard_configure_api_key_placeholder),
            description = uiState.apiKeyErrorResId?.let { stringResource(it) },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
            secretVisible = uiState.apiKeyVisible,
            onToggleSecretVisibility = onToggleApiKeyVisibility,
            toggleSecretVisibleContentDescription = stringResource(
                R.string.ui_onboard_configure_api_key_show,
            ),
            toggleSecretHiddenContentDescription = stringResource(
                R.string.ui_onboard_configure_api_key_hide,
            ),
        )
        SettingsItemDivider()
        SettingControlledExpandableTextItem(
            field = ConfigureEditableField.Model,
            controller = fieldController,
            title = stringResource(R.string.ui_onboard_configure_model_label),
            value = uiState.modelInput,
            onValueChange = onModelChange,
            placeholder = stringResource(R.string.ui_onboard_configure_model_placeholder),
            description = uiState.modelErrorResId?.let { stringResource(it) }
                ?: if (uiState.modelInput.isBlank()) {
                    stringResource(
                        R.string.ui_onboard_configure_model_example,
                        uiState.providerSpec.exampleModelId,
                    )
                } else {
                    null
                },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 8,
        )
        SettingsItemDivider()
        SettingNavigationItem(
            title = stringResource(R.string.catalog_fetch_models),
            onClick = {
                if (!uiState.isSaving) {
                    onClearActiveField()
                    onShowModelCatalogSheet()
                }
            },
        )
        if (policy.showEndpointSection) {
            SettingsItemDivider()
            val endpointEditable = policy.endpointEditable &&
                    (!policy.showEndpointOverrideToggle || uiState.endpointOverrideEnabled)
            SettingControlledExpandableTextItem(
                field = ConfigureEditableField.Endpoint,
                controller = fieldController,
                title = stringResource(R.string.ui_onboard_configure_endpoint_label),
                value = uiState.endpointInput,
                onValueChange = onEndpointChange,
                placeholder = stringResource(R.string.ui_onboard_configure_endpoint_placeholder),
                description = uiState.endpointErrorResId?.let { stringResource(it) },
                enabled = endpointEditable && !uiState.isSaving,
                minLines = 3,
                maxLines = 6,
            )
            if (policy.showEndpointOverrideToggle) {
                SettingsItemDivider()
                SettingToggleItem(
                    title = stringResource(R.string.ui_onboard_configure_endpoint_override_title),
                    description = stringResource(
                        if (uiState.endpointOverrideEnabled) {
                            R.string.ui_onboard_configure_endpoint_override_description_on
                        } else {
                            R.string.ui_onboard_configure_endpoint_override_description_off
                        },
                    ),
                    checked = uiState.endpointOverrideEnabled,
                    enabled = !uiState.isSaving,
                    onCheckedChange = { enabled ->
                        if (!uiState.isSaving) {
                            onClearActiveField()
                            onEndpointOverrideChange(enabled)
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun ConfigureProtocolSettingsBlock(
    uiState: ConfigureUiState,
    fieldController: EditableDetailFieldController<ConfigureEditableField>,
    onSupportsImagesChange: (Boolean) -> Unit,
    onProtocolSelected: (String) -> Unit,
    onThinkingLevelSelected: (String) -> Unit,
    onMaxTokensChange: (String) -> Unit,
    onContextWindowChange: (String) -> Unit,
) {
    val onClearActiveField = fieldController.clearActiveField
    var showProtocolDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupCard {
        SettingToggleItem(
            title = stringResource(R.string.ui_settings_configure_vision_label),
            checked = uiState.supportsImages,
            enabled = !uiState.isSaving,
            onCheckedChange = { enabled ->
                if (!uiState.isSaving) {
                    onClearActiveField()
                    onSupportsImagesChange(enabled)
                }
            },
        )
        SettingsItemDivider()
        // 协议行：Value Row（点击弹选择弹窗）
        SettingsListItem(
            title = stringResource(R.string.ui_settings_configure_protocol_label),
            currentState = uiState.protocolWireId,
            showChevron = true,
            onClick = {
                onClearActiveField()
                showProtocolDialog = true
            },
        )
        SettingsItemDivider()
        // 思考强度：可折叠滑块（有极 8 档）
        val thinkingOptions = thinkingLevelOptions()
        val currentLevelIndex = thinkingOptions.indexOfFirst { it.wire == uiState.thinkingLevelWire }
            .takeIf { it >= 0 } ?: 0
        SettingExpandableSliderItem(
            title = stringResource(R.string.ui_settings_configure_thinking_label),
            summary = stringResource(R.string.ui_settings_configure_thinking_hint),
            value = currentLevelIndex.toFloat(),
            onValueChange = { rawIndex ->
                val idx = rawIndex.roundToInt().coerceIn(0, thinkingOptions.lastIndex)
                onThinkingLevelSelected(thinkingOptions[idx].wire)
            },
            valueRange = 0f..thinkingOptions.lastIndex.toFloat(),
            steps = thinkingOptions.size - 1,
            valueLabel = thinkingOptions[currentLevelIndex].label,
            startLabel = stringResource(R.string.ui_settings_configure_thinking_start_label),
            endLabel = stringResource(R.string.ui_settings_configure_thinking_end_label),
            enabled = !uiState.isSaving,
            expanded = fieldController.expandedField == ConfigureEditableField.Thinking,
            onExpandedChange = { expanded ->
                fieldController.onExpandedFieldChange(
                    if (expanded) ConfigureEditableField.Thinking else null
                )
            },
        )
        SettingsItemDivider()
        // Max Tokens：留空 = 云端优先，其次默认值；服务端报输出超限时调小
        SettingControlledExpandableTextItem(
            field = ConfigureEditableField.MaxTokens,
            controller = fieldController,
            title = stringResource(R.string.ui_settings_configure_max_tokens_label),
            value = uiState.maxTokensInput,
            onValueChange = onMaxTokensChange,
            placeholder = stringResource(R.string.ui_settings_configure_max_tokens_placeholder),
            description = uiState.maxTokensErrorResId?.let { stringResource(it) },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
        SettingsItemDivider()
        // 上下文窗口：环的分母；留空由 gateway 兜底。
        SettingControlledExpandableTextItem(
            field = ConfigureEditableField.ContextWindow,
            controller = fieldController,
            title = stringResource(R.string.ui_settings_configure_context_window_label),
            value = uiState.contextWindowInput,
            onValueChange = onContextWindowChange,
            placeholder = stringResource(R.string.ui_settings_configure_context_window_placeholder),
            description = uiState.contextWindowErrorResId?.let { stringResource(it) },
            enabled = !uiState.isSaving,
            minLines = 1,
            maxLines = 1,
        )
    }

    // "deepseek" 协议与 openai-chat-completions 同壳，仅作存量存储值兼容，不再提供新选
    val selectableProtocols = LlmProtocol.entries.filter { it != LlmProtocol.DeepSeek }
    SingleChoiceLiquidDialog(
        visible = showProtocolDialog,
        onDismissRequest = { showProtocolDialog = false },
        title = stringResource(R.string.ui_settings_configure_protocol_label),
        hint = stringResource(R.string.ui_settings_configure_protocol_hint),
        options = selectableProtocols,
        selectedId = uiState.protocolWireId,
        optionId = LlmProtocol::wireId,
        optionLabel = LlmProtocol::wireId,
        onSelect = { protocol ->
            showProtocolDialog = false
            onProtocolSelected(protocol.wireId)
        },
    )
}

/** 思考强度可选项：「Provider 默认」（空串 = 不发字段）+ 全部 ThinkingLevel。 */
private data class ThinkingLevelOption(val wire: String, val label: String)

private fun thinkingLevelOptions(): List<ThinkingLevelOption> =
    listOf(ThinkingLevelOption("", "Default")) +
            ThinkingLevel.entries.map { ThinkingLevelOption(it.wireValue, it.wireValue) }

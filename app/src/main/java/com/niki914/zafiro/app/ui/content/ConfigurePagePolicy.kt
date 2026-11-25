package com.niki914.zafiro.app.ui.content

import com.niki914.zafiro.app.ui.model.ProviderSpec
import com.niki914.zafiro.app.ui.model.CustomApiSpec

data class ConfigurePagePolicy(
    val showEndpointSection: Boolean,
    val showEndpointOverrideToggle: Boolean,
    val endpointEditable: Boolean,
)

internal fun onboardingConfigurePolicy(providerSpec: ProviderSpec): ConfigurePagePolicy {
    return ConfigurePagePolicy(
        showEndpointSection = providerSpec.allowsCustomEndpointInNewConfig,
        showEndpointOverrideToggle = providerSpec.allowsCustomEndpointInNewConfig && providerSpec != CustomApiSpec,
        endpointEditable = providerSpec.allowsCustomEndpointInNewConfig,
    )
}

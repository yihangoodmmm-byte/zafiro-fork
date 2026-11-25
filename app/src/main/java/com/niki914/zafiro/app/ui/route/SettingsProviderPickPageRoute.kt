package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.CustomApiSpec
import com.niki914.zafiro.app.ui.content.SelectionOption
import com.niki914.zafiro.app.ui.content.SelectionPageContent
import com.niki914.zafiro.app.ui.model.ProviderSpecs
import com.niki914.zafiro.app.ui.nav.SavedConfigDetailPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

@Composable
internal fun SettingsProviderPickPageRoute(
    onPush: (ZafiroPage) -> Unit,
) {
    SelectionPageContent(
        options = ProviderSpecs.all.map { spec ->
            val label = if (spec == CustomApiSpec) stringResource(R.string.catalog_custom_api) else spec.brandName
            val colors = providerButtonColorsOrNull(spec)
            SelectionOption(
                id = spec.id,
                title = label,
                leadingIconRes = spec.iconRes,
                tintLeadingIcon = spec.tintIcon,
                darkContainerColor = colors?.darkContainerColor,
                lightContainerColor = colors?.lightContainerColor,
                darkContentColor = colors?.darkContentColor,
                lightContentColor = colors?.lightContentColor,
                darkIconColor = colors?.darkIconColor,
                lightIconColor = colors?.lightIconColor,
                onClick = {
                    onPush(
                        SavedConfigDetailPage(
                            configId = null,
                            configName = label,
                            providerId = spec.id,
                        ),
                    )
                },
            )
        },
    )
}

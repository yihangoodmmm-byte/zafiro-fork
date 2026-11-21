package com.niki914.zafiro.app.ui.content.header

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.PageChromeMenuItem
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.drawer.LocalDrawerController
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec

@Composable
internal fun HomeTopBarContribution(
    isConfigMenuVisible: Boolean,
    onDismissConfigMenu: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    titleContent: @Composable () -> Unit,
) {
    val drawerController = LocalDrawerController.current
    val newConversationMenuLabel = stringResource(R.string.ui_home_menu_new_conversation)
    val settingsMenuLabel = stringResource(R.string.ui_settings_menu_entry)

    val pageChromeContribution = remember(
        newConversationMenuLabel,
        settingsMenuLabel,
        titleContent,
        isConfigMenuVisible,
    ) {
        PageChromeContribution(
            titleContent = titleContent,
            isTitleOccluded = isConfigMenuVisible,
            actionsVisible = !isConfigMenuVisible,
            // 左侧改成抽屉入口：历史与设置都是低频动作，收进抽屉比各占一个按钮更省。
            // 没有抽屉控制器时（预览/测试环境）退回原来的历史入口。
            leftAction = TopBarActionSpec(
                icon = if (drawerController != null) Icons.Default.Menu else Icons.Default.History,
                onClick = {
                    if (isConfigMenuVisible) {
                        onDismissConfigMenu()
                    }
                    val drawer = drawerController
                    if (drawer != null) drawer.open() else onOpenHistory()
                },
                contentDescription = "History Button",
            ),
            menuItems = listOf(
                PageChromeMenuItem(
                    key = "new_conversation",
                    title = newConversationMenuLabel,
                    onClick = {
                        if (isConfigMenuVisible) {
                            onDismissConfigMenu()
                        }
                        onNewConversation()
                    },
                ),
                PageChromeMenuItem(
                    key = "settings",
                    title = settingsMenuLabel,
                    onClick = {
                        if (isConfigMenuVisible) {
                            onDismissConfigMenu()
                        }
                        onOpenSettings()
                    },
                ),
            ),
        )
    }

    RegisterPageChrome(pageChromeContribution)
}

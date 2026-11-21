package com.niki914.zafiro.app.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R

/**
 * 抽屉控制口。页面侧只表达「打开抽屉」，抽屉本体归壳层所有——
 * 页面并不知道抽屉里有哪些入口，也不该知道。
 */
class DrawerController(private val openDrawer: () -> Unit) {
    fun open() = openDrawer()
}

val LocalDrawerController = staticCompositionLocalOf<DrawerController?> { null }

/** 抽屉里的一个入口。 */
private data class DrawerEntry(
    val icon: ImageVector,
    val labelRes: Int,
    val onClick: () -> Unit,
)

/**
 * 左侧抽屉：应用级导航中枢。
 *
 * 为什么做成叠加层而不是 ModalNavigationDrawer：壳层的顶栏、折叠信号、
 * 动作按钮边界测量都挂在同一个 Box 上，把整棵子树包进 Drawer 容器会牵动这些
 * 与抽屉无关的逻辑；作为最后渲染的子节点叠上去，命中区域与视觉层级都够用，
 * 且不会影响既有布局测量。
 */
@Composable
fun NavigationDrawerOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDeveloperWorkbench: () -> Unit,
) {
    if (!visible) return

    val entries = listOf(
        DrawerEntry(Icons.Default.Add, R.string.ui_drawer_new_conversation) {
            onDismiss()
            onNewConversation()
        },
        DrawerEntry(Icons.Default.History, R.string.ui_drawer_history) {
            onDismiss()
            onOpenHistory()
        },
        DrawerEntry(Icons.Default.Build, R.string.workbench_title) {
            onDismiss()
            onOpenDeveloperWorkbench()
        },
        DrawerEntry(Icons.Default.Settings, R.string.ui_drawer_settings) {
            onDismiss()
            onOpenSettings()
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：点击关闭，同时挡住下层点击
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss),
        )
        Surface(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_drawer_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                entries.forEach { entry ->
                    DrawerRow(entry = entry)
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(entry: DrawerEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = entry.onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = entry.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = stringResource(entry.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

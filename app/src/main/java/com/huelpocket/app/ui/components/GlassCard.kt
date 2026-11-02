package com.huelpocket.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.huelpocket.glassui.GlassCard as UiGlassCard

/**
 * 应用内统一玻璃卡片的轻量封装。
 *
 * 实际材质、圆角、描边、高光和阴影全部来自 glass-ui；应用层只负责把
 * 原有「无内边距卡片」的调用习惯适配到库组件上。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    UiGlassCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(content = { content() })
    }
}

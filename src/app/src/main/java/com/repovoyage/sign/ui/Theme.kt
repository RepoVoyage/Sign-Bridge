package com.repovoyage.sign.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * App 主题（ARCHITECTURE §3.2：仅状态、字幕和控制，无视频组件）。
 * 跟随系统深浅色；配色用 Material3 默认基线，字幕可读性优先。
 */
@Composable
fun SignTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

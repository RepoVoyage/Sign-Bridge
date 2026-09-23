package com.repovoyage.sign

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.ui.HistoryScreen
import com.repovoyage.sign.ui.HistoryViewModel
import com.repovoyage.sign.ui.MainScreen
import com.repovoyage.sign.ui.MainViewModel
import com.repovoyage.sign.ui.SettingsScreen
import com.repovoyage.sign.ui.SettingsViewModel
import com.repovoyage.sign.ui.SignTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 单 Activity 宿主（ARCHITECTURE §3.2：Compose + MVVM/StateFlow）。
 * 三屏枚举导航（主界面/设置/历史）；VM 由 SignApp 容器供给数据。
 * P2 验证面板逻辑已迁入 MainViewModel/MainScreen。
 */
class MainActivity : ComponentActivity() {

    private val mainVm: MainViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()
    private val historyVm: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SignTheme {
                AppNav(mainVm, settingsVm, historyVm)
            }
        }
    }
}

private enum class Screen { MAIN, SETTINGS, HISTORY }

@Composable
private fun AppNav(
    mainVm: MainViewModel,
    settingsVm: SettingsViewModel,
    historyVm: HistoryViewModel,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
    BackHandler(screen != Screen.MAIN) { screen = Screen.MAIN }

    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(hasRuntimePermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasPermissions = grants.values.all { it }
    }

    // §2.6 首次使用告知：缓存内容、保留期限和删除方法
    val settings = remember(context) { (context.applicationContext as SignApp).settings }
    val scope = rememberCoroutineScope()
    var showCacheNotice by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showCacheNotice = !settings.cacheNoticeAcknowledged.first()
    }

    when (screen) {
        Screen.MAIN -> MainScreen(
            vm = mainVm,
            hasPermissions = hasPermissions,
            onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenHistory = { screen = Screen.HISTORY },
        )
        Screen.SETTINGS -> SettingsScreen(settingsVm, onBack = { screen = Screen.MAIN })
        Screen.HISTORY -> HistoryScreen(historyVm, onBack = { screen = Screen.MAIN })
    }

    if (showCacheNotice) {
        AlertDialog(
            onDismissRequest = { showCacheNotice = false },
            title = { Text(stringResource(R.string.cache_notice_title)) },
            text = { Text(stringResource(R.string.cache_notice_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settings.acknowledgeCacheNotice() }
                    showCacheNotice = false
                }) { Text(stringResource(R.string.cache_notice_confirm)) }
            },
        )
    }
}

// ---------------------------------------------------------------- 权限（自 P2 面板沿用）

private fun requiredPermissions(): Array<String> {
    val perms = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // manifest 中 BLUETOOTH_SCAN 为 neverForLocation，无需位置权限
        perms += Manifest.permission.BLUETOOTH_SCAN
        perms += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        perms += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}

private fun hasRuntimePermissions(context: Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

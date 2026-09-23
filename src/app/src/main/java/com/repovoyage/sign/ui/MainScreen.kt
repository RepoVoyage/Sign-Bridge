package com.repovoyage.sign.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repovoyage.sign.R
import com.repovoyage.sign.camera.SessionState
import com.repovoyage.sign.language.OutputStatus
import com.repovoyage.sign.pipeline.PipelinePhase
import com.repovoyage.sign.pipeline.SubtitleLine
import com.repovoyage.sign.sentence.LangCode

/**
 * 主界面（§2.7）：会话开始/停止、连接与管线状态、选定语言字幕、待核对入口、
 * 语音状态（未播报标记 + 显式重播）；training 额外显示采集入口与吞吐统计。
 * 不提供逐句结束按钮，不弹阻塞确认框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val pipeline by vm.pipelineState.collectAsStateWithLifecycle()
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()
    val sessionEvent by vm.sessionEvent.collectAsStateWithLifecycle()
    val statsText by vm.statsText.collectAsStateWithLifecycle()
    val scanStatus by vm.scanStatus.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.nav_history)) }
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.nav_settings)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ------------------------------------------------ 会话状态与控制
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.state_format, sessionStateText(sessionState)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    sessionEvent?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (statsText.isNotEmpty()) {
                        Text(statsText, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { if (hasPermissions) vm.startScan() else onRequestPermissions() },
                        ) { Text(stringResource(R.string.scan_button)) }
                        if (sessionState !is SessionState.Idle) {
                            OutlinedButton(onClick = vm::stopSession) { Text(stringResource(R.string.stop_button)) }
                        }
                    }
                    if (scanStatus.isNotEmpty()) {
                        Text(scanStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    if (devices.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            devices.forEachIndexed { index, device ->
                                FilterChip(
                                    selected = false,
                                    onClick = { vm.connect(device) },
                                    label = { Text(vm.deviceLabel(device, index)) },
                                )
                            }
                        }
                    }
                    // 训练版采集入口（production 无入口，P8 验收）
                    if (vm.captureEntry.isAvailable) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.captureEntry.start(vm.getApplication()) }) {
                                Text(stringResource(R.string.capture_start_button))
                            }
                            OutlinedButton(onClick = { vm.captureEntry.stop(vm.getApplication()) }) {
                                Text(stringResource(R.string.capture_stop_button))
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------ 翻译管线控制
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pipeline.phase == PipelinePhase.RUNNING) {
                    Button(onClick = vm::stopTranslation) { Text(stringResource(R.string.translation_stop)) }
                } else {
                    Button(
                        onClick = vm::startTranslation,
                        enabled = vm.recognitionAvailable,
                    ) { Text(stringResource(R.string.translation_start)) }
                }
                if (pipeline.phase == PipelinePhase.SOURCE_UNAVAILABLE) {
                    Text(
                        stringResource(R.string.translation_source_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ------------------------------------------------ 字幕区
            SubtitleArea(
                draft = pipeline.draft,
                lines = pipeline.lines,
                pendingConfirm = pipeline.pendingConfirm,
                onDiscard = vm::discardPending,
                onReplay = vm::replay,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun sessionStateText(state: SessionState): String = when (state) {
    SessionState.Idle -> "未启动"
    SessionState.Checking -> "检查中"
    is SessionState.BleConnecting -> "蓝牙连接中 ${state.deviceName ?: ""}"
    SessionState.WifiConnecting -> "Wi-Fi 连接中"
    is SessionState.Authorizing -> "相机授权中（${state.status}）"
    SessionState.Activating -> "激活中"
    SessionState.Preparing -> "准备取流"
    is SessionState.Streaming -> "取流中 ${state.params.width}x${state.params.height}@${state.params.fps}"
    is SessionState.Reconnecting -> "重连中（第 ${state.attempt} 次）"
    SessionState.Stopping -> "停止中"
    SessionState.PausedHot -> "过热暂停"
    is SessionState.Error -> "错误（${state.reason}）"
}

@Composable
private fun SubtitleArea(
    draft: com.repovoyage.sign.pipeline.DraftLine?,
    lines: List<SubtitleLine>,
    pendingConfirm: List<com.repovoyage.sign.pipeline.PendingConfirmLine>,
    onDiscard: (String) -> Unit,
    onReplay: (String, LangCode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (draft != null) {
            item(key = "draft") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.draft_prefix),
                            style = MaterialTheme.typography.labelMedium,
                            fontStyle = FontStyle.Italic,
                        )
                        Text(draft.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        items(pendingConfirm, key = { "pending-${it.segmentId}" }) { pending ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.pending_confirm_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(pending.draftText, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.pending_confirm_reason, pending.reason.name),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onDiscard(pending.segmentId) }) {
                        Text(stringResource(R.string.action_discard))
                    }
                }
            }
        }
        items(lines, key = { it.segmentId }) { line ->
            SubtitleLineCard(line, onReplay)
        }
    }
}

@Composable
private fun SubtitleLineCard(line: SubtitleLine, onReplay: (String, LangCode) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(line.rawChinese, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            if (line.results.isEmpty()) {
                Text(
                    stringResource(R.string.draft_prefix),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                )
            }
            line.results.forEach { (language, result) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            languageDisplayName(language),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        when (result.status) {
                            OutputStatus.READY ->
                                Text(result.text ?: "", style = MaterialTheme.typography.bodyMedium)
                            OutputStatus.NEEDS_CONFIRMATION ->
                                Text(
                                    "${result.text ?: ""}（${stringResource(R.string.result_needs_confirmation)}）",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            OutputStatus.UNAVAILABLE ->
                                Text(
                                    stringResource(R.string.result_unavailable),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                )
                        }
                    }
                    if (language in line.unspokenLanguages) {
                        Text(
                            stringResource(R.string.unspoken_marker),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        TextButton(onClick = { onReplay(line.segmentId, language) }) {
                            Text(stringResource(R.string.action_replay))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun languageDisplayName(language: LangCode): String = when (language.tag) {
    "zh-CN" -> stringResource(R.string.lang_zh_cn)
    "en-US" -> stringResource(R.string.lang_en_us)
    "ja-JP" -> stringResource(R.string.lang_ja_jp)
    else -> language.tag
}

package com.repovoyage.sign.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repovoyage.sign.R
import com.repovoyage.sign.history.SentenceWithResults
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** 待确认的破坏性动作（会话级/全部删除需确认；单句删除即点即删） */
private sealed interface PendingDelete {
    data class Session(val sessionId: String) : PendingDelete
    data object All : PendingDelete
}

/** 历史界面：按会话分组（新→旧），三级删除 + JSON/CSV 导出分享 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    // 保持缓存观察流的降序语义：会话按最近句排序，组内新→旧
    val grouped = remember(history) {
        history.groupBy { it.sentence.sessionId }
    }

    fun export(json: Boolean) {
        scope.launch {
            runCatching {
                val file = if (json) vm.exportJsonFile() else vm.exportCsvFile()
                context.startActivity(vm.shareChooser(file))
            }.onFailure {
                Toast.makeText(context, it.message ?: "export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.nav_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { export(json = true) }) {
                    Text(stringResource(R.string.history_export_json))
                }
                OutlinedButton(onClick = { export(json = false) }) {
                    Text(stringResource(R.string.history_export_csv))
                }
                OutlinedButton(
                    onClick = { pendingDelete = PendingDelete.All },
                    enabled = history.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.history_delete_all))
                }
            }
            Text(
                stringResource(R.string.export_receiver_notice),
                style = MaterialTheme.typography.labelSmall,
            )

            if (history.isEmpty()) {
                Text(
                    stringResource(R.string.history_empty),
                    modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    grouped.forEach { (sessionId, entries) ->
                        item(key = "session-$sessionId") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(
                                        R.string.history_session_header,
                                        sessionId.take(8), entries.size,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { pendingDelete = PendingDelete.Session(sessionId) }) {
                                    Text(stringResource(R.string.history_delete_session))
                                }
                            }
                        }
                        items(entries, key = { it.sentence.sessionId + "|" + it.sentence.segmentId }) { entry ->
                            HistoryEntryCard(entry, onDelete = {
                                vm.deleteSegment(entry.sentence.sessionId, entry.sentence.segmentId)
                            })
                        }
                    }
                }
            }
        }
    }

    when (val pending = pendingDelete) {
        is PendingDelete.Session -> AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_session)) },
            text = { Text(stringResource(R.string.history_delete_session_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSession(pending.sessionId)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        PendingDelete.All -> AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_all)) },
            text = { Text(stringResource(R.string.history_delete_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        null -> Unit
    }
}

@Composable
private fun HistoryEntryCard(entry: SentenceWithResults, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.sentence.rawChinese, fontWeight = FontWeight.Bold)
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(entry.sentence.wallTimeStart)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.history_delete_entry)) }
            }
            entry.results.forEach { result ->
                Text(
                    buildString {
                        append(languageTagLabel(result.language.tag))
                        append("：")
                        append(
                            when (result.status) {
                                "READY" -> result.text ?: ""
                                "NEEDS_CONFIRMATION" -> "${result.text ?: ""}（待核对）"
                                else -> "不可用"
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun languageTagLabel(tag: String): String = when (tag) {
    "zh-CN" -> "中文"
    "en-US" -> "英文"
    "ja-JP" -> "日文"
    else -> tag
}

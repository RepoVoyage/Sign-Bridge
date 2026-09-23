package com.repovoyage.sign.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.repovoyage.sign.R
import com.repovoyage.sign.SignApp
import com.repovoyage.sign.history.CacheExporter
import com.repovoyage.sign.history.SentenceWithResults
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * 历史界面 VM（§2.6）：观察缓存全量历史；按句/按会话/全部删除；
 * 导出 = 用户主动触发 → 系统分享机制（不常驻公共目录）。
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val signApp = app as SignApp
    private val exporter = CacheExporter(app, signApp.sentenceCache)

    val history: StateFlow<List<SentenceWithResults>> =
        signApp.sentenceCache.observeHistory()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteSegment(sessionId: String, segmentId: String) = viewModelScope.launch {
        signApp.sentenceCache.deleteBySegment(sessionId, segmentId)
    }

    fun deleteSession(sessionId: String) = viewModelScope.launch {
        signApp.sentenceCache.deleteBySession(sessionId)
    }

    fun deleteAll() = viewModelScope.launch {
        signApp.sentenceCache.deleteAll()
    }

    suspend fun exportJsonFile(): File = exporter.exportJsonFile()

    suspend fun exportCsvFile(): File = exporter.exportCsvFile()

    fun shareChooser(file: File): Intent {
        val mime = if (file.name.endsWith(".csv")) CacheExporter.CSV_MIME else CacheExporter.JSON_MIME
        return Intent.createChooser(
            exporter.shareIntent(file, mime),
            getApplication<Application>().getString(R.string.export_chooser_title),
        )
    }
}

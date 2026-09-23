package com.repovoyage.sign.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.repovoyage.sign.SignApp
import com.repovoyage.sign.recognition.ModelCatalog
import com.repovoyage.sign.sentence.LangCode
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.settings.LlmCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置界面 VM（§2.4.4/§2.6/§6.3）：语言选择顺序 = 处理优先级；语音语言 ⊆
 * 字幕语言由 AppSettings 约束层保证；识别模型选择在推理接入（P6）后生效。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings: AppSettings = (app as SignApp).settings

    val supportedLanguages = AppSettings.SUPPORTED_LANGUAGES

    val selectedLanguages: StateFlow<List<LangCode>> =
        settings.selectedLanguages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.DEFAULT_LANGUAGES)

    val spokenLanguages: StateFlow<List<LangCode>> =
        settings.spokenLanguages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.DEFAULT_LANGUAGES)

    val ttsEnabled: StateFlow<Boolean> =
        settings.ttsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val cacheEnabled: StateFlow<Boolean> =
        settings.cacheEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val selectedModelId: StateFlow<String?> =
        settings.selectedModelId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val llmCredentials: StateFlow<LlmCredentials> =
        settings.llmCredentials.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmCredentials("", "", ""))

    /** 模型目录 + 安装状态（filesDir/models 约定路径） */
    val modelEntries = ModelCatalog.ENTRIES.map { entry ->
        entry to ModelCatalog.isInstalled(app, entry)
    }

    // 凭据编辑草稿（保存才写入）
    val urlDraft = MutableStateFlow("")
    val keyDraft = MutableStateFlow("")
    val modelDraft = MutableStateFlow("")
    private var draftsLoaded = false

    init {
        viewModelScope.launch {
            if (!draftsLoaded) {
                draftsLoaded = true
                val c = settings.llmCredentials.first()
                urlDraft.value = c.baseUrl
                keyDraft.value = c.apiKey
                modelDraft.value = c.model
            }
        }
    }

    /** 选定语言：已选则移除（保底一种），未选则按点击顺序追加（顺序 = 优先级） */
    fun toggleSelected(language: LangCode) {
        viewModelScope.launch {
            val current = settings.selectedLanguages.first()
            val next = if (language in current) current - language else current + language
            if (next.isNotEmpty()) settings.setSelectedLanguages(next)
        }
    }

    fun toggleSpoken(language: LangCode) {
        viewModelScope.launch {
            val current = settings.spokenLanguages.first()
            val next = if (language in current) current - language else current + language
            settings.setSpokenLanguages(next)   // 非选定项由 AppSettings 自动过滤
        }
    }

    fun setTtsEnabled(enabled: Boolean) = viewModelScope.launch { settings.setTtsEnabled(enabled) }

    fun setCacheEnabled(enabled: Boolean) = viewModelScope.launch { settings.setCacheEnabled(enabled) }

    fun selectModel(modelId: String) = viewModelScope.launch { settings.setSelectedModelId(modelId) }

    fun saveCredentials() = viewModelScope.launch {
        settings.setLlmCredentials(LlmCredentials(urlDraft.value, keyDraft.value, modelDraft.value))
    }
}

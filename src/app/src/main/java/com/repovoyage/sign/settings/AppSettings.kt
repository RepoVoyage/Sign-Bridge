package com.repovoyage.sign.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsStore: DataStore<Preferences> by preferencesDataStore("app_settings")

/**
 * 应用设置（ARCHITECTURE §2.4.4：DataStore/StateFlow 可观察状态）。
 * 当前仅缓存相关两项（§2.6）；语言/语音/LLM 凭据随设置 UI 阶段扩充。
 */
class AppSettings(private val context: Context) {

    /** 文本缓存开关：默认开启；关闭后持有方停止写入新记录，已存记录由用户处置 */
    val cacheEnabled: Flow<Boolean> =
        context.appSettingsStore.data.map { it[KEY_CACHE_ENABLED] ?: true }

    suspend fun setCacheEnabled(enabled: Boolean) {
        context.appSettingsStore.edit { it[KEY_CACHE_ENABLED] = enabled }
    }

    /** 首次使用告知（缓存内容/保留期限/删除方法）是否已展示并确认 */
    val cacheNoticeAcknowledged: Flow<Boolean> =
        context.appSettingsStore.data.map { it[KEY_CACHE_NOTICE_ACK] ?: false }

    suspend fun acknowledgeCacheNotice() {
        context.appSettingsStore.edit { it[KEY_CACHE_NOTICE_ACK] = true }
    }

    private companion object {
        val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val KEY_CACHE_NOTICE_ACK = booleanPreferencesKey("cache_notice_acknowledged")
    }
}

package com.repovoyage.sign

import android.app.Application
import android.util.Log
import com.arashivision.sdk.camera.InstaCameraSDK
import com.repovoyage.sign.history.RoomSentenceCache
import com.repovoyage.sign.history.SentenceCache
import com.repovoyage.sign.history.SentenceDatabase
import com.repovoyage.sign.history.applyRetentionPolicy
import com.repovoyage.sign.language.LanguageProcessor
import com.repovoyage.sign.language.LanguageProcessorImpl
import com.repovoyage.sign.pipeline.CredentialLlmPolisher
import com.repovoyage.sign.pipeline.TranslationPipeline
import com.repovoyage.sign.recognition.RecognitionSourceImpl
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.tts.AndroidTtsSpeaker
import com.repovoyage.sign.tts.TtsManager
import com.repovoyage.sign.tts.TtsManagerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SignApp : Application() {

    /** 应用级单例容器（MVVM 的组合根；正式 DI 框架为 §3.2 遗留决策项） */
    val settings: AppSettings by lazy { AppSettings(this) }
    val database: SentenceDatabase by lazy { SentenceDatabase.create(this) }
    val sentenceCache: SentenceCache by lazy { RoomSentenceCache(database.sentenceDao()) }
    val ttsSpeaker: AndroidTtsSpeaker by lazy { AndroidTtsSpeaker(this) }
    val ttsManager: TtsManager by lazy { TtsManagerImpl(ttsSpeaker, appScope) }
    val languageProcessor: LanguageProcessor by lazy {
        LanguageProcessorImpl(
            // §6.3：凭据运行时读取，发布包不内置密钥
            engine = CredentialLlmPolisher { settings.llmCredentials.first() },
            scope = appScope,
        )
    }
    val pipeline: TranslationPipeline by lazy {
        TranslationPipeline(
            source = RecognitionSourceImpl,   // flavor 缝：training=桩源，production=不可用
            settings = settings,
            processor = languageProcessor,
            tts = ttsManager,
            cache = sentenceCache,
            scope = appScope,
        )
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // ARCHITECTURE.md §2.1.2：按 Application 位置初始化（Demo 在 MainActivity
        // 权限通过后才 init，两者等价性在首个真机构建时验证）。
        // 日志级别用默认值：SDK 日志可能带相机信息，成品不开 verbose。
        InstaCameraSDK.init(this) {
            cacheDir = externalCacheDir?.absolutePath
        }
        // §2.6 保留策略：每次进程启动清理一次 90 天/万条超限；
        // 写入失败不影响实时链路（§6 错误矩阵），仅记日志。
        appScope.launch {
            runCatching {
                applyRetentionPolicy(database.sentenceDao(), System.currentTimeMillis())
            }.onFailure { Log.w(TAG, "retention cleanup failed", it) }
        }
    }

    private companion object {
        const val TAG = "SignApp"
    }
}

package com.repovoyage.sign.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.repovoyage.sign.sentence.LangCode
import java.util.Locale

/**
 * [TtsSpeaker] 的 Android TextToSpeech 实现（§2.5：App 管理播报）。
 *
 * - 初始化异步；未完成前 [isLanguageReady] 一律 false（宁可标记语音不可用，
 *   不假装就绪——§2.5.1）
 * - 就绪判定 = 该 locale 存在**不需要网络连接**的系统 Voice（离线优先，
 *   不隐式依赖联网语音）；缺 Voice 的语言由管线标记"语音不可用"仅显示字幕
 * - 终结回调经 [UtteranceProgressListener] 三态映射，幂等由 TtsManager 保证
 * - 生命周期归持有方：容器销毁时调 [release]
 */
class AndroidTtsSpeaker(context: Context) : TtsSpeaker {

    @Volatile
    private var ready = false

    private var listener: ((utteranceId: String, outcome: SpeakerOutcome) -> Unit)? = null

    // onInit 异步回调晚于构造完成，回调内安全引用
    private val tts: TextToSpeech?

    /** 就绪态变化日志去重（设置页 3s 轮询，避免刷屏）；仅诊断用 */
    private val readyLogState = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(progressListener)
                ready = true
                logVoices()
            } else {
                android.util.Log.e(TAG, "TTS 引擎初始化失败：status=$status")
            }
        }
    }

    /** 联调诊断：引擎与中文语音包快照（只记元数据，不记播报内容——§2.6） */
    private fun logVoices() {
        runCatching {
            val all = tts?.voices.orEmpty()
            val zh = all.filter { it.locale.language == "zh" }
            android.util.Log.i(
                TAG,
                "TTS 就绪：引擎=${tts?.defaultEngine}，voice 共 ${all.size} 个，" +
                    "中文 ${zh.map { "${it.locale}(离线=${!it.isNetworkConnectionRequired})" }}",
            )
        }
    }

    override fun isLanguageReady(language: LangCode): Boolean {
        val engine = tts
        if (!ready || engine == null) return false
        val locale = Locale.forLanguageTag(language.tag)
        // 离线 Voice 存在即就绪；引擎未下载该语言数据时不触发隐式下载。
        // 宽松 locale 匹配（2026-09-23 真机联调）：系统语音包常以 zh-Hans-CN
        // 或裸 zh 形式上报，与 forLanguageTag("zh-CN") 严格相等会误判不可用
        val result = engine.voices?.any { voice ->
            localeMatches(voice.locale, locale) && !voice.isNetworkConnectionRequired
        } == true
        if (readyLogState.put(language.tag, result) != result) {
            android.util.Log.i(TAG, "语音就绪判定 ${language.tag}=$result")
        }
        return result
    }

    override fun speak(utteranceId: String, text: String, language: LangCode): Boolean {
        val engine = tts
        if (!ready || engine == null) return false
        val locale = Locale.forLanguageTag(language.tag)
        val setLangResult = engine.setLanguage(locale)
        if (setLangResult == TextToSpeech.LANG_MISSING_DATA ||
            setLangResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            android.util.Log.w(TAG, "TTS setLanguage(${language.tag}) 失败：$setLangResult")
            return false
        }
        val submitted = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
        android.util.Log.i(TAG, "TTS 播报提交 ${language.tag}（${text.length} 字）：$submitted")
        return submitted
    }

    internal companion object {
        const val TAG = "YuqiaoTts"

        /**
         * 目标 locale（如 zh-CN）能否由语音包 locale 承担：语言必须一致；
         * 国家缺省视为通用包可用；中文按 script 区分简繁（Hant 不承担 zh-CN）。
         */
        fun localeMatches(voice: Locale, target: Locale): Boolean {
            if (voice.language != target.language) return false
            if (voice.script.isNotEmpty() && target.script.isNotEmpty() &&
                voice.script != target.script
            ) {
                return false
            }
            if (target.language == "zh" && target.script.isEmpty() && voice.script == "Hant") {
                return false   // zh-CN 目标需简体（zh-Hans-* 或无 script 的 zh/zh-CN）
            }
            return voice.country.isEmpty() || voice.country == target.country
        }
    }

    override fun stop() {
        if (ready) tts?.stop()
    }

    override fun setTerminalListener(listener: (utteranceId: String, outcome: SpeakerOutcome) -> Unit) {
        this.listener = listener
    }

    fun release() {
        ready = false
        tts?.stop()
        tts?.shutdown()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { listener?.invoke(it, SpeakerOutcome.DONE) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { listener?.invoke(it, SpeakerOutcome.ERROR) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { listener?.invoke(it, SpeakerOutcome.ERROR) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { listener?.invoke(it, SpeakerOutcome.STOP) }
        }
    }
}

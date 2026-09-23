package com.repovoyage.sign.p7

import com.repovoyage.sign.tts.AndroidTtsSpeaker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 语音包 locale 宽松匹配（2026-09-23 真机联调：严格相等把 zh-Hans-CN /
 * 裸 zh 语音包误判为"中文语音不可用"）。
 */
class TtsLocaleMatchTest {

    private fun matches(voice: Locale, targetTag: String) =
        AndroidTtsSpeaker.localeMatches(voice, Locale.forLanguageTag(targetTag))

    @Test
    fun `中文——常见语音包形态都能承担 zh-CN`() {
        assertTrue(matches(Locale.forLanguageTag("zh-CN"), "zh-CN"))
        assertTrue(matches(Locale.forLanguageTag("zh-Hans-CN"), "zh-CN"))
        assertTrue(matches(Locale.forLanguageTag("zh-Hans"), "zh-CN"))
        assertTrue(matches(Locale("zh"), "zh-CN"))   // 裸 zh 通用包
    }

    @Test
    fun `中文——繁体与地区变体不承担 zh-CN`() {
        assertFalse(matches(Locale.forLanguageTag("zh-Hant-CN"), "zh-CN"))
        assertFalse(matches(Locale.forLanguageTag("zh-TW"), "zh-CN"))
        assertFalse(matches(Locale.forLanguageTag("zh-HK"), "zh-CN"))
    }

    @Test
    fun `英日——语言一致且国家缺省或相等`() {
        assertTrue(matches(Locale.forLanguageTag("en-US"), "en-US"))
        assertTrue(matches(Locale("en"), "en-US"))
        assertFalse(matches(Locale.forLanguageTag("en-GB"), "en-US"))
        assertTrue(matches(Locale.forLanguageTag("ja-JP"), "ja-JP"))
        assertFalse(matches(Locale.forLanguageTag("ja"), "en-US"))
    }
}

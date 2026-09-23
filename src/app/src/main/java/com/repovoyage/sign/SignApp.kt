package com.repovoyage.sign

import android.app.Application
import android.util.Log
import com.arashivision.sdk.camera.InstaCameraSDK
import com.repovoyage.sign.history.RoomSentenceCache
import com.repovoyage.sign.history.SentenceCache
import com.repovoyage.sign.history.SentenceDatabase
import com.repovoyage.sign.history.applyRetentionPolicy
import com.repovoyage.sign.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SignApp : Application() {

    /** 应用级单例：设置 / 数据库 / 缓存门面（管线与 UI 阶段消费） */
    val settings: AppSettings by lazy { AppSettings(this) }
    val database: SentenceDatabase by lazy { SentenceDatabase.create(this) }
    val sentenceCache: SentenceCache by lazy { RoomSentenceCache(database.sentenceDao()) }

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

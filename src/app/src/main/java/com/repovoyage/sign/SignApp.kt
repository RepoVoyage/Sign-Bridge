package com.repovoyage.sign

import android.app.Application
import com.arashivision.sdk.camera.InstaCameraSDK

class SignApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // ARCHITECTURE.md §2.1.2：按 Application 位置初始化（Demo 在 MainActivity
        // 权限通过后才 init，两者等价性在首个真机构建时验证）。
        // 日志级别用默认值：SDK 日志可能带相机信息，成品不开 verbose。
        InstaCameraSDK.init(this) {
            cacheDir = externalCacheDir?.absolutePath
        }
    }
}

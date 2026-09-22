package com.repovoyage.sign.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 相机连接/取流前台服务的空壳（P1）。
 * 只做 Manifest 声明与存活验证，实际连接逻辑在 P2/P3 实现。
 */
class CameraBridgeForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null
}

package com.repovoyage.sign.recognition

import android.content.Context
import java.io.File

/**
 * 识别模型目录（P5 产物，当前两个已训练模型）。文件名/格式/输入规格随
 * P6 SignRecognizer 接入时按 ModelSpec（API.md §3）定稿回填——本目录先固定
 * id 与展示名；用户选择持久化于 [com.repovoyage.sign.settings.AppSettings]
 * .selectedModelId，推理接入后即生效。
 *
 * 模型文件约定放置于 App 私有 filesDir/models/（不进备份、不常驻公共目录，
 * §2.6 隐私口径一致）；开发期可 adb push 更新。
 */
data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val fileName: String,       // 占位命名，P6 接入时按实际交付物回填
)

object ModelCatalog {

    val ENTRIES = listOf(
        ModelCatalogEntry("model-a", "模型 A", "model-a.tflite"),
        ModelCatalogEntry("model-b", "模型 B", "model-b.tflite"),
    )

    fun modelsDir(context: Context): File = File(context.filesDir, "models")

    /** 模型文件是否已就位于本机（UI 显示 已安装/未安装；未安装不可选用） */
    fun isInstalled(context: Context, entry: ModelCatalogEntry): Boolean =
        File(modelsDir(context), entry.fileName).exists()
}

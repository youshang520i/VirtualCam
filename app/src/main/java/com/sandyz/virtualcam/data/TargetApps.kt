package com.sandyz.virtualcam.data

/**
 * 目标 App 清单（仅 UI 层使用，与各 hook 的 getSupportedPackages() 保持独立，
 * 避免改动 hook 行为引入回归）。
 */
data class TargetApp(
    val pkg: String,
    val label: String,
)

object TargetApps {
    val ALL: List<TargetApp> = listOf(
        TargetApp("com.android.camera", "系统相机"),
        TargetApp("com.ss.android.ugc.aweme", "抖音"),
        TargetApp("tv.danmaku.bili", "B站"),
        TargetApp("com.tencent.mm", "微信"),
        TargetApp("com.smile.gifmaker", "快手"),
        TargetApp("com.tencent.mobileqq", "QQ"),
        TargetApp("com.xunmeng.pinduoduo", "拼多多"),
        TargetApp("com.whatsapp", "WhatsApp"),
    )

    fun labelOf(pkg: String): String = ALL.firstOrNull { it.pkg == pkg }?.label ?: pkg
}

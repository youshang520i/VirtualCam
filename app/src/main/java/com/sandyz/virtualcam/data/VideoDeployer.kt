package com.sandyz.virtualcam.data

import android.content.Context
import com.sandyz.virtualcam.utils.RootShell
import com.sandyz.virtualcam.utils.xLog
import java.io.File

/**
 * 把「App → 视频」指派落地到目标 App 的 cache 目录：
 * 用 root 执行 cp，把库文件复制成 <目标cache>/virtual.mp4，并删掉 stream.txt 确保 fallback 生效。
 */
object VideoDeployer {

    /**
     * @param libraryFilePath 视频库中视频文件的绝对路径
     * @param pkg 目标 App 包名
     * @return 部署是否成功
     */
    fun deploy(context: Context, pkg: String, libraryFilePath: String): Boolean {
        val src = File(libraryFilePath)
        if (!src.exists()) {
            xLog("部署失败，源文件不存在: $libraryFilePath")
            return false
        }
        val targetCache = "/storage/emulated/0/Android/data/$pkg/cache"
        val cmd = "mkdir -p $targetCache && " +
            "cp $libraryFilePath $targetCache/virtual.mp4 && " +
            "rm -f $targetCache/stream.txt && " +
            "chmod 644 $targetCache/virtual.mp4"

        val result = RootShell.exec(cmd)
        xLog("deploy pkg=$pkg src=$libraryFilePath exit=${result.exitCode} output=${result.output}")
        if (result.isSuccess) {
            ConfigStore.markDeployed(context, pkg, src.name)
        }
        return result.isSuccess
    }

    /**
     * 清除所有目标 App 已部署的虚拟视频（virtual.mp4）和 stream.txt，恢复真实摄像头。
     * @return 是否全部清除成功
     */
    fun clearAll(): Boolean {
        val targets = TargetApps.ALL
            .map { "/storage/emulated/0/Android/data/${it.pkg}/cache" }
        val cmd = targets.joinToString(" && ") { "rm -f $it/virtual.mp4 $it/stream.txt" }
        val result = RootShell.exec(cmd)
        xLog("clearAll exit=${result.exitCode} output=${result.output}")
        return result.isSuccess
    }
}

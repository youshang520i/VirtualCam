package com.sandyz.virtualcam.data

import com.sandyz.virtualcam.utils.RootShell
import com.sandyz.virtualcam.utils.xLog
import java.io.File

/**
 * 视频去重：用 FFmpeg 周期性替换画面段 / 音频段。
 * FFmpeg 二进制需提前部署到 /data/local/tmp/ffmpeg 和 /data/local/tmp/ffprobe（root）。
 */
object VideoDedup {

    const val FFMPEG = "/data/local/tmp/ffmpeg"
    const val FFPROBE = "/data/local/tmp/ffprobe"

    /** 检查 FFmpeg/ffprobe 是否已部署。 */
    fun isReady(): Boolean {
        return RootShell.exec("ls $FFMPEG $FFPROBE").isSuccess
    }

    /**
     * 探测视频时长（秒）。失败返回 -1。
     */
    fun probeDuration(path: String): Double {
        val r = RootShell.exec(
            "$FFPROBE -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 \"$path\""
        )
        return r.output.trim().toDoubleOrNull() ?: -1.0
    }

    /**
     * 探测视频宽高（w:h）。失败返回 "0:0"。
     */
    fun probeSize(path: String): Pair<Int, Int> {
        val r = RootShell.exec(
            "$FFPROBE -v error -select_streams v:0 -show_entries stream=width,height " +
                "-of csv=s=x:p=0 \"$path\""
        )
        val parts = r.output.trim().split('x')
        return if (parts.size == 2) {
            (parts[0].toIntOrNull() ?: 0) to (parts[1].toIntOrNull() ?: 0)
        } else {
            0 to 0
        }
    }

    /**
     * 音频段替换去重。
     * 每周期：保留原音频 keep 秒，替换为参考音频 replace 秒，循环直到覆盖原视频时长。
     * 视频轨道不重编码（copy）。
     */
    fun dedupAudio(
        originalPath: String,
        refPath: String,
        keep: Double,
        replace: Double,
        outPath: String,
    ): Boolean {
        val duration = probeDuration(originalPath)
        if (duration <= 0) {
            xLog("dedupAudio 探测时长失败: $originalPath")
            return false
        }
        val period = keep + replace
        if (period <= 0) return false

        val filters = StringBuilder()
        val inputs = mutableListOf<String>()
        var cursor = 0.0
        var idx = 0
        var refCursor = 0.0
        while (cursor < duration) {
            val keepEnd = minOf(cursor + keep, duration)
            if (keepEnd > cursor) {
                filters.append("[0:a]atrim=${cursor}:${keepEnd},asetpts=PTS-STARTPTS[a$idx];")
                inputs.add("a$idx")
                idx++
            }
            cursor = keepEnd
            if (cursor >= duration) break

            val replaceEnd = cursor + replace
            filters.append("[1:a]atrim=${refCursor}:${refCursor + replace},asetpts=PTS-STARTPTS[r$idx];")
            inputs.add("r$idx")
            idx++
            refCursor += replace
            cursor = replaceEnd
        }

        if (inputs.size < 2) {
            xLog("dedupAudio 片段数不足: ${inputs.size}")
            return false
        }
        filters.append(inputs.joinToString("") { "[$it]" })
        filters.append("concat=n=${inputs.size}:v=0:a=1[aout]")

        val cmd = "$FFMPEG -y -i \"$originalPath\" -stream_loop -1 -i \"$refPath\" " +
            "-filter_complex \"$filters\" " +
            "-map 0:v -map \"[aout]\" -c:v copy -c:a aac \"$outPath\""
        val r = RootShell.exec(cmd)
        xLog("dedupAudio exit=${r.exitCode} out=${r.output.takeLast(200)}")
        return r.isSuccess
    }

    /**
     * 画面段替换去重。
     * 每周期：保留原画面 keep 秒，替换为参考视频画面 replace 秒，循环直到覆盖原视频时长。
     * 视频需重编码（mpeg4 软编，MediaCodec 对小分辨率支持差）。
     */
    fun dedupVideo(
        originalPath: String,
        refPath: String,
        keep: Double,
        replace: Double,
        outPath: String,
    ): Boolean {
        val duration = probeDuration(originalPath)
        if (duration <= 0) {
            xLog("dedupVideo 探测时长失败: $originalPath")
            return false
        }
        val (w, h) = probeSize(originalPath)
        if (w <= 0 || h <= 0) {
            xLog("dedupVideo 探测分辨率失败: $originalPath")
            return false
        }
        val period = keep + replace
        if (period <= 0) return false

        val filters = StringBuilder()
        val inputs = mutableListOf<String>()
        var cursor = 0.0
        var idx = 0
        var refCursor = 0.0
        while (cursor < duration) {
            val keepEnd = minOf(cursor + keep, duration)
            if (keepEnd > cursor) {
                filters.append("[0:v]trim=${cursor}:${keepEnd},setpts=PTS-STARTPTS[v$idx];")
                inputs.add("v$idx")
                idx++
            }
            cursor = keepEnd
            if (cursor >= duration) break

            val replaceEnd = cursor + replace
            filters.append("[1:v]trim=${refCursor}:${refCursor + replace},scale=$w:$h,setpts=PTS-STARTPTS[r$idx];")
            inputs.add("r$idx")
            idx++
            refCursor += replace
            cursor = replaceEnd
        }

        if (inputs.size < 2) {
            xLog("dedupVideo 片段数不足: ${inputs.size}")
            return false
        }
        filters.append(inputs.joinToString("") { "[$it]" })
        filters.append("concat=n=${inputs.size}:v=1:a=0[vout]")

        val cmd = "$FFMPEG -y -i \"$originalPath\" -stream_loop -1 -i \"$refPath\" " +
            "-filter_complex \"$filters\" " +
            "-map \"[vout]\" -map 0:a -c:v mpeg4 -q:v 5 -c:a copy \"$outPath\""
        val r = RootShell.exec(cmd)
        xLog("dedupVideo exit=${r.exitCode} out=${r.output.takeLast(200)}")
        return r.isSuccess
    }
}

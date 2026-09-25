package com.sandyz.virtualcam.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File

/**
 * 视频库管理。
 * 视频存放在模块自己的 external files 目录（无需 root/权限），
 * 真实文件名被消毒为 [A-Za-z0-9_-]，原始显示名映射保存在 SharedPreferences。
 */
data class VideoItem(
    val fileName: String,       // 磁盘上的真实（消毒后）文件名
    val displayName: String,    // 原始显示名
    val size: Long,
)

object VideoLibrary {
    private const val PREFS = "virtualcam_video_lib"
    private const val KEY_NAME_MAP = "name_map"

    fun dir(context: Context): File {
        val d = File(context.getExternalFilesDir(null), "videos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun namePrefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取 文件名→显示名 映射。 */
    private fun nameMap(context: Context): Map<String, String> {
        val raw = namePrefs(context).getString(KEY_NAME_MAP, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k -> map[k] = obj.optString(k) }
            map
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    private fun saveNameMap(context: Context, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        namePrefs(context).edit().putString(KEY_NAME_MAP, obj.toString()).apply()
    }

    /** 列出视频库中所有 .mp4 文件，按加入时间倒序（新视频在前）。 */
    fun list(context: Context): List<VideoItem> {
        val d = dir(context)
        val names = nameMap(context)
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".mp4") } ?: emptyArray()
        return files
            .sortedByDescending { it.lastModified() }
            .map { f ->
                VideoItem(
                    fileName = f.name,
                    displayName = names[f.name] ?: f.name,
                    size = f.length(),
                )
            }
    }

    fun fileOf(context: Context, fileName: String): File = File(dir(context), fileName)

    /**
     * 从 SAF content URI 导入一个视频到视频库。
     * 在调用方指定的线程（IO）里执行。
     */
    fun add(context: Context, uri: Uri): VideoItem? {
        val (displayName, size) = queryMeta(context, uri)
        val sanitized = sanitize(displayName)
        val dest = File(dir(context), sanitized)

        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null

        val names = nameMap(context).toMutableMap()
        names[sanitized] = displayName
        saveNameMap(context, names)

        return VideoItem(
            fileName = sanitized,
            displayName = displayName,
            size = size,
        )
    }

    fun delete(context: Context, fileName: String) {
        val f = File(dir(context), fileName)
        if (f.exists()) f.delete()
        val names = nameMap(context).toMutableMap()
        names.remove(fileName)
        saveNameMap(context, names)
    }

    private fun queryMeta(context: Context, uri: Uri): Pair<String, Long> {
        var name = "video_${System.currentTimeMillis()}.mp4"
        var size = 0L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Throwable) {
        }
        if (name.isBlank()) name = "video_${System.currentTimeMillis()}.mp4"
        if (!name.endsWith(".mp4")) name = "$name.mp4"
        return name to size
    }

    /** 把任意文件名消毒为 [A-Za-z0-9_-].mp4，规避 shell 引号/中文/空格问题。 */
    private fun sanitize(original: String): String {
        val base = original.substringBeforeLast('.', original).ifBlank { "video" }
        val safe = base.map { c -> if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') c else '_' }
            .joinToString("")
            .trim('_')
            .ifBlank { "video" }
        return "${safe}_${System.currentTimeMillis()}.mp4"
    }
}

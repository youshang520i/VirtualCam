package com.sandyz.virtualcam.data

import android.content.Context
import org.json.JSONObject

/**
 * 保存「目标 App → 视频」映射，以及已部署视频缓存（用于跳过重复 cp）。
 */
object ConfigStore {
    private const val PREFS = "virtualcam_config"
    private const val KEY_APP_VIDEO_MAP = "app_video_map"
    private const val KEY_DEPLOYED_MAP = "deployed_map"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取 App→视频文件名 映射。 */
    fun getAppVideoMap(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_APP_VIDEO_MAP, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k -> map[k] = obj.optString(k) }
            map
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    /** 保存 App→视频文件名 映射。 */
    fun setAppVideo(context: Context, pkg: String, fileName: String) {
        val map = getAppVideoMap(context).toMutableMap()
        map[pkg] = fileName
        prefs(context).edit()
            .putString(KEY_APP_VIDEO_MAP, toJson(map).toString())
            .apply()
    }

    /** 读取已部署缓存（pkg → 已部署的视频文件名）。 */
    fun getDeployedMap(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_DEPLOYED_MAP, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k -> map[k] = obj.optString(k) }
            map
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    fun markDeployed(context: Context, pkg: String, fileName: String) {
        val map = getDeployedMap(context).toMutableMap()
        map[pkg] = fileName
        prefs(context).edit()
            .putString(KEY_DEPLOYED_MAP, toJson(map).toString())
            .apply()
    }

    private fun toJson(map: Map<String, String>): JSONObject {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj
    }
}

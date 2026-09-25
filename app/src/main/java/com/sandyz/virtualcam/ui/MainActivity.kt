package com.sandyz.virtualcam.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sandyz.virtualcam.R
import com.sandyz.virtualcam.data.ConfigStore
import com.sandyz.virtualcam.data.TargetApp
import com.sandyz.virtualcam.data.TargetApps
import com.sandyz.virtualcam.data.VideoDedup
import com.sandyz.virtualcam.data.VideoDeployer
import com.sandyz.virtualcam.data.VideoItem
import com.sandyz.virtualcam.data.VideoLibrary
import com.sandyz.virtualcam.utils.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class MainActivity : AppCompatActivity() {

    private lateinit var rvVideos: RecyclerView
    private lateinit var rvApps: RecyclerView
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var appAdapter: AppAdapter

    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rvVideos = findViewById(R.id.rv_videos)
        rvApps = findViewById(R.id.rv_apps)
        val btnAdd = findViewById<Button>(R.id.btn_add_video)
        val btnClear = findViewById<Button>(R.id.btn_clear)
        val btnDedupAudio = findViewById<Button>(R.id.btn_dedup_audio)
        val btnDedupVideo = findViewById<Button>(R.id.btn_dedup_video)

        videoAdapter = VideoAdapter()
        rvVideos.layoutManager = GridLayoutManager(this, 2)
        rvVideos.adapter = videoAdapter

        appAdapter = AppAdapter()
        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = appAdapter

        btnAdd.setOnClickListener { openFilePicker() }
        btnClear.setOnClickListener { confirmClearAll() }
        btnDedupAudio.setOnClickListener { showDedupDialog(isAudio = true) }
        btnDedupVideo.setOnClickListener { showDedupDialog(isAudio = false) }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        videoAdapter.submit(VideoLibrary.list(this))
        appAdapter.submit(TargetApps.ALL, ConfigStore.getAppVideoMap(this))
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_PICK_VIDEO)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_VIDEO && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            data.data?.let { uris.add(it) }
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uris.add(it) }
                }
            }
            if (uris.isEmpty()) return
            importVideos(uris)
        }
    }

    private fun importVideos(uris: List<Uri>) {
        val toast = Toast.makeText(this, "正在导入 ${uris.size} 个视频…", Toast.LENGTH_SHORT)
        toast.show()
        uiScope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    runCatching { VideoLibrary.add(this@MainActivity, uri) }
                }
            }
            val ok = results.count { it.isSuccess && it.getOrNull() != null }
            val fail = results.size - ok
            Toast.makeText(
                this@MainActivity,
                "导入完成：成功 $ok，失败 $fail",
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    private fun showSelectVideoDialog(app: TargetApp) {
        val videos = VideoLibrary.list(this)
        if (videos.isEmpty()) {
            Toast.makeText(this, "视频库为空，请先添加视频", Toast.LENGTH_SHORT).show()
            return
        }
        val names = videos.map { it.displayName }.toTypedArray()
        val items = videos.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("为「${app.label}」选择视频")
            .setItems(names) { _, which ->
                val chosen = items[which]
                deployToApp(app, chosen)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("清除所有设置")
            .setMessage("将删除所有目标 App 的虚拟视频（virtual.mp4），恢复真实摄像头。确定吗？")
            .setPositiveButton("清除") { _, _ -> clearAll() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun clearAll() {
        if (!RootShell.isRootAvailable()) {
            Toast.makeText(
                this,
                "未获取 root 权限，请在 APatch 管理器里允许本模块的 root 请求后重试",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val toast = Toast.makeText(this, "正在清除…", Toast.LENGTH_SHORT)
        toast.show()
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                VideoDeployer.clearAll()
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) "已清除所有虚拟视频设置"
                else "清除失败，请查看日志",
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
    }

    private fun deployToApp(app: TargetApp, video: VideoItem) {
        if (!RootShell.isRootAvailable()) {
            Toast.makeText(
                this,
                "未获取 root 权限，请在 APatch 管理器里允许本模块的 root 请求后重试",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val src = VideoLibrary.fileOf(this, video.fileName).absolutePath
        val toast = Toast.makeText(this, "正在部署到 ${app.label}…", Toast.LENGTH_SHORT)
        toast.show()
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                VideoDeployer.deploy(this@MainActivity, app.pkg, src)
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) "已部署到 ${app.label}，重启目标 App 生效"
                else "部署失败，请查看日志",
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
    }

    /**
     * 弹出去重对话框：选择原视频 + 参考文件，输入 keep/replace 秒数，执行合成。
     */
    private fun showDedupDialog(isAudio: Boolean) {
        val videos = VideoLibrary.list(this)
        if (videos.size < 2) {
            Toast.makeText(this, "视频库至少需要 2 个视频（原视频 + 参考视频/音频）", Toast.LENGTH_LONG).show()
            return
        }
        if (!VideoDedup.isReady()) {
            Toast.makeText(
                this,
                "FFmpeg 未部署。需将 ffmpeg/ffprobe 放到 /data/local/tmp/ 并赋执行权限",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val names = videos.map { it.displayName }.toTypedArray()
        val items = videos.toTypedArray()

        // 选原视频
        var originalIdx = -1
        var refIdx = -1

        AlertDialog.Builder(this)
            .setTitle(if (isAudio) "音频去重 - 选择原视频" else "视频去重 - 选择原视频")
            .setItems(names) { _, which ->
                originalIdx = which
                // 选参考文件
                AlertDialog.Builder(this)
                    .setTitle(if (isAudio) "选择参考音频" else "选择参考视频")
                    .setItems(names) { _, w2 ->
                        refIdx = w2
                        if (refIdx == originalIdx) {
                            Toast.makeText(this, "参考文件不能和原视频相同", Toast.LENGTH_SHORT).show()
                            return@setItems
                        }
                        showDedupParamsDialog(isAudio, items[originalIdx], items[refIdx])
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDedupParamsDialog(isAudio: Boolean, original: VideoItem, ref: VideoItem) {
        // 用简单默认参数 keep=3, replace=3；提供对话框让用户输入
        val inputKeep = android.widget.EditText(this).apply {
            hint = "保留原内容秒数（默认3）"
            setText("3")
        }
        val inputReplace = android.widget.EditText(this).apply {
            hint = "替换秒数（默认3）"
            setText("3")
        }
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
            addView(inputKeep)
            addView(inputReplace)
        }
        AlertDialog.Builder(this)
            .setTitle(if (isAudio) "音频去重参数" else "视频去重参数")
            .setMessage("原视频：${original.displayName}\n参考：${ref.displayName}")
            .setView(layout)
            .setPositiveButton("开始合成") { _, _ ->
                val keep = inputKeep.text.toString().toDoubleOrNull() ?: 3.0
                val replace = inputReplace.text.toString().toDoubleOrNull() ?: 3.0
                if (keep <= 0 || replace <= 0) {
                    Toast.makeText(this, "秒数必须大于 0", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runDedup(isAudio, original, ref, keep, replace)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runDedup(isAudio: Boolean, original: VideoItem, ref: VideoItem, keep: Double, replace: Double) {
        if (!RootShell.isRootAvailable()) {
            Toast.makeText(this, "未获取 root 权限", Toast.LENGTH_LONG).show()
            return
        }
        val origPath = VideoLibrary.fileOf(this, original.fileName).absolutePath
        val refPath = VideoLibrary.fileOf(this, ref.fileName).absolutePath
        val outFile = java.io.File(
            VideoLibrary.dir(this),
            "dedup_${System.currentTimeMillis()}.mp4"
        )
        val outPath = outFile.absolutePath

        val toast = Toast.makeText(this, "正在合成（可能较慢）…", Toast.LENGTH_SHORT)
        toast.show()
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (isAudio) {
                    VideoDedup.dedupAudio(origPath, refPath, keep, replace, outPath)
                } else {
                    VideoDedup.dedupVideo(origPath, refPath, keep, replace, outPath)
                }
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) "合成完成，已加入视频库" else "合成失败，请查看日志",
                Toast.LENGTH_LONG
            ).show()
            refresh()
        }
    }

    // ---------------- Adapters ----------------

    inner class VideoAdapter :
        RecyclerView.Adapter<VideoAdapter.VH>() {

        private var items: List<VideoItem> = emptyList()

        fun submit(list: List<VideoItem>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tv_video_name)
            val size: TextView = view.findViewById(R.id.tv_video_size)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.name.text = item.displayName
            holder.size.text = formatSize(item.size)
            holder.itemView.setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("删除视频")
                    .setMessage("确定删除「${item.displayName}」吗？")
                    .setPositiveButton("删除") { _, _ ->
                        VideoLibrary.delete(this@MainActivity, item.fileName)
                        refresh()
                    }
                    .setNegativeButton("取消", null)
                    .show()
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }

    inner class AppAdapter :
        RecyclerView.Adapter<AppAdapter.VH>() {

        private var apps: List<TargetApp> = emptyList()
        private var selectedMap: Map<String, String> = emptyMap()

        fun submit(apps: List<TargetApp>, selectedMap: Map<String, String>) {
            this.apps = apps
            this.selectedMap = selectedMap
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.tv_app_label)
            val pkg: TextView = view.findViewById(R.id.tv_app_pkg)
            val selected: TextView = view.findViewById(R.id.tv_app_selected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = apps[position]
            holder.label.text = app.label
            holder.pkg.text = app.pkg
            val fileName = selectedMap[app.pkg]
            holder.selected.text = if (fileName != null) {
                "当前：${displayNameOf(fileName)}"
            } else {
                "当前：未选择"
            }
            holder.itemView.setOnClickListener {
                showSelectVideoDialog(app)
            }
        }

        override fun getItemCount(): Int = apps.size
    }

    private fun displayNameOf(fileName: String): String {
        return VideoLibrary.list(this).firstOrNull { it.fileName == fileName }?.displayName
            ?: fileName
    }

    private fun formatSize(bytes: Long): String {
        val df = DecimalFormat("0.0")
        return when {
            bytes >= 1024 * 1024 * 1024 -> df.format(bytes / (1024.0 * 1024 * 1024)) + " GB"
            bytes >= 1024 * 1024 -> df.format(bytes / (1024.0 * 1024)) + " MB"
            bytes >= 1024 -> df.format(bytes / 1024.0) + " KB"
            else -> "$bytes B"
        }
    }

    companion object {
        private const val REQ_PICK_VIDEO = 1001
    }
}

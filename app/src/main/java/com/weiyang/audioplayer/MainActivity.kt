package com.weiyang.audioplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.weiyang.audioplayer.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : AppCompatActivity(), Player.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: ProgressStore
    private var treeUri: Uri? = null
    private var folders: List<Folder> = emptyList()
    private var selectedFolder = 0
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playingIndex = -1
    private var lastPlayingUri: String? = null

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2
    private var repeatMode = 0 // 0 顺序 1 单曲 2 列表 3 随机
    private var lastSaved = 0L
    private var sleepEnd = 0L
    private var restored = false
    private val handler = Handler(Looper.getMainLooper())

    private val requestNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 无论授权与否都不影响播放，仅影响通知是否显示 */ }

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            prefs.saveTreeUri(uri.toString())
            treeUri = uri
            loadTree()
        } else {
            Toast.makeText(this, "未选择文件夹，无法播放音频", Toast.LENGTH_LONG).show()
        }
    }

    // 导入一个额外的文件夹（合并进列表）
    private val importTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importFolder(uri)
        else Toast.makeText(this, "未选择文件夹", Toast.LENGTH_SHORT).show()
    }

    // 导入多个音频文件（合并进「导入」文件夹）
    private val pickFiles = registerForActivityResult(OpenDocumentMultipleContract()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        val items = uris.mapNotNull { uri ->
            val name = getDisplayName(uri) ?: "音频"
            if (isAudio(name)) AudioItem(name, uri, "导入") else null
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "选择的文件里没有音频", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val mutable = folders.toMutableList()
        val existing = mutable.indexOfFirst { it.name == "导入" }
        if (existing >= 0) {
            val merged = (mutable[existing].items + items).distinctBy { it.uri.toString() }
            mutable[existing] = Folder("导入", merged)
        } else {
            mutable.add(Folder("导入", items))
        }
        folders = mutable
        folderAdapterRef.submit(folders, selectedFolder)
        if (existing >= 0 && existing == selectedFolder) {
            audioAdapterRef.submit(folders[selectedFolder].items)
            syncPlaying()
        }
        Toast.makeText(this, "已导入 ${items.size} 个音频到「导入」", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = ProgressStore(this)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.title = "未央音频播放器"

        treeUri = prefs.getTreeUri()?.let { Uri.parse(it) }
        val savedIdx = speeds.indexOfFirst { it == prefs.getSpeed() }
        speedIndex = if (savedIdx < 0) 2 else savedIdx

        setupLists()
        setupControls()

        // 先启动后台播放服务，再连接控制器
        startService(Intent(this, PlaybackService::class.java))
        connectController()

        // Android 13+ 需要运行时申请通知权限，否则锁屏/通知控制可能不显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (treeUri == null) pickTree.launch(null) else loadTree()
    }

    // ---------------- 列表与连接 ----------------
    private fun setupLists() {
        val folderAdapter = FolderAdapter { pos -> showFolder(pos) }
        this.folderAdapterRef = folderAdapter
        binding.rvFolders.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.rvFolders.adapter = folderAdapter

        val audioAdapter = AudioAdapter { _, idx -> playFolderItem(selectedFolder, idx) }
        this.audioAdapterRef = audioAdapter
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = audioAdapter
    }

    private lateinit var folderAdapterRef: FolderAdapter
    private lateinit var audioAdapterRef: AudioAdapter

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture!!.addListener({
            controller = controllerFuture!!.get()
            controller!!.addListener(this@MainActivity)
            controller!!.playbackParameters = PlaybackParameters(speeds[speedIndex])
            applyRepeat()
            handler.post(ticker)
            maybeRestore()
        }, MoreExecutors.directExecutor())
    }

    private fun loadTree() {
        val root = DocumentFile.fromTreeUri(this, treeUri!!) ?: return
        val folderDocs = root.listFiles()
            .filter { it.isDirectory }
            .sortedBy { it.name?.lowercase() ?: "" }

        folders = folderDocs.map { fd ->
            val items = fd.listFiles()
                .filter { it.isFile && isAudio(it.name ?: "") }
                .sortedBy { it.name?.lowercase() ?: "" }
                .map { AudioItem(it.name ?: "音频", it.uri, fd.name ?: "") }
            Folder(fd.name ?: "文件夹", items)
        }

        selectedFolder = 0
        prefs.getLastFolder()?.let { lf ->
            val i = folders.indexOfFirst { it.name == lf }
            if (i >= 0) selectedFolder = i
        }
        folderAdapterRef.submit(folders, selectedFolder)
        showFolder(selectedFolder)
        maybeRestore()
    }

    private fun showFolder(index: Int) {
        if (index !in folders.indices) return
        selectedFolder = index
        prefs.saveLastFolder(folders[index].name)
        folderAdapterRef.setSelected(index)
        audioAdapterRef.submit(folders[index].items)
        syncPlaying()
    }

    // ---------------- 播放控制 ----------------
    private fun playFolderItem(folderIndex: Int, itemIndex: Int) {
        val folder = folders.getOrNull(folderIndex) ?: return
        val item = folder.items.getOrNull(itemIndex) ?: return
        val c = controller ?: return
        // 点的是正在播放的那一条：直接暂停/继续，不打断进度
        if (folderIndex == selectedFolder && itemIndex == playingIndex && c.isPlaying) {
            c.pause()
            return
        }
        val items = folder.items.map { buildMediaItem(it) }
        val start = prefs.getProgress(item.uri.toString()).coerceAtLeast(0L)
        c.setMediaItems(items, itemIndex, start)
        c.prepare()
        c.play()
        playingIndex = itemIndex
        audioAdapterRef.setPlaying(itemIndex)
    }

    private fun buildMediaItem(item: AudioItem): MediaItem =
        MediaItem.Builder().setUri(item.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.name)
                    .setArtist(item.folderName)
                    .build()
            )
            .build()

    // ---------------- Player.Listener ----------------
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val title = mediaItem?.mediaMetadata?.title?.toString() ?: "未播放"
        binding.tvTitle.text = title
        val uri = mediaItem?.localConfiguration?.uri
        binding.btnFav.text =
            if (uri != null && prefs.isFavorite(uri.toString())) "★" else "☆"
        // 自动跳到下一首 => 说明上一首已经播完，把它的进度重置，下次从头播
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            lastPlayingUri?.let { prefs.clearProgress(it) }
        }
        lastPlayingUri = uri?.toString()
        syncPlaying()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // 顺序播放到最后一首播完（STATE_ENDED）=> 重置当前这条的进度
        if (playbackState == Player.STATE_ENDED) {
            controller?.currentMediaItem?.localConfiguration?.uri?.let {
                prefs.clearProgress(it.toString())
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        binding.btnPlay.text = if (isPlaying) "⏸" else "▶"
        if (!isPlaying) saveCurrent()
    }

    override fun onPlaybackParametersChanged(params: PlaybackParameters) {
        val s = params.speed
        speedIndex = (0 until speeds.size).minByOrNull { abs(speeds[it] - s) } ?: 2
        binding.btnSpeed.text = formatSpeed(s)
    }

    override fun onRepeatModeChanged(repeatMode: Int) = updateRepeatIcon()
    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateRepeatIcon()

    // 进度刷新（每 500ms）
    private val ticker = object : Runnable {
        override fun run() {
            controller?.let { c ->
                val dur = (c.duration / 1000).toInt().coerceAtLeast(1)
                binding.seekBar.max = dur
                binding.seekBar.progress = (c.currentPosition / 1000).toInt()
                binding.tvCur.text = fmt(c.currentPosition)
                binding.tvDur.text = fmt(c.duration)
                // 正在播放那一行上的进度条
                val total = c.duration
                if (total > 0) {
                    val pct = ((c.currentPosition * 100) / total).toInt().coerceIn(0, 100)
                    audioAdapterRef.setProgress(pct)
                    // 播到接近结尾就视为"播完" -> 重置进度（不依赖切歌回调，最稳）
                    if (c.currentPosition >= total - 1000) {
                        c.currentMediaItem?.localConfiguration?.uri?.let {
                            prefs.clearProgress(it.toString())
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastSaved > 4000) {
                    saveCurrent()
                    lastSaved = now
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    // ---------------- 按钮 ----------------
    private fun setupControls() {
        binding.btnPlay.setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        binding.btnPrev.setOnClickListener { controller?.seekToPrevious() }
        binding.btnNext.setOnClickListener { controller?.seekToNext() }
        binding.btnSpeed.setOnClickListener { cycleSpeed() }
        binding.btnRepeat.setOnClickListener { cycleRepeat() }
        binding.btnSleep.setOnClickListener { showSleepMenu() }
        binding.btnFav.setOnClickListener { toggleFav() }
        // 常驻的「导入」按钮（不依赖工具栏菜单，保证看得见）
        binding.btnImport.setOnClickListener { showImportChooser() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                controller?.seekTo((s?.progress?.toLong() ?: 0) * 1000)
            }
        })
    }

    // 顶栏菜单（收藏 / 导入）——用标准方式，避免菜单不显示
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_fav -> { showFavorites(); true }
        R.id.menu_import -> { showImportChooser(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        val sp = speeds[speedIndex]
        controller?.playbackParameters = PlaybackParameters(sp)
        prefs.saveSpeed(sp)
    }

    private fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 4
        applyRepeat()
    }

    private fun applyRepeat() {
        controller?.let { c ->
            when (repeatMode) {
                0 -> { c.repeatMode = Player.REPEAT_MODE_OFF; c.shuffleModeEnabled = false }
                1 -> { c.repeatMode = Player.REPEAT_MODE_ONE; c.shuffleModeEnabled = false }
                2 -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = false }
                3 -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = true }
            }
        }
        updateRepeatIcon()
    }

    private fun updateRepeatIcon() {
        val txt = when {
            controller == null -> "🔁"
            controller!!.shuffleModeEnabled -> "🔀"
            controller!!.repeatMode == Player.REPEAT_MODE_ONE -> "🔂"
            controller!!.repeatMode == Player.REPEAT_MODE_ALL -> "🔁"
            else -> "➡"
        }
        binding.btnRepeat.text = txt
    }

    private fun toggleFav() {
        controller?.currentMediaItem?.localConfiguration?.uri?.let { uri ->
            val s = uri.toString()
            prefs.toggleFavorite(s)
            binding.btnFav.text = if (prefs.isFavorite(s)) "★" else "☆"
        }
    }

    // ---------------- 睡眠定时 ----------------
    private fun showSleepMenu() {
        val opts = arrayOf("15 分钟", "30 分钟", "45 分钟", "60 分钟", "90 分钟", "关闭定时")
        AlertDialog.Builder(this)
            .setTitle("睡眠定时")
            .setItems(opts) { _, i ->
                if (i == opts.lastIndex) setSleep(0)
                else setSleep((i + 1) * 15)
            }
            .show()
    }

    private fun setSleep(min: Int) {
        if (min <= 0) {
            sleepEnd = 0
            binding.tvSleep.text = ""
            handler.removeCallbacks(sleepTicker)
            return
        }
        sleepEnd = System.currentTimeMillis() + min * 60_000L
        handler.post(sleepTicker)
    }

    private val sleepTicker = object : Runnable {
        override fun run() {
            val left = sleepEnd - System.currentTimeMillis()
            if (left <= 0) {
                controller?.pause()
                binding.tvSleep.text = ""
                return
            }
            binding.tvSleep.text = "⏱ " + fmt(left)
            handler.postDelayed(this, 1000)
        }
    }

    // ---------------- 收藏列表 ----------------
    private fun showImportChooser() {
        AlertDialog.Builder(this)
            .setTitle("导入音频")
            .setItems(arrayOf("导入文件夹", "导入文件")) { _, i ->
                if (i == 0) importTree.launch(null)
                else pickFiles.launch(arrayOf("audio/*"))
            }
            .show()
    }

    private fun showFavorites() {
        val favs = prefs.getFavorites()
        if (favs.isEmpty()) {
            Toast.makeText(this, "还没有收藏，点 ★ 收藏当前音频", Toast.LENGTH_SHORT).show()
            return
        }
        val entries = favs.map { uri -> findName(uri) ?: uri }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("我的收藏")
            .setItems(entries) { _, i -> playUri(favs.elementAt(i)) }
            .show()
    }

    private fun findName(uri: String): String? {
        for (f in folders) {
            f.items.firstOrNull { it.uri.toString() == uri }?.let { return it.name }
        }
        return null
    }

    private fun playUri(uri: String) {
        for (fi in folders.indices) {
            val idx = folders[fi].items.indexOfFirst { it.uri.toString() == uri }
            if (idx >= 0) {
                showFolder(fi)
                playFolderItem(fi, idx)
                return
            }
        }
        Toast.makeText(this, "找不到该收藏文件", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 进度保存与恢复 ----------------
    private fun saveCurrent() {
        controller?.let { c ->
            val uri = c.currentMediaItem?.localConfiguration?.uri ?: return
            val pos = c.currentPosition
            val dur = c.duration
            // 已播完 / 已到接近结尾：不保存末尾位置，改为清空进度（下次从头播）
            if (c.playbackState == Player.STATE_ENDED || (dur > 0 && pos >= dur - 1000)) {
                prefs.clearProgress(uri.toString())
                return
            }
            if (pos > 0) {
                // 每个文件单独存进度，用于断点续播
                prefs.saveProgress(uri.toString(), pos)
                // 同时记录"上次播放"，用于启动时自动恢复
                prefs.saveLast(uri.toString(), pos)
            }
        }
    }

    private fun maybeRestore() {
        if (restored) return
        if (controller == null || folders.isEmpty()) return
        val (uri, _) = prefs.getLast() ?: return
        for (fi in folders.indices) {
            val idx = folders[fi].items.indexOfFirst { it.uri.toString() == uri }
            if (idx >= 0) {
                showFolder(fi)
                val items = folders[fi].items.map { buildMediaItem(it) }
                val pos = prefs.getProgress(uri).coerceAtLeast(0L)
                controller!!.setMediaItems(items, idx, pos)
                controller!!.prepare()
                restored = true
                syncPlaying()
                return
            }
        }
    }

    /** 根据当前控制器正在播放的音频，更新列表高亮（仅当它在当前文件夹时）。 */
    private fun syncPlaying() {
        val uri = controller?.currentMediaItem?.localConfiguration?.uri?.toString()
        val idx = if (uri != null) {
            folders.getOrNull(selectedFolder)?.items
                ?.indexOfFirst { it.uri.toString() == uri } ?: -1
        } else -1
        playingIndex = idx
        audioAdapterRef.setPlaying(idx)
    }

    /** 把选中的文件夹合并进列表（同名则合并去重）。 */
    private fun importFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val doc = DocumentFile.fromTreeUri(this, uri) ?: return
        val items = doc.listFiles()
            .filter { it.isFile && isAudio(it.name ?: "") }
            .sortedBy { it.name?.lowercase() ?: "" }
            .map { AudioItem(it.name ?: "音频", it.uri, doc.name ?: "导入") }
        if (items.isEmpty()) {
            Toast.makeText(this, "该文件夹里没有音频文件", Toast.LENGTH_SHORT).show()
            return
        }
        val folder = Folder(doc.name ?: "导入", items)
        val mutable = folders.toMutableList()
        val existing = mutable.indexOfFirst { it.name == folder.name }
        if (existing >= 0) {
            val merged = (mutable[existing].items + items).distinctBy { it.uri.toString() }
            mutable[existing] = Folder(folder.name, merged)
        } else {
            mutable.add(folder)
        }
        folders = mutable
        folderAdapterRef.submit(folders, selectedFolder)
        if (existing >= 0 && existing == selectedFolder) {
            audioAdapterRef.submit(folders[selectedFolder].items)
            syncPlaying()
        }
        Toast.makeText(this, "已导入：${folder.name}（${items.size} 个）", Toast.LENGTH_SHORT).show()
    }

    /** 取文档 Uri 的显示文件名。 */
    private fun getDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = c.getString(idx)
            }
        }
        return name
    }

    // ---------------- 工具 ----------------
    private fun formatSpeed(s: Float): String {
        val str = String.format("%.2f", s)
        return str.trimEnd('0').trimEnd('.') + "×"
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = TimeUnit.MILLISECONDS.toSeconds(ms)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controller?.removeListener(this)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}

/** 一次选多个文档（部分 activity 版本没有 OpenDocumentMultiple，这里自己实现）。 */
class OpenDocumentMultipleContract : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (intent == null || resultCode != Activity.RESULT_OK) return emptyList()
        val clip = intent.clipData
        if (clip != null) {
            val list = mutableListOf<Uri>()
            for (i in 0 until clip.itemCount) list.add(clip.getItemAt(i).uri)
            return list
        }
        val single = intent.data
        return if (single != null) listOf(single) else emptyList()
    }
}

package com.weiyang.audioplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = ProgressStore(this)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.title = "未央音频播放器"

        treeUri = prefs.getTreeUri()?.let { Uri.parse(it) }
        speedIndex = speeds.indexOf(prefs.getSpeed()).let { if (it < 0) 2 else it }

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
    }

    // ---------------- 播放控制 ----------------
    private fun playFolderItem(folderIndex: Int, itemIndex: Int) {
        val folder = folders.getOrNull(folderIndex) ?: return
        val items = folder.items.map { buildMediaItem(it) }
        controller?.let { c ->
            c.setMediaItems(items, itemIndex, 0L)
            c.prepare()
            c.play()
        }
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
        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_fav -> { showFavorites(); true }
                else -> false
            }
        }

        binding.btnPlay.setOnClickListener {
            controller?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        binding.btnPrev.setOnClickListener { controller?.seekToPrevious() }
        binding.btnNext.setOnClickListener { controller?.seekToNext() }
        binding.btnSpeed.setOnClickListener { cycleSpeed() }
        binding.btnRepeat.setOnClickListener { cycleRepeat() }
        binding.btnSleep.setOnClickListener { showSleepMenu() }
        binding.btnFav.setOnClickListener { toggleFav() }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                controller?.seekTo((s?.progress?.toLong() ?: 0) * 1000)
            }
        })
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
            if (c.currentPosition > 0) prefs.saveLast(uri.toString(), c.currentPosition)
        }
    }

    private fun maybeRestore() {
        if (restored) return
        if (controller == null || folders.isEmpty()) return
        val (uri, pos) = prefs.getLast() ?: return
        for (fi in folders.indices) {
            val idx = folders[fi].items.indexOfFirst { it.uri.toString() == uri }
            if (idx >= 0) {
                showFolder(fi)
                val items = folders[fi].items.map { buildMediaItem(it) }
                controller!!.setMediaItems(items, idx, pos)
                controller!!.prepare()
                restored = true
                return
            }
        }
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

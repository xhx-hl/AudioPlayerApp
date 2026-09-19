package com.weiyang.audioplayer

import android.net.Uri
import android.provider.DocumentsContract

/** 一个文件夹（对应 26 个目录之一）及其下的音频文件。 */
data class Folder(
    val name: String,
    val items: List<AudioItem>
)

/** 单个音频文件。 */
data class AudioItem(
    val name: String,
    val uri: Uri,
    val folderName: String
)

/** 判断文件名是否为常见音频格式。 */
fun isAudio(name: String): Boolean {
    val n = name.lowercase()
    return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") ||
            n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".ogg") ||
            n.endsWith(".wma") || n.endsWith(".opus") || n.endsWith(".amr")
}

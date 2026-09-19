package com.weiyang.audioplayer

import android.content.Context
import android.content.SharedPreferences

/**
 * 用 SharedPreferences 持久化：选中的根目录、上次播放位置、变速、收藏等。
 * 这样关掉 App 再打开，能恢复到上次听的地方。
 */
class ProgressStore(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.getSharedPreferences("audioplayer", Context.MODE_PRIVATE)

    // ---- 根目录（SAF 持久授权）----
    fun saveTreeUri(s: String) = sp.edit().putString("tree", s).apply()
    fun getTreeUri(): String? = sp.getString("tree", null)

    // ---- 上次播放的文件夹 ----
    fun saveLastFolder(name: String) = sp.edit().putString("lastFolder", name).apply()
    fun getLastFolder(): String? = sp.getString("lastFolder", null)

    // ---- 上次播放的文件与位置（用于恢复）----
    fun saveLast(uri: String, pos: Long) =
        sp.edit().putString("lastUri", uri).putLong("lastPos", pos).apply()

    fun getLast(): Pair<String, Long>? {
        val u = sp.getString("lastUri", null) ?: return null
        val p = sp.getLong("lastPos", 0L)
        return u to p
    }

    // ---- 每个文件单独保存进度（断点续播）----
    fun saveProgress(uri: String, pos: Long) =
        sp.edit().putLong("pos_$uri", pos).apply()

    fun getProgress(uri: String): Long = sp.getLong("pos_$uri", 0L)

    // ---- 变速 ----
    fun saveSpeed(s: Float) = sp.edit().putFloat("speed", s).apply()
    fun getSpeed(): Float = sp.getFloat("speed", 1f)

    // ---- 收藏（★）----
    fun getFavorites(): MutableSet<String> =
        (sp.getStringSet("favs", emptySet()) ?: emptySet()).toMutableSet()

    fun toggleFavorite(uri: String) {
        val s = getFavorites()
        if (s.contains(uri)) s.remove(uri) else s.add(uri)
        sp.edit().putStringSet("favs", s).apply()
    }

    fun isFavorite(uri: String): Boolean = getFavorites().contains(uri)
}

package com.weiyang.audioplayer

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.weiyang.audioplayer.databinding.ItemAudioBinding
import com.weiyang.audioplayer.databinding.ItemFolderBinding

/** 顶部横向的文件夹列表（26 个目录）。 */
class FolderAdapter(private val onClick: (Int) -> Unit) :
    RecyclerView.Adapter<FolderAdapter.VH>() {

    private var data: List<Folder> = emptyList()
    private var selected = 0

    fun submit(list: List<Folder>, sel: Int) {
        data = list
        selected = sel
        notifyDataSetChanged()
    }

    fun setSelected(sel: Int) {
        selected = sel
        notifyDataSetChanged()
    }

    class VH(val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
        VH(ItemFolderBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val f = data[i]
        h.b.root.text = "${f.name} (${f.items.size})"
        val color = if (i == selected) R.color.folder_sel else R.color.folder_bg
        h.b.root.setBackgroundColor(ContextCompat.getColor(h.b.root.context, color))
        h.b.root.setOnClickListener { onClick(i) }
    }
}

/** 当前文件夹下的音频文件列表。 */
class AudioAdapter(private val onClick: (AudioItem, Int) -> Unit) :
    RecyclerView.Adapter<AudioAdapter.VH>() {

    private var data: List<AudioItem> = emptyList()

    fun submit(list: List<AudioItem>) {
        data = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemAudioBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(p: ViewGroup, v: Int): VH =
        VH(ItemAudioBinding.inflate(LayoutInflater.from(p.context), p, false))

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(h: VH, i: Int) {
        h.b.root.text = data[i].name
        h.b.root.setOnClickListener { onClick(data[i], i) }
    }
}

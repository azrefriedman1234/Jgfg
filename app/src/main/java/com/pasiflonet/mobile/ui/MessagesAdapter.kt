package com.pasiflonet.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.drinkless.tdlib.TdApi

class MessagesAdapter(
    private val onClick: (TdApi.Message) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val items = ArrayList<TdApi.Message>(200)

    fun submit(list: List<TdApi.Message>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        holder.title.text = "chat=${m.chatId}  id=${m.id}"
        holder.sub.text = extractText(m)
        holder.itemView.setOnClickListener { onClick(m) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(android.R.id.text1)
        val sub: TextView = v.findViewById(android.R.id.text2)
    }

    private fun extractText(m: TdApi.Message): String {
        return try {
            when (val c = m.content) {
                is TdApi.MessageText -> c.text.text
                is TdApi.MessagePhoto -> "[PHOTO]"
                is TdApi.MessageVideo -> "[VIDEO]"
                is TdApi.MessageDocument -> "[DOC]"
                is TdApi.MessageAnimation -> "[GIF]"
                is TdApi.MessageSticker -> "[STICKER]"
                else -> "[" + (c?.javaClass?.simpleName ?: "CONTENT") + "]"
            }
        } catch (_: Throwable) {
            "[MSG]"
        }
    }
}

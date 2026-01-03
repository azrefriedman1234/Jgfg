package com.pasiflonet.mobile.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pasiflonet.mobile.R
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val onClick: (MessageRow) -> Unit
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<MessageRow>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun addTop(row: MessageRow) {
        items.add(0, row)
        notifyItemInserted(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_message, parent, false)
        return VH(v, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], fmt)
    override fun getItemCount(): Int = items.size

    class VH(v: View, val onClick: (MessageRow) -> Unit) : RecyclerView.ViewHolder(v) {
        private val tvDate: TextView = v.findViewById(R.id.tvDate)
        private val tvText: TextView = v.findViewById(R.id.tvText)
        private val tvType: TextView = v.findViewById(R.id.tvType)

        fun bind(row: MessageRow, fmt: SimpleDateFormat) {
            tvDate.text = fmt.format(Date(row.dateUnix.toLong() * 1000L))
            tvText.text = row.text
            tvType.text = row.type
            itemView.setOnClickListener { onClick(row) }
        }
    }
}

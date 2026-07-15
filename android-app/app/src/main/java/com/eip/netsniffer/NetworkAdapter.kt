package com.eip.netsniffer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.eip.netsniffer.databinding.ItemNetworkBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NetworkAdapter : RecyclerView.Adapter<NetworkAdapter.VH>() {

    private var items: List<NetEntry> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun submit(list: List<NetEntry>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemNetworkBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemNetworkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.b.method.text = e.method
        h.b.status.text = if (e.status < 0) "ERR" else e.status.toString()
        h.b.status.setTextColor(
            when {
                e.status < 0 -> 0xFFD32F2F.toInt()
                e.status in 200..299 -> 0xFF2E7D32.toInt()
                e.status in 300..399 -> 0xFFF9A825.toInt()
                else -> 0xFFD32F2F.toInt()
            }
        )
        h.b.size.text = formatBytes(e.bytes)
        h.b.url.text = e.url
        h.b.meta.text = "${e.source}  •  ${e.mime ?: "-"}  •  ${e.ms} ms  •  ${timeFmt.format(Date(e.time))}"
        h.b.root.setOnClickListener {
            DetailActivity.start(it.context, e.id)
        }
    }

    override fun getItemCount() = items.size

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        else -> String.format(Locale.US, "%.2f MB", b / 1024.0 / 1024.0)
    }
}

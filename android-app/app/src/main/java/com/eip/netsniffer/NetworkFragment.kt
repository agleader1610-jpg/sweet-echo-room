package com.eip.netsniffer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.eip.netsniffer.databinding.FragmentNetworkBinding

class NetworkFragment : Fragment() {
    private var _b: FragmentNetworkBinding? = null
    private val b get() = _b!!
    private val adapter = NetworkAdapter()

    private enum class Tab { ALL, HTTP, WS }
    private var current = Tab.ALL
    private var allEntries: List<NetEntry> = emptyList()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentNetworkBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        b.list.layoutManager = LinearLayoutManager(requireContext())
        b.list.adapter = adapter

        CaptureStore.entries.observe(viewLifecycleOwner) { list ->
            allEntries = list
            applyFilter()
        }

        b.clearBtn.setOnClickListener { CaptureStore.clear() }
        b.tabAll.setOnClickListener { current = Tab.ALL; applyFilter() }
        b.tabHttp.setOnClickListener { current = Tab.HTTP; applyFilter() }
        b.tabWs.setOnClickListener { current = Tab.WS; applyFilter() }

        // Cloud upload runs automatically in the background — no UI needed.
        CaptureUploader.init(requireContext().applicationContext)

        applyFilter()
    }

    private fun applyFilter() {
        val filtered = when (current) {
            Tab.ALL -> allEntries
            Tab.HTTP -> allEntries.filter { !it.source.startsWith("ws") }
            Tab.WS -> allEntries.filter { it.source.startsWith("ws") }
        }
        adapter.submit(filtered)
        b.countText.text = "${filtered.size} / ${allEntries.size} requests"
        styleTab(b.tabAll, current == Tab.ALL)
        styleTab(b.tabHttp, current == Tab.HTTP)
        styleTab(b.tabWs, current == Tab.WS)
    }

    private fun styleTab(btn: Button, active: Boolean) {
        if (active) {
            btn.setBackgroundColor(Color.parseColor("#1565C0"))
            btn.setTextColor(Color.WHITE)
            btn.setTypeface(btn.typeface, Typeface.BOLD)
        } else {
            btn.setBackgroundColor(Color.parseColor("#ECEFF1"))
            btn.setTextColor(Color.parseColor("#455A64"))
            btn.setTypeface(Typeface.DEFAULT)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

package com.eip.netsniffer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.eip.netsniffer.databinding.FragmentProfileBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileFragment : Fragment() {
    private var _b: FragmentProfileBinding? = null
    private val b get() = _b!!
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentProfileBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        UserInfoStore.current.observe(viewLifecycleOwner) { render(it) }
        UserInfoStore.history.observe(viewLifecycleOwner) { renderHistory(it) }

        b.clearBtn.setOnClickListener { UserInfoStore.clear() }
        b.copyBtn.setOnClickListener {
            val raw = UserInfoStore.current.value?.raw ?: return@setOnClickListener
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("profile", raw))
            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun render(p: UserProfile?) {
        if (p == null) {
            b.emptyText.visibility = View.VISIBLE
            b.card.visibility = View.GONE
            return
        }
        b.emptyText.visibility = View.GONE
        b.card.visibility = View.VISIBLE
        b.nickText.text = p.nickName ?: p.userName ?: "(unknown)"
        b.uidText.text = "UID: ${p.uid ?: "-"}"
        b.balanceText.text = "৳ ${p.balance ?: "-"}"
        b.codeText.text = "Turnover / Code: ${p.amountOfCode ?: "-"}"
        b.phoneText.text = "📱 ${p.userName ?: p.phone ?: "-"}"
        b.hostText.text = p.host ?: ""
        b.updatedText.text = "Updated: ${timeFmt.format(Date(p.updatedAt))}"
    }

    private fun renderHistory(list: List<UserProfile>) {
        if (list.isEmpty()) { b.historyText.text = "(none)"; return }
        b.historyText.text = list.take(30).joinToString("\n") { p ->
            "${timeFmt.format(Date(p.updatedAt))}  " +
                "bal=${p.balance ?: "-"}  code=${p.amountOfCode ?: "-"}  " +
                "uid=${p.uid ?: "-"}"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

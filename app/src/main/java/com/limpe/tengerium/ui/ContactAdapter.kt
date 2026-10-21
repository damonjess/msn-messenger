package com.limpe.tengerium.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.limpe.tengerium.R
import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.ItemContactGroupBinding
import com.limpe.tengerium.databinding.ItemDateHeaderBinding
import com.limpe.tengerium.databinding.ItemRequestsHeaderBinding
import com.limpe.tengerium.databinding.ItemUserRowBinding
import com.limpe.tengerium.domain.model.Contact
import com.limpe.tengerium.domain.model.Group
import com.limpe.tengerium.util.FormattingUtils
import java.util.*

class ContactAdapter(
    private val securePrefs: SecurePrefs,
    private val onClick: (Contact) -> Unit,
    private val onLongClick: (View, Contact) -> Unit,
    private val onRequestsClick: () -> Unit,
    private val onGroupClick: (Group) -> Unit,
    private val onGroupLongClick: (View, Group) -> Unit
) : ListAdapter<ContactAdapter.ContactItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private var highlightedAccount: String? = null

    sealed class ContactItem {
        data class User(val contact: Contact, val isMuted: Boolean = false) : ContactItem()
        data class GroupHeader(val group: Group, val onlineCount: Int, val totalCount: Int, val isExpanded: Boolean) : ContactItem()
        data class Header(val count: Int) : ContactItem()
        object NotConnected : ContactItem()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ContactItem.User -> 0
            is ContactItem.GroupHeader -> 1
            is ContactItem.Header -> 2
            is ContactItem.NotConnected -> 3
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> ContactViewHolder(ItemUserRowBinding.inflate(inflater, parent, false))
            1 -> GroupViewHolder(ItemContactGroupBinding.inflate(inflater, parent, false))
            2 -> HeaderViewHolder(ItemRequestsHeaderBinding.inflate(inflater, parent, false))
            else -> NotConnectedViewHolder(ItemDateHeaderBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ContactItem.User -> {
                val isSelected = item.contact.account.equals(highlightedAccount, ignoreCase = true)
                (holder as ContactViewHolder).bind(item.contact, item.isMuted, isSelected)
                holder.itemView.setOnClickListener {
                    setHighlightedAccount(item.contact.account)
                    onClick(item.contact)
                }
                holder.itemView.setOnLongClickListener {
                    setHighlightedAccount(item.contact.account)
                    onLongClick(it, item.contact)
                    true
                }
            }
            is ContactItem.GroupHeader -> {
                (holder as GroupViewHolder).bind(item.group, item.onlineCount, item.totalCount, item.isExpanded)
                holder.itemView.setOnClickListener { onGroupClick(item.group) }
                holder.itemView.setOnLongClickListener {
                    onGroupLongClick(it, item.group)
                    true
                }
            }
            is ContactItem.Header -> {
                (holder as HeaderViewHolder).bind(item.count)
                holder.itemView.setOnClickListener { onRequestsClick() }
            }
            is ContactItem.NotConnected -> {
                (holder as NotConnectedViewHolder).bind()
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val item = getItem(position)
            if (item is ContactItem.User && holder is ContactViewHolder) {
                payloads.forEach { payload ->
                    if (payload is Bundle) {
                        if (payload.containsKey("status") || payload.containsKey("nickname")) {
                            holder.updateTextAndStatus(item.contact, item.contact.account.equals(highlightedAccount, ignoreCase = true))
                        }
                        if (payload.containsKey("mute")) {
                            holder.updateMuteState(payload.getBoolean("mute"))
                        }
                    }
                }
            }
        }
    }

    fun setHighlightedAccount(account: String?) {
        val old = highlightedAccount
        highlightedAccount = account

        val oldPos = currentList.indexOfFirst { it is ContactItem.User && it.contact.account.equals(old, ignoreCase = true) }
        if (oldPos != -1) notifyItemChanged(oldPos)

        val newPos = currentList.indexOfFirst { it is ContactItem.User && it.contact.account.equals(account, ignoreCase = true) }
        if (newPos != -1) notifyItemChanged(newPos)
    }

    class ContactViewHolder(private val binding: ItemUserRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(contact: Contact, isMuted: Boolean, isSelected: Boolean) {
            updateTextAndStatus(contact, isSelected)
            updateMuteState(isMuted)
        }

        fun updateTextAndStatus(contact: Contact, isSelected: Boolean) {
            val context = itemView.context
            val nickname = FormattingUtils.formatBBCode(contact.nickname.ifEmpty { contact.account })
            val status = contact.status

            val statusRes = StatusUtils.getStatusStringRes(status)
            val statusStr = context.getString(statusRes)

            if (status != MSNPProto.Status.OFFLINE && status != MSNPProto.Status.HIDDEN && !status.equals("FLN", ignoreCase = true)) {
                binding.tvTitle.text = "$nickname ($statusStr)"
            } else {
                binding.tvTitle.text = nickname
            }

            // MSN Status Icon
            when (status) {
                MSNPProto.Status.ONLINE -> binding.ivMsnStatusIcon.setImageResource(R.drawable.ic_msn_online)
                MSNPProto.Status.BUSY, MSNPProto.Status.PHONE -> binding.ivMsnStatusIcon.setImageResource(R.drawable.ic_msn_busy)
                MSNPProto.Status.AWAY, MSNPProto.Status.BRB, MSNPProto.Status.LUNCH -> binding.ivMsnStatusIcon.setImageResource(R.drawable.ic_msn_away)
                else -> binding.ivMsnStatusIcon.setImageResource(R.drawable.ic_msn_offline)
            }

            // Classic MSN Selection Box (#316AC5)
            if (isSelected) {
                binding.rowRootLayout.setBackgroundColor(Color.parseColor("#316AC5"))
                binding.tvTitle.setTextColor(Color.WHITE)
            } else {
                binding.rowRootLayout.setBackgroundColor(Color.TRANSPARENT)
                binding.tvTitle.setTextColor(Color.BLACK)
            }
        }

        fun updateMuteState(isMuted: Boolean) {
            binding.ivMuted.visibility = if (isMuted) View.VISIBLE else View.GONE
        }
    }

    class GroupViewHolder(private val binding: ItemContactGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: Group, onlineCount: Int, totalCount: Int, isExpanded: Boolean) {
            binding.tvGroupName.text = group.name
            binding.tvGroupCount.text = "($totalCount)"
            binding.ivExpand.setImageResource(if (isExpanded) R.drawable.ic_msn_chevron else R.drawable.ic_msn_chevron_down)
        }
    }

    class HeaderViewHolder(private val binding: ItemRequestsHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(count: Int) {
            binding.tvRequestsCount.text = count.toString()
            binding.tvRequestsCount.visibility = if (count > 0) View.VISIBLE else View.GONE
        }
    }

    class NotConnectedViewHolder(private val binding: ItemDateHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.tvDateHeader.text = itemView.context.getString(R.string.not_connected_message)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ContactItem>() {
        override fun areItemsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return when {
                oldItem is ContactItem.User && newItem is ContactItem.User ->
                    oldItem.contact.account.lowercase(Locale.ROOT) == newItem.contact.account.lowercase(Locale.ROOT)
                oldItem is ContactItem.GroupHeader && newItem is ContactItem.GroupHeader ->
                    oldItem.group.guid == newItem.group.guid
                oldItem is ContactItem.Header && newItem is ContactItem.Header -> true
                oldItem is ContactItem.NotConnected && newItem is ContactItem.NotConnected -> true
                else -> false
            }
        }

        override fun areContentsTheSame(oldItem: ContactItem, newItem: ContactItem): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: ContactItem, newItem: ContactItem): Any? {
            if (oldItem is ContactItem.User && newItem is ContactItem.User) {
                val diff = Bundle()
                if (oldItem.contact.status != newItem.contact.status) diff.putString("status", newItem.contact.status)
                if (oldItem.contact.nickname != newItem.contact.nickname) diff.putString("nickname", newItem.contact.nickname)
                if (oldItem.contact.avatarUrl != newItem.contact.avatarUrl) diff.putString("avatar", newItem.contact.avatarUrl)
                if (oldItem.isMuted != newItem.isMuted) diff.putBoolean("mute", newItem.isMuted)
                return if (diff.isEmpty) null else diff
            }
            return null
        }
    }
}

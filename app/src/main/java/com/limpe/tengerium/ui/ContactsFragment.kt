package com.limpe.tengerium.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.AppConfig
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.databinding.FragmentContactsBinding
import com.limpe.tengerium.databinding.LayoutChatMenuBinding
import com.limpe.tengerium.domain.model.Contact
import com.limpe.tengerium.domain.model.Group
import com.limpe.tengerium.util.AnimationHelper
import com.limpe.tengerium.util.SoundUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.Locale

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var repository: com.limpe.tengerium.data.MSNPRepository
    private lateinit var securePrefs: com.limpe.tengerium.data.security.SecurePrefs
    private lateinit var adapter: ContactAdapter
    private val mainViewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    private var isScrollRestored = false
    private val collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    private val searchQuery = MutableStateFlow("")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        repository = (requireActivity().application as TengeriumApp).repository
        securePrefs = com.limpe.tengerium.data.security.SecurePrefs(requireContext())
        
        collapsedGroups.value = securePrefs.getCollapsedGroups()

        setupRecyclerView()
        setupFab()
        setupIWantToPanel()
        setupBanner()
        observeData()
    }

    private fun setupIWantToPanel() {
        binding.btnMsnIWantToHeader.setOnClickListener {
            val isVisible = binding.layoutMsnIWantToContent.isVisible
            binding.layoutMsnIWantToContent.isVisible = !isVisible
            binding.ivMsnIWantToChevron.setImageResource(
                if (!isVisible) R.drawable.ic_msn_chevron else R.drawable.ic_msn_chevron_down
            )
        }

        binding.btnMsnAddContact.setOnClickListener { navigateToAddFriend() }

        binding.btnMsnSendIm.setOnClickListener {
            val mainFragment = findMainFragment()
            if (mainFragment != null) {
                mainFragment.binding.viewPager.currentItem = 1
            }
        }

        binding.btnMsnSendFile.setOnClickListener {
            val mainFragment = findMainFragment()
            if (mainFragment != null) {
                mainFragment.showDetail(AvatarLibraryFragment(), addToBackStack = true)
            } else {
                findNavController().navigate(R.id.AvatarLibraryFragment)
            }
        }

        binding.btnMsnPlayGame.setOnClickListener {
            SoundUtils.playSound(requireContext(), R.raw.nudge)
            val mainFragment = findMainFragment()
            if (mainFragment != null) {
                mainFragment.showDetail(EasterEggFragment(), addToBackStack = true)
            } else {
                findNavController().navigate(R.id.EasterEggFragment)
            }
        }

        binding.btnMsnSearchContact.setOnClickListener {
            showContactSearchDialog()
        }

        binding.btnMsnMore.setOnClickListener {
            val mainFragment = findMainFragment()
            if (mainFragment != null) {
                mainFragment.showDetail(ChatSettingsFragment(), addToBackStack = true)
            } else {
                findNavController().navigate(R.id.ChatSettingsFragment)
            }
        }
    }

    private fun showContactSearchDialog() {
        val previousQuery = searchQuery.value
        val input = EditText(requireContext()).apply {
            hint = "Name or email address"
            setSingleLine(true)
            setText(searchQuery.value)
            setSelection(text.length)
        }
        input.addTextChangedListener { editable ->
            searchQuery.value = editable?.toString()?.trim().orEmpty()
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Find a contact")
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Clear search") { _, _ -> searchQuery.value = "" }
            .setNegativeButton(android.R.string.cancel) { _, _ -> searchQuery.value = previousQuery }
            .show()
    }
    private fun setupBanner() {
        val openAbout = {
            val mainFragment = findMainFragment()
            if (mainFragment != null) {
                mainFragment.showDetail(AboutFragment(), addToBackStack = true)
            } else {
                findNavController().navigate(R.id.AboutFragment)
            }
        }
        binding.btnMsnJoin.setOnClickListener { openAbout() }
        binding.layoutMsnBanner.setOnClickListener { openAbout() }
    }

    private fun setupRecyclerView() {
        adapter = ContactAdapter(
            securePrefs = securePrefs,
            onClick = { contact ->
                val mainFragment = findMainFragment()
                if (mainFragment != null) {
                    mainFragment.openChatOnTablet(contact.account)
                } else {
                    val bundle = bundleOf("account" to contact.account)
                    findNavController().navigate(R.id.action_MainFragment_to_ChatFragment, bundle)
                }
            },
            onLongClick = { anchor, contact -> showContactContextMenu(anchor, contact) },
            onRequestsClick = {
                val mainFragment = findMainFragment()
                if (mainFragment != null) {
                    mainFragment.showDetail(RequestsFragment(), addToBackStack = true)
                } else {
                    findNavController().navigate(R.id.action_MainFragment_to_RequestsFragment)
                }
            },
            onGroupClick = { group ->
                val current = collapsedGroups.value.toMutableSet()
                if (current.contains(group.guid)) current.remove(group.guid)
                else current.add(group.guid)
                collapsedGroups.value = current
                securePrefs.setCollapsedGroups(current)
            },
            onGroupLongClick = { view, group -> showGroupContextMenu(view, group) }
        )
        binding.rvContacts.layoutManager = LinearLayoutManager(context)
        binding.rvContacts.adapter = adapter

        binding.rvContacts.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                val firstVisibleItemView = layoutManager?.getChildAt(0)
                if (firstVisibleItemView != null) {
                    mainViewModel.contactsScrollPosition = layoutManager.findFirstVisibleItemPosition()
                    mainViewModel.contactsScrollOffset = firstVisibleItemView.top
                }
            }
        })
    }

    private fun isOnline(): Boolean = repository.loginState.value is MSNPLoginState.Success

    private fun showContactContextMenu(anchor: View, contact: Contact) {
        val menuBinding = LayoutChatMenuBinding.inflate(layoutInflater)
        val popup = PopupWindow(menuBinding.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        
        popup.elevation = 20f

        menuBinding.btnMenuPin.isVisible = false
        menuBinding.btnMenuNudge.isVisible = false
        menuBinding.btnMenuExport.isVisible = false
        menuBinding.btnMenuClear.isVisible = false

        val allGroups = repository.groups.value
        val availableGroups = allGroups.filter { group -> !contact.groupGuids.contains(group.guid) }
        
        val online = isOnline()

        menuBinding.btnMenuFolder.isVisible = availableGroups.isNotEmpty()
        menuBinding.btnMenuFolder.isEnabled = online
        menuBinding.btnMenuFolder.alpha = if (online) 1.0f else 0.5f
        menuBinding.btnMenuFolder.setOnClickListener {
            popup.dismiss()
            showChangeGroupDialog(contact, availableGroups)
        }

        val isInAnyGroup = contact.groupGuids.isNotEmpty()
        menuBinding.btnMenuRemoveFolder.isVisible = isInAnyGroup
        menuBinding.btnMenuRemoveFolder.isEnabled = online
        menuBinding.btnMenuRemoveFolder.alpha = if (online) 1.0f else 0.5f
        menuBinding.btnMenuRemoveFolder.setOnClickListener {
            popup.dismiss()
            val contactGuid = contact.guid
            if (contactGuid != null) {
                contact.groupGuids.forEach { groupGuid ->
                    repository.removeContactFromGroup(contact.account, contactGuid, groupGuid)
                }
            }
        }

        val isMuted = securePrefs.isChatMuted(contact.account)
        menuBinding.btnMenuMute.isVisible = true
        menuBinding.btnMenuMute.text = if (isMuted) getString(R.string.unmute_notifications) else getString(R.string.mute_notifications)
        menuBinding.btnMenuMute.setIconResource(if (isMuted) R.drawable.ic_notifications_on else R.drawable.ic_notifications_off)
        menuBinding.btnMenuMute.setOnClickListener {
            securePrefs.toggleMuteChat(contact.account)
            popup.dismiss()
        }

        val isBlocked = contact.listType.contains("BL")
        menuBinding.btnMenuBlock.isVisible = true
        menuBinding.btnMenuBlock.isEnabled = online
        menuBinding.btnMenuBlock.alpha = if (online) 1.0f else 0.5f
        menuBinding.btnMenuBlock.text = if (isBlocked) getString(R.string.unblock) else getString(R.string.block)
        menuBinding.btnMenuBlock.setIconResource(if (isBlocked) R.drawable.ic_unblock else R.drawable.ic_block)
        menuBinding.btnMenuBlock.setOnClickListener {
            if (isBlocked) repository.unblockContact(contact.account) else repository.blockContact(contact.account)
            popup.dismiss()
        }

        menuBinding.root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val xOffset = -menuBinding.root.measuredWidth / 2 + anchor.width / 2
        val yOffset = -anchor.height / 2
        
        AnimationHelper.showSmartPopup(popup, anchor, xOffset, yOffset)
    }

    private fun showGroupContextMenu(view: View, group: Group) {
        if (group.guid == "online" || group.guid == "not_online" || group.guid == "other") return
        val popup = PopupMenu(requireContext(), view)
        val deleteItem = popup.menu.add(getString(R.string.delete))
        
        val online = isOnline()
        deleteItem.isEnabled = online
        
        popup.setOnMenuItemClickListener { item ->
            if (item.title == getString(R.string.delete)) {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.delete)
                    .setMessage(R.string.delete_group_confirm)
                    .setPositiveButton(R.string.delete) { _, _ -> repository.deleteGroup(group.guid) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        popup.show()
    }

    private fun showChangeGroupDialog(contact: Contact, availableGroups: List<Group>) {
        if (availableGroups.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_groups_error, Toast.LENGTH_SHORT).show()
            return
        }
        val groupNames = availableGroups.map { it.name }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.move_to_group_title, contact.nickname.ifEmpty { contact.account }))
            .setItems(groupNames) { _, which ->
                val targetGroup = availableGroups[which]
                val contactGuid = contact.guid
                if (contactGuid != null) {
                    repository.addContactToGroup(contact.account, contactGuid, targetGroup.guid)
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    repository.contacts,
                    repository.groups,
                    repository.pendingRequests,
                    repository.loginState,
                    combine(
                        collapsedGroups,
                        securePrefs.prefsChangedFlow.onStart { emit("init") },
                        searchQuery
                    ) { collapsed, _, query -> collapsed to query }
                ) { contacts, groups, requests, loginState, rosterState ->
                    buildItemList(contacts, groups, requests, loginState, rosterState.first, rosterState.second)
                }.collect { (items, requestsCount, isConnected) ->
                    val binding = _binding ?: return@collect
                    adapter.submitList(items) {
                        val innerBinding = _binding ?: return@submitList
                        if (!isScrollRestored) {
                            if (mainViewModel.contactsScrollPosition != 0 || mainViewModel.contactsScrollOffset != 0) {
                                (innerBinding.rvContacts.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(mainViewModel.contactsScrollPosition, mainViewModel.contactsScrollOffset)
                            }
                            isScrollRestored = true
                        }
                        innerBinding.rvContacts.alpha = 1f
                    }
                    val hasContacts = items.any { item ->
                        item is ContactAdapter.ContactItem.User ||
                            (item is ContactAdapter.ContactItem.GroupHeader && item.totalCount > 0)
                    }
                    val searching = searchQuery.value.isNotBlank()
                    val showEmpty = isConnected &&
                        ((!hasContacts && searching) || (!hasContacts && !searching && requestsCount <= 0))
                    binding.layoutEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    if (showEmpty) {
                        val title = binding.layoutEmpty.getChildAt(0) as? android.widget.TextView
                        val message = binding.layoutEmpty.getChildAt(1) as? android.widget.TextView
                        title?.setText(if (searching) R.string.no_search_results_title else R.string.empty_contacts_title)
                        message?.setText(if (searching) R.string.no_search_results_message else R.string.empty_contacts_message)
                    }
                    binding.layoutNotConnected.visibility = if (!isConnected && !AppConfig.OFFCLIS) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun buildItemList(
        contacts: List<Contact>,
        groups: List<Group>,
        requests: List<Contact>,
        loginState: MSNPLoginState,
        collapsed: Set<String>,
        query: String
    ): Triple<List<ContactAdapter.ContactItem>, Int, Boolean> {
        val items = mutableListOf<ContactAdapter.ContactItem>()
        val isConnected = loginState is MSNPLoginState.Success
        val myAcc = repository.getSavedAccount()?.lowercase(Locale.ROOT)?.trim()
        
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val filteredContacts = contacts.filter { contact ->
            contact.account.lowercase(Locale.ROOT).trim() != myAcc &&
                (normalizedQuery.isEmpty() || contact.nickname.contains(normalizedQuery, ignoreCase = true) ||
                    contact.account.contains(normalizedQuery, ignoreCase = true))
        }
        val collapsedForDisplay = if (normalizedQuery.isEmpty()) collapsed else emptySet()
        
        if (requests.isNotEmpty()) {
            val filteredRequests = requests.filter { it.account.lowercase(Locale.ROOT).trim() != myAcc }
            if (filteredRequests.isNotEmpty()) items.add(ContactAdapter.ContactItem.Header(filteredRequests.size))
        }

        if (!isConnected && !AppConfig.OFFCLIS) {
            return Triple(items, requests.size, isConnected)
        }

        val displayedAccounts = mutableSetOf<String>()

        if (groups.isNotEmpty()) {
            groups.forEach { group ->
                val groupContacts = filteredContacts.filter { it.groupGuids.contains(group.guid) }
                val onlineInGroup = groupContacts.filter { it.status != "FLN" && it.status != "HDN" && it.status != "OFFLINE" }.sortedBy { it.nickname.lowercase(Locale.ROOT) }
                val offlineInGroup = groupContacts.filter { it.status == "FLN" || it.status == "HDN" || it.status == "OFFLINE" }.sortedBy { it.nickname.lowercase(Locale.ROOT) }
                val isExpanded = !collapsedForDisplay.contains(group.guid)
                
                items.add(ContactAdapter.ContactItem.GroupHeader(group, onlineInGroup.size, groupContacts.size, isExpanded))
                
                if (isExpanded) {
                    onlineInGroup.forEach { 
                        items.add(ContactAdapter.ContactItem.User(it, securePrefs.isChatMuted(it.account)))
                        displayedAccounts.add(it.account.lowercase(Locale.ROOT))
                    }
                    offlineInGroup.forEach { 
                        items.add(ContactAdapter.ContactItem.User(it, securePrefs.isChatMuted(it.account)))
                        displayedAccounts.add(it.account.lowercase(Locale.ROOT))
                    }
                } else {
                    groupContacts.forEach { displayedAccounts.add(it.account.lowercase(Locale.ROOT)) }
                }
            }
        }

        val remainingContacts = filteredContacts.filter { !displayedAccounts.contains(it.account.lowercase(Locale.ROOT)) }
        val onlineOther = remainingContacts.filter { it.status != "FLN" && it.status != "HDN" && it.status != "OFFLINE" }.sortedBy { it.nickname.lowercase(Locale.ROOT) }
        val offlineOther = remainingContacts.filter { it.status == "FLN" || it.status == "HDN" || it.status == "OFFLINE" }.sortedBy { it.nickname.lowercase(Locale.ROOT) }

        // Classic MSN Group 1: Online
        val isOnlineExpanded = !collapsedForDisplay.contains("online")
        items.add(ContactAdapter.ContactItem.GroupHeader(
            Group("online", "Online", emptyList()),
            onlineOther.size,
            onlineOther.size,
            isOnlineExpanded
        ))
        if (isOnlineExpanded) {
            onlineOther.forEach { items.add(ContactAdapter.ContactItem.User(it, securePrefs.isChatMuted(it.account))) }
        }

        // Classic MSN Group 2: Not Online
        val isOfflineExpanded = !collapsedForDisplay.contains("not_online")
        items.add(ContactAdapter.ContactItem.GroupHeader(
            Group("not_online", "Not Online", emptyList()),
            0,
            offlineOther.size,
            isOfflineExpanded
        ))
        if (isOfflineExpanded) {
            offlineOther.forEach { items.add(ContactAdapter.ContactItem.User(it, securePrefs.isChatMuted(it.account))) }
        }

        return Triple(items, requests.size, isConnected)
    }

    private fun setupFab() {
        binding.fabAddContact.setOnClickListener {
            navigateToAddFriend()
        }
    }

    private fun navigateToAddFriend() {
        val mainFragment = findMainFragment()
        if (mainFragment != null) {
            mainFragment.showDetail(AddContactFragment(), addToBackStack = true)
        } else {
            findNavController().navigate(R.id.action_MainFragment_to_AddContactFragment)
        }
    }

    private fun findMainFragment(): MainFragment? {
        var parent = parentFragment
        while (parent != null) {
            if (parent is MainFragment) return parent
            parent = parent.parentFragment
        }
        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

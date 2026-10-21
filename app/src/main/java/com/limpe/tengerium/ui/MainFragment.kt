package com.limpe.tengerium.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayoutMediator
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentMainWithTabsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainFragment : Fragment() {

    private var _binding: FragmentMainWithTabsBinding? = null
    val binding get() = _binding!!

    var isTablet: Boolean = false
        private set

    private lateinit var securePrefs: SecurePrefs
    private var pagerAdapter: MainPagerAdapter? = null
    private var tabLayoutMediator: TabLayoutMediator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMainWithTabsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repository = (requireActivity().application as TengeriumApp).repository
        securePrefs = SecurePrefs(requireContext())
        
        binding.slidingPaneLayout.lockMode = SlidingPaneLayout.LOCK_MODE_LOCKED_CLOSED
        
        val onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeDetail()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)

        binding.slidingPaneLayout.addPanelSlideListener(object : SlidingPaneLayout.PanelSlideListener {
            override fun onPanelSlide(panel: View, slideOffset: Float) {}
            override fun onPanelOpened(panel: View) {
                onBackPressedCallback.isEnabled = true
                updateSlidingPaneLockMode()
            }
            override fun onPanelClosed(panel: View) {
                onBackPressedCallback.isEnabled = false
                binding.slidingPaneLayout.lockMode = SlidingPaneLayout.LOCK_MODE_LOCKED_CLOSED
                if (binding.slidingPaneLayout.isSlideable) {
                    clearTemporaryDetails()
                }
            }
        })

        childFragmentManager.addOnBackStackChangedListener {
            updateSlidingPaneLockMode()
        }

        isTablet = !binding.slidingPaneLayout.isSlideable

        pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        setupTabs()
        applyDynamicDesign()
        setupMsnHeaderAndSidebar(repository)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.loginState.collectLatest { state ->
                    updateConnectionStatus(state is MSNPLoginState.Loading || state is MSNPLoginState.Reconnecting)
                }
            }
        }

        observeUnreadCount(repository)
    }

    private fun setupMsnHeaderAndSidebar(repository: MSNPRepository) {
        binding.btnMsnStatusDropdown?.setOnClickListener { view ->
            showMsnStatusMenu(view, repository)
        }

        binding.btnMsnEmailLink?.setOnClickListener {
            showDetail(RequestsFragment(), addToBackStack = true)
        }

        binding.layoutMsnAvatarContainer?.setOnClickListener {
            showDetail(ProfileFragment(), addToBackStack = true)
        }

        binding.btnMsnMinimize?.setOnClickListener {
            requireActivity().moveTaskToBack(true)
        }
        binding.btnMsnMaximize?.setOnClickListener {
            showDetail(ProfileFragment(), addToBackStack = true)
        }
        binding.btnMsnClose?.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("MSN Messenger")
                .setMessage("Are you sure you want to sign out?")
                .setPositiveButton(R.string.logout) { _, _ ->
                    repository.logout()
                    findNavController().navigate(R.id.AuthFragment)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        setupMsnMenuBar(repository)
        setupMsnSidebar()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(repository.loginState, repository.myStatus) { state, status ->
                        Pair(state, status)
                    }.collectLatest { (state, status) ->
                        if (state is MSNPLoginState.Success) {
                            val acc = state.account
                            val nick = state.nickname.ifEmpty { securePrefs.getNickname(acc) ?: acc }
                            val statusStr = getString(StatusUtils.getStatusStringRes(status))
                            binding.tvMsnUserDisplayName?.text = "$nick ($statusStr)"

                            if (!state.avatarUrl.isNullOrEmpty()) {
                                binding.ivMsnGreenBuddy?.isVisible = false
                                binding.msnAvatarStatusView?.isVisible = true
                                binding.msnAvatarStatusView?.setAvatar(state.avatarUrl, acc)
                                binding.msnAvatarStatusView?.setStatus(status)
                            } else {
                                binding.ivMsnGreenBuddy?.isVisible = true
                                binding.msnAvatarStatusView?.isVisible = false
                            }
                        }
                    }
                }

                launch {
                    repository.pendingRequests.collectLatest { requests ->
                        if (requests.isNotEmpty()) {
                            binding.tvMsnEmailStatus?.text = "${requests.size} pending request(s)"
                        } else {
                            binding.tvMsnEmailStatus?.text = "No new e-mail messages"
                        }
                    }
                }
            }
        }
    }

    private fun showMsnStatusMenu(anchor: View, repository: MSNPRepository) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        val statuses = listOf(
            getString(R.string.online) to MSNPProto.Status.ONLINE,
            getString(R.string.busy) to MSNPProto.Status.BUSY,
            getString(R.string.away) to MSNPProto.Status.AWAY,
            getString(R.string.brb) to MSNPProto.Status.BRB,
            getString(R.string.on_phone) to MSNPProto.Status.PHONE,
            getString(R.string.out_to_lunch) to MSNPProto.Status.LUNCH,
            getString(R.string.offline) to MSNPProto.Status.HIDDEN
        )
        statuses.forEachIndexed { index, (label, _) ->
            popup.menu.add(0, index, index, label)
        }
        popup.setOnMenuItemClickListener { item ->
            val status = statuses.getOrNull(item.itemId)?.second
            if (status != null) {
                repository.setStatus(status)
            }
            true
        }
        popup.show()
    }

    private fun setupMsnMenuBar(repository: MSNPRepository) {
        binding.tvMenuFile?.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), view)
            popup.menu.add("My Profile")
            popup.menu.add("Sign Out")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "My Profile" -> showDetail(ProfileFragment(), addToBackStack = true)
                    "Sign Out" -> {
                        repository.logout()
                        findNavController().navigate(R.id.AuthFragment)
                    }
                }
                true
            }
            popup.show()
        }

        binding.tvMenuContacts?.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), view)
            popup.menu.add("Add a Contact")
            popup.menu.add("Contact Requests")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Add a Contact" -> showDetail(AddContactFragment(), addToBackStack = true)
                    "Contact Requests" -> showDetail(RequestsFragment(), addToBackStack = true)
                }
                true
            }
            popup.show()
        }

        binding.tvMenuActions?.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), view)
            popup.menu.add("Send Instant Message")
            popup.menu.add("Change My Status")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Send Instant Message" -> binding.viewPager.currentItem = 1
                    "Change My Status" -> showMsnStatusMenu(view, repository)
                }
                true
            }
            popup.show()
        }

        binding.tvMenuTools?.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), view)
            popup.menu.add("App Settings")
            popup.menu.add("Data and Storage")
            popup.menu.add("Avatar Library")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "App Settings" -> showDetail(ChatSettingsFragment(), addToBackStack = true)
                    "Data and Storage" -> showDetail(DataAndStorageFragment(), addToBackStack = true)
                    "Avatar Library" -> showDetail(AvatarLibraryFragment(), addToBackStack = true)
                }
                true
            }
            popup.show()
        }

        binding.tvMenuHelp?.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), view)
            popup.menu.add("About MSN Messenger")
            popup.menu.add("Check Services")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "About MSN Messenger" -> showDetail(AboutFragment(), addToBackStack = true)
                    "Check Services" -> showDetail(ServicesFragment(), addToBackStack = true)
                }
                true
            }
            popup.show()
        }
    }

    private fun setupMsnSidebar() {
        binding.btnSideBell?.setOnClickListener {
            com.limpe.tengerium.util.SoundUtils.playSound(requireContext(), R.raw.nudge)
        }
        binding.btnSideSmiley?.setOnClickListener {
            showDetail(AvatarLibraryFragment(), addToBackStack = true)
        }
        binding.btnSideGraph?.setOnClickListener {
            showDetail(TrafficUsageFragment(), addToBackStack = true)
        }
        binding.btnSidePlane?.setOnClickListener {
            showDetail(ServicesFragment(), addToBackStack = true)
        }
        binding.btnSideCar?.setOnClickListener {
            showDetail(MemoryUsageFragment(), addToBackStack = true)
        }
        binding.btnSideBag?.setOnClickListener {
            showDetail(DataAndStorageFragment(), addToBackStack = true)
        }
        binding.btnSideGlass?.setOnClickListener {
            showDetail(ChatSettingsFragment(), addToBackStack = true)
        }
        binding.btnSideLogo?.setOnClickListener {
            showDetail(AboutFragment(), addToBackStack = true)
        }
    }

    private fun observeUnreadCount(repository: MSNPRepository) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getUnreadChatsCount().collectLatest { count ->
                    updateTabBadge(1, count)
                }
            }
        }
    }

    private fun updateTabBadge(tabIndex: Int, count: Int) {
        val tab = binding.tabLayout.getTabAt(tabIndex) ?: return
        val customView = tab.customView ?: return
        val badgeFrame = customView.findViewById<View>(R.id.tabBadge) ?: return
        val badgeText = customView.findViewById<TextView>(R.id.tabBadgeText) ?: return

        if (count > 0) {
            badgeFrame.visibility = View.VISIBLE
            badgeText.text = if (count > 9) "9+" else count.toString()
        } else {
            badgeFrame.visibility = View.GONE
        }
    }

    private fun updateSlidingPaneLockMode() {
        if (!binding.slidingPaneLayout.isSlideable) return
        
        val hasDetail = childFragmentManager.findFragmentById(R.id.detail_container) != null
        val hasBackStack = childFragmentManager.backStackEntryCount > 0

        binding.slidingPaneLayout.lockMode = when {
            !hasDetail -> SlidingPaneLayout.LOCK_MODE_LOCKED_CLOSED
            hasBackStack -> SlidingPaneLayout.LOCK_MODE_LOCKED_OPEN
            else -> SlidingPaneLayout.LOCK_MODE_UNLOCKED
        }
    }

    private fun clearTemporaryDetails() {
        val current = childFragmentManager.findFragmentById(R.id.detail_container)
        if (current != null) {
            clearAllDetails()
        }
    }

    fun showDetail(fragment: Fragment, addToBackStack: Boolean = false) {
        val currentFragment = childFragmentManager.findFragmentById(R.id.detail_container)
        val shouldAddToBackStack = addToBackStack && currentFragment != null

        childFragmentManager.commit {
            setCustomAnimations(
                R.anim.metro_enter,
                R.anim.metro_exit,
                R.anim.metro_pop_enter,
                R.anim.metro_pop_exit
            )
            setReorderingAllowed(true)
            if (currentFragment != null) {
                add(R.id.detail_container, fragment)
            } else {
                replace(R.id.detail_container, fragment)
            }
            
            if (shouldAddToBackStack) {
                addToBackStack(null)
            }
        }
        binding.slidingPaneLayout.openPane()
        updateSlidingPaneLockMode()
    }

    fun closeDetail() {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStackImmediate()
            updateSlidingPaneLockMode()
            
            if (childFragmentManager.findFragmentById(R.id.detail_container) == null && binding.slidingPaneLayout.isSlideable) {
                binding.slidingPaneLayout.closePane()
            }
            return
        }
        
        if (binding.slidingPaneLayout.isOpen) {
            binding.slidingPaneLayout.closePane()
        }
    }

    fun clearAllDetails() {
        childFragmentManager.commitNow {
            while(childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStackImmediate()
            }
            val fragment = childFragmentManager.findFragmentById(R.id.detail_container)
            if (fragment != null) remove(fragment)
        }
        updateSlidingPaneLockMode()
    }

    fun openChatOnTablet(account: String) {
        val fragment = ChatFragment().apply {
            arguments = bundleOf("account" to account)
        }
        childFragmentManager.commit {
            setCustomAnimations(
                R.anim.metro_enter,
                R.anim.metro_exit,
                R.anim.metro_pop_enter,
                R.anim.metro_pop_exit
            )
            replace(R.id.detail_container, fragment)
        }
        binding.slidingPaneLayout.openPane()
        updateSlidingPaneLockMode()
    }

    private fun updateConnectionStatus(isConnecting: Boolean) {
        val statusView = binding.toolbarLayout?.root?.findViewById<TextView>(R.id.tvToolbarStatus) ?: return
        statusView.isVisible = isConnecting
    }

    private fun setupTabs() {
        tabLayoutMediator?.detach()
        tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.setCustomView(R.layout.view_tab_item)
            val titles = listOf(getString(R.string.tab_contacts), getString(R.string.tab_chats), getString(R.string.tab_profile))
            tab.customView?.findViewById<TextView>(R.id.tabTitle)?.text = titles.getOrNull(position)
        }
        tabLayoutMediator?.attach()
    }

    private fun applyDynamicDesign() {
        val surfaceColor = com.google.android.material.color.MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, Color.WHITE)
        binding.appBarLayout.setBackgroundColor(Color.parseColor("#DCE6F7"))
        binding.mainToolbar.setBackgroundColor(surfaceColor)
        binding.tabLayout.setBackgroundColor(Color.parseColor("#DCE6F7"))
    }

    private class MainPagerAdapter(fragment: Fragment) : androidx.viewpager2.adapter.FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ContactsFragment()
                1 -> ChatsFragment()
                else -> ProfileFragment()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tabLayoutMediator?.detach()
        _binding = null
    }
}

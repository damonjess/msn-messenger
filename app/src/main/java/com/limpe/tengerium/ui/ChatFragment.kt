package com.limpe.tengerium.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
import coil.load
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.IncomingFileTransfer
import com.limpe.tengerium.data.protocol.FileTransferLimits
import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentChatBinding
import com.limpe.tengerium.databinding.LayoutChatMenuBinding
import com.limpe.tengerium.ui.call.CallActivity
import com.limpe.tengerium.ui.call.CallManager
import com.limpe.tengerium.ui.games.GameActivity
import com.limpe.tengerium.ui.games.GameKind
import com.limpe.tengerium.ui.games.GameManager
import com.limpe.tengerium.util.AnimationHelper
import com.limpe.tengerium.util.ChatHistoryExporter
import com.limpe.tengerium.util.FormattingUtils
import com.limpe.tengerium.util.SoundUtils
import com.limpe.tengerium.util.VibrationHelper
import com.limpe.tengerium.util.WinkSoundPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import com.limpe.tengerium.data.db.MessageEntity
import com.limpe.tengerium.data.security.SafeStorage

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: MSNPRepository
    private lateinit var securePrefs: SecurePrefs
    private lateinit var adapter: ChatAdapter
    private var contactAccount: String? = null
    
    private var typingJob: Job? = null
    private var lastStatus: String? = null
    
    private val mainViewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            performExport(uri)
        }
    }

    private val sendFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendSelectedFile(uri)
    }

    private val sendPhotoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) sendSelectedFile(uri)
    }

    private var pendingCustomEmoticonCode: String? = null

    private val customEmoticonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val code = pendingCustomEmoticonCode
        pendingCustomEmoticonCode = null
        val appContext = context?.applicationContext
        if (uri == null || code == null || appContext == null) return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { CustomEmoticons.add(appContext, code, uri) }
            if (!isAdded) return@launch
            if (error == null) {
                Toast.makeText(appContext, getString(R.string.custom_emoticon_added, code), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(appContext, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val recordAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!isAdded) return@registerForActivityResult
        if (granted) showVoiceClipDialog()
        else Toast.makeText(requireContext(), R.string.voice_clip_permission_needed, Toast.LENGTH_LONG).show()
    }

    private var pendingCallMedia: CallManager.Media? = null

    private val callPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val media = pendingCallMedia
        pendingCallMedia = null
        if (!isAdded) return@registerForActivityResult
        if (media != null) {
            if (grants.values.all { it }) startCallNow(media)
            else Toast.makeText(requireContext(), R.string.call_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private val saveIncomingFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val transfer = pendingIncomingTransfer
        pendingIncomingTransfer = null
        if (transfer != null) {
            if (uri == null) declineIncomingFile(transfer)
            else saveIncomingFile(transfer, uri)
        }
    }

    private var pendingIncomingTransfer: IncomingFileTransfer? = null
    private var activeVoiceClipDialog: VoiceClipDialog? = null
    private val shownIncomingTransferIds = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = (requireActivity().application as TengeriumApp).repository
        securePrefs = SecurePrefs(requireContext())
        contactAccount = arguments?.getString("account")
        contactAccount?.let { account ->
            ConversationTabs.open(account, account.substringBefore('@'))
        }

        setupUI()
        observeData()
        
        mainViewModel.setActiveChat(contactAccount)
    }

    private fun setupUI() {
        adapter = ChatAdapter(viewLifecycleOwner.lifecycleScope, onLongClick = { message ->
            if (!message.isIncoming && message.error != null) {
                repository.resendMessage(message.id)
            }
        })
        binding.rvMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.rvMessages.adapter = adapter
        adapter.onWinkClick = { wink -> playWink(wink) }
        
        binding.swipeBackLayout.setOnSwipeBackListener {
            performBackNavigation()
        }

        applyDynamicDesign()
        loadChatBackground()

        // MSN Title bar window controls
        binding.btnWindowClose.setOnClickListener { performBackNavigation() }
        binding.btnWindowMinimize.setOnClickListener { performBackNavigation(closeTab = false) }
        binding.btnWindowMaximize.setOnClickListener { performBackNavigation(closeTab = false) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ConversationTabs.tabs.collectLatest { tabs -> renderChatTabs(tabs) }
            }
        }

        // MSN Menu bar items
        binding.btnMenuFile.setOnClickListener { startExport() }
        binding.btnMenuEdit.setOnClickListener { showClearChatDialog() }
        binding.btnMenuActions.setOnClickListener { showCustomMenu(it) }
        binding.btnMenuTools.setOnClickListener { showToolsMenu(it) }
        binding.btnMenuHelp.setOnClickListener {
            val parent = findMainFragment()
            if (parent != null) {
                parent.showDetail(AboutFragment(), addToBackStack = true)
            } else {
                runCatching { findNavController().navigate(R.id.AboutFragment) }
            }
        }

        // MSN Action toolbar items
        binding.btnActionInvite.setOnClickListener {
            val bundle = bundleOf("account" to (contactAccount ?: ""))
            val parent = findMainFragment()
            if (parent != null) {
                val inviteFragment = InviteParticipantsFragment().apply { arguments = bundle }
                parent.showDetail(inviteFragment, addToBackStack = true)
            } else {
                runCatching { findNavController().navigate(R.id.action_ChatFragment_to_InviteParticipantsFragment, bundle) }
            }
        }
        binding.btnActionSendFile.setOnClickListener { showAttachMenu(it) }
        binding.btnActionVideo.setOnClickListener { startCall(CallManager.Media.VIDEO) }
        binding.btnActionVoice.setOnClickListener { openVoiceClip() }
        binding.btnActionActivities.setOnClickListener { showActivitiesMenu(it) }
        binding.btnActionGames.setOnClickListener {
            val parent = findMainFragment()
            if (parent != null) {
                parent.showDetail(EasterEggFragment(), addToBackStack = true)
            } else {
                runCatching { findNavController().navigate(R.id.EasterEggFragment) }
            }
        }

        // Formatting Bar items
        binding.btnFormatEmoticon.setOnClickListener { showEmoticonPicker(it) }
        binding.btnFormatGift.setOnClickListener { showUnavailable() }
        binding.btnTabInk.setOnClickListener { openInkDialog() }
        binding.btnTabText.setOnClickListener {
            binding.etMessage.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(binding.etMessage, 0)
        }
        binding.btnFormatVoiceClip.setOnClickListener { openVoiceClip() }
        binding.btnFormatWink.setOnClickListener { showWinkPicker() }
        binding.btnFormatNudge.setOnClickListener { sendNudge() }
        binding.btnFormatFont.setOnClickListener { showFontFormattingMenu() }
        binding.btnFormatBackground.setOnClickListener { openChatSettings() }

        // Input & Avatars
        binding.cardContactAvatar.setOnClickListener { openProfile() }
        binding.cardMyAvatar.setOnClickListener {
            val parent = findMainFragment()
            if (parent != null) {
                parent.showDetail(AvatarLibraryFragment(), addToBackStack = true)
            } else {
                runCatching { findNavController().navigate(R.id.AvatarLibraryFragment) }
            }
        }

        binding.toolbar.setNavigationOnClickListener { 
            performBackNavigation()
        }

        binding.toolbar.inflateMenu(R.menu.menu_chat)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_chat_menu) {
                val anchor = activity?.findViewById<View>(R.id.action_chat_menu) ?: binding.toolbar
                showCustomMenu(anchor)
                true
            } else {
                false
            }
        }

        binding.layoutChatHeader.setOnClickListener { openProfile() }

        binding.btnAttach.setOnClickListener { showAttachMenu(it) }
        binding.btnSend.setOnClickListener { sendMessage() }
        
        updateSendState()

        binding.etMessage.addTextChangedListener { s ->
            updateSendState()

            // 7: don't send typing notifications for the "messaging unavailable" placeholder
            if (!s.isNullOrBlank() && binding.etMessage.isEnabled) {
                repository.sendTyping(contactAccount ?: "")
            }
        }

        if (securePrefs.sendByEnter) {
            binding.etMessage.imeOptions = EditorInfo.IME_ACTION_SEND
            binding.etMessage.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        }

        binding.etMessage.setOnEditorActionListener { _, actionId, event ->
            if (securePrefs.sendByEnter) {
                if (actionId == EditorInfo.IME_ACTION_SEND || 
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    sendMessage()
                    return@setOnEditorActionListener true
                }
            }
            false
        }

        binding.etMessage.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_F2) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0 && activeVoiceClipDialog == null) openVoiceClip(holdToRecord = true)
                        true
                    }
                    KeyEvent.ACTION_UP -> {
                        activeVoiceClipDialog?.endRecording()
                        true
                    }
                    else -> true
                }
            } else if (securePrefs.sendByEnter && keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                sendMessage()
                true
            } else {
                false
            }
        }

        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val isAtBottom = lastVisible >= adapter.itemCount - 3
                
                if (isAtBottom) {
                    hideScrollToBottom()
                } else if (dy < -10) {
                    showScrollToBottom()
                }
            }
        })

        binding.btnScrollToBottom.setOnClickListener {
            binding.rvMessages.smoothScrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun isOnline(): Boolean = repository.loginState.value is MSNPLoginState.Success

    private fun showCustomMenu(anchor: View) {
        val menuBinding = LayoutChatMenuBinding.inflate(layoutInflater)
        val popup = PopupWindow(menuBinding.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        
        popup.elevation = 20f

        val normalizedAccount = contactAccount?.lowercase(Locale.ROOT)?.trim() ?: ""
        val contacts = repository.contacts.value
        val contact = contacts.find { it.account.lowercase(Locale.ROOT).trim() == normalizedAccount }
        val online = isOnline()
        val isContactOnline = contact != null && contact.status != MSNPProto.Status.OFFLINE && contact.status != "FLN"
        
        // Hide elements not needed in active chat context
        menuBinding.btnMenuPin.isVisible = false
        menuBinding.btnMenuFolder.isVisible = false
        menuBinding.btnMenuRemoveFolder.isVisible = false
        
        // Mute logic
        val isMuted = securePrefs.isChatMuted(normalizedAccount)
        menuBinding.btnMenuMute.text = if (isMuted) getString(R.string.unmute_notifications) else getString(R.string.mute_notifications)
        menuBinding.btnMenuMute.setIconResource(if (isMuted) R.drawable.ic_notifications_on else R.drawable.ic_notifications_off)
        menuBinding.btnMenuMute.setOnClickListener {
            securePrefs.toggleMuteChat(normalizedAccount)
            popup.dismiss()
        }

        // Nudge (Будильник)
        menuBinding.btnMenuNudge.isVisible = true
        val cooldown = repository.nudgeCooldowns.value[normalizedAccount] ?: 0
        
        if (!isContactOnline || !online) {
            menuBinding.btnMenuNudge.isEnabled = false
            menuBinding.btnMenuNudge.alpha = 0.5f
        } else if (cooldown > 0) {
            menuBinding.btnMenuNudge.isEnabled = false
            menuBinding.btnMenuNudge.text = getString(R.string.send_nudge_cooldown, cooldown)
        } else {
            menuBinding.btnMenuNudge.isEnabled = true
            menuBinding.btnMenuNudge.alpha = 1.0f
        }
        
        menuBinding.btnMenuNudge.setOnClickListener {
            sendNudge()
            popup.dismiss()
        }

        // Export
        menuBinding.btnMenuExport.isVisible = true
        menuBinding.btnMenuExport.setOnClickListener {
            startExport()
            popup.dismiss()
        }

        // Clear
        menuBinding.btnMenuClear.isVisible = true
        menuBinding.btnMenuClear.setOnClickListener {
            showClearChatDialog()
            popup.dismiss()
        }
        
        // Block
        menuBinding.btnMenuBlock.isVisible = true
        val isBlocked = contact?.listType?.contains("BL") ?: false
        menuBinding.btnMenuBlock.isEnabled = online
        menuBinding.btnMenuBlock.alpha = if (online) 1.0f else 0.5f
        menuBinding.btnMenuBlock.text = if (isBlocked) getString(R.string.unblock) else getString(R.string.block)
        menuBinding.btnMenuBlock.setIconResource(if (isBlocked) R.drawable.ic_unblock else R.drawable.ic_block)
        menuBinding.btnMenuBlock.setOnClickListener {
            if (isBlocked) repository.unblockContact(normalizedAccount) else repository.blockContact(normalizedAccount)
            popup.dismiss()
        }

        menuBinding.root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val xOffset = -menuBinding.root.measuredWidth + anchor.width
        val yOffset = 0
        
        AnimationHelper.showSmartPopup(popup, anchor, xOffset, yOffset)
    }

    private fun showClearChatDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_deep_link_warning, null)
        val builder = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            
        val dialog = builder.create()
        dialog.show()

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvWarningTitle)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvWarningMessage)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val colorOnSurface = com.google.android.material.color.MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurface, 0)
        tvTitle.setTextColor(colorOnSurface)

        tvTitle.text = getString(R.string.clear_history_title)
        tvMessage.text = getString(R.string.clear_history_message)
        btnDelete.text = getString(R.string.delete)
        
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            contactAccount?.let { account ->
                repository.clearChatMessages(account)
                Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
    }

    private fun performBackNavigation(closeTab: Boolean = true) {
        if (closeTab) {
            val key = ConversationTabs.normalize(contactAccount ?: "")
            val tabs = ConversationTabs.tabs.value
            val index = tabs.indexOfFirst { it.account == key }
            if (index >= 0) {
                val remaining = ConversationTabs.close(key)
                if (remaining.isNotEmpty()) {
                    switchToTab(remaining[index.coerceIn(0, remaining.size - 1)].account)
                    return
                }
            }
        }
        val mainFragment = findMainFragment()
        if (mainFragment != null) {
            mainFragment.closeDetail()
        } else {
            findNavController().popBackStack()
        }
    }

    // ---- tabbed conversations -----------------------------------------------------------

    private fun renderChatTabs(tabs: List<ConversationTabs.Tab>) {
        val b = _binding ?: return
        b.scrollChatTabs.isVisible = tabs.size > 1
        if (tabs.size <= 1) {
            b.layoutChatTabs.removeAllViews()
            return
        }
        val density = resources.displayMetrics.density
        val currentKey = ConversationTabs.normalize(contactAccount ?: "")
        b.layoutChatTabs.removeAllViews()
        tabs.forEach { tab ->
            val active = tab.account == currentKey
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((9 * density).toInt(), 0, (2 * density).toInt(), 0)
                setBackgroundResource(if (active) R.drawable.bg_chat_tab_active else R.drawable.bg_chat_tab)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { marginEnd = (3 * density).toInt() }
                setOnClickListener { switchToTab(tab.account) }
            }
            row.addView(TextView(requireContext()).apply {
                text = FormattingUtils.formatBBCode(tab.title).toString()
                textSize = 11f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (140 * density).toInt()
                setTextColor(if (active) 0xFF0A2A66.toInt() else 0xFF31527F.toInt())
                setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
            })
            row.addView(TextView(requireContext()).apply {
                text = "×"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(0xFF31527F.toInt())
                setPadding((7 * density).toInt(), 0, (5 * density).toInt(), (1 * density).toInt())
                contentDescription = getString(R.string.close_conversation_tab, tab.title)
                setOnClickListener { closeTab(tab.account) }
            })
            b.layoutChatTabs.addView(row)
        }
    }

    private fun switchToTab(account: String) {
        val key = ConversationTabs.normalize(account)
        if (key == ConversationTabs.normalize(contactAccount ?: "")) return
        val parent = findMainFragment()
        if (parent != null) {
            parent.openChatOnTablet(key)
        } else {
            runCatching {
                findNavController().navigate(
                    R.id.action_MainFragment_to_ChatFragment,
                    bundleOf("account" to key)
                )
            }
        }
    }

    private fun closeTab(account: String) {
        val key = ConversationTabs.normalize(account)
        val currentKey = ConversationTabs.normalize(contactAccount ?: "")
        val tabs = ConversationTabs.tabs.value
        val index = tabs.indexOfFirst { it.account == key }
        val remaining = ConversationTabs.close(key)
        if (key != currentKey) return
        if (remaining.isEmpty()) {
            performBackNavigation(closeTab = false)
            return
        }
        switchToTab(remaining[index.coerceIn(0, remaining.size - 1)].account)
    }

    private fun findMainFragment(): MainFragment? {
        var parent = parentFragment
        while (parent != null) {
            if (parent is MainFragment) return parent
            parent = parent.parentFragment
        }
        return null
    }

    private fun startExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "chat_history_${contactAccount?.replace("@", "_")}.json")
        }
        exportLauncher.launch(intent)
    }

    private fun performExport(uri: Uri) {
        val account = contactAccount ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val messages = repository.getMessages(account).first()
            val contacts = repository.contacts.value
            val contact = contacts.find { it.account.equals(account, ignoreCase = true) }
            
            val loginState = repository.loginState.value as? MSNPLoginState.Success
            val myNickname = loginState?.nickname
            val myEmail = repository.getCurrentAccount() ?: ""

            val success = ChatHistoryExporter.exportChat(
                requireContext(),
                uri,
                contact?.nickname,
                account,
                myNickname,
                myEmail,
                messages
            )
            
            if (success) {
                Toast.makeText(requireContext(), R.string.export_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.export_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadChatBackground() {
        val bgPath = securePrefs.chatBackgroundPath
        if (!bgPath.isNullOrEmpty()) {
            binding.ivChatBackground.isVisible = true
            binding.ivChatBackground.load(Uri.parse(bgPath))
            binding.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            binding.ivChatBackground.isVisible = false
        }
    }

    // ---- winks: animation playback ------------------------------------------------------

    private val seenMessageIds = HashSet<Long>()
    private var winkSnapshotTaken = false

    /** Plays the animation for winks that arrive (or are sent) while the chat is open. */
    private fun playNewWinks(messages: List<MessageEntity>) {
        val now = System.currentTimeMillis()
        var latest: Wink? = null
        for (m in messages) {
            if (!seenMessageIds.add(m.id)) continue
            if (!winkSnapshotTaken) continue            // history that was already there
            if (now - m.timestamp > 60_000) continue    // stale
            val wink = WinkCatalog.fromMessage(SafeStorage.decryptText(m.encryptedText)) ?: continue
            latest = wink
        }
        winkSnapshotTaken = true
        latest?.let { playWink(it) }
    }

    private fun playWink(wink: Wink) {
        val overlay = _binding?.winkOverlay ?: return
        overlay.play(wink)
        if (wink.id == WinkCatalog.KNOCK) shakeWindowForKnock()
        if (securePrefs.chatSoundsEnabled) {
            // Classic winks each have their own effect; retired winks use the old chime.
            if (!WinkSoundPlayer.play(requireContext(), wink.id)) {
                SoundUtils.playSound(requireContext(), R.raw.wink)
            }
        }
    }

    private var knockShakeAnimator: ValueAnimator? = null

    /** Physically shakes the chat window in step with the four knocks. */
    private fun shakeWindowForKnock() {
        val root = _binding?.swipeBackLayout ?: return
        knockShakeAnimator?.cancel()
        val amplitude = 9f * resources.displayMetrics.density
        val bangs = floatArrayOf(0.05f, 0.29f, 0.53f, 0.77f)
        knockShakeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                var offset = 0f
                for (bang in bangs) {
                    val local = t - bang
                    if (local >= 0f && local < 0.16f) {
                        val falloff = 1f - local / 0.16f
                        offset += kotlin.math.sin(local * 190f) * amplitude * falloff * falloff
                    }
                }
                root.translationX = offset
                root.translationY = offset * 0.3f
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.translationX = 0f
                    root.translationY = 0f
                }
            })
            start()
        }
    }

    // ---- misc menus / pickers -----------------------------------------------------------

    private fun showUnavailable() {
        Toast.makeText(requireContext(), R.string.msn_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun openChatSettings() {
        val parent = findMainFragment()
        if (parent != null) {
            parent.showDetail(ChatSettingsFragment(), addToBackStack = true)
        } else {
            runCatching { findNavController().navigate(R.id.ChatSettingsFragment) }
        }
    }

    private fun showToolsMenu(anchor: View) {
        val account = contactAccount ?: return
        val normalized = account.lowercase(Locale.ROOT).trim()
        val muted = securePrefs.isChatMuted(normalized)
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, if (muted) R.string.unmute_notifications else R.string.mute_notifications)
        popup.menu.add(0, 2, 1, R.string.chat_settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> securePrefs.toggleMuteChat(normalized)
                2 -> openChatSettings()
            }
            true
        }
        popup.show()
    }

    /** Inserts an emoticon code at the cursor, spaced so it is recognised as an emoticon. */
    private fun insertEmoticon(code: String) {
        val et = _binding?.etMessage ?: return
        if (!et.isEnabled) return
        val text = et.text ?: return
        val start = maxOf(0, minOf(et.selectionStart, et.selectionEnd))
        val end = maxOf(et.selectionStart, et.selectionEnd).coerceAtLeast(start)
        val before = if (start > 0) text[start - 1] else null
        val prefix = if (before != null && !before.isWhitespace()) " " else ""
        text.replace(start, end, "$prefix$code ")
    }

    private fun showEmoticonPicker(anchor: View) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (6 * density).toInt()
        val cell = (44 * density).toInt()
        val icon = (28 * density).toInt()
        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 6
            setPadding(pad, pad, pad, pad)
        }
        lateinit var popup: PopupWindow
        Emoticons.all.forEach { e ->
            val iv = ImageView(ctx).apply {
                setImageResource(e.drawable)
                contentDescription = e.code
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding((cell - icon) / 2, (cell - icon) / 2, (cell - icon) / 2, (cell - icon) / 2)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                }
                setOnClickListener {
                    insertEmoticon(e.code)
                    popup.dismiss()
                }
            }
            grid.addView(iv)
        }

        CustomEmoticons.entries(ctx).forEach { entry ->
            val iv = ImageView(ctx).apply {
                val preview = CustomEmoticons.preview(ctx, entry, icon)
                if (preview != null) setImageDrawable(preview) else setImageResource(R.drawable.emo_smile)
                contentDescription = entry.code
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding((cell - icon) / 2, (cell - icon) / 2, (cell - icon) / 2, (cell - icon) / 2)
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                }
                setOnClickListener {
                    insertEmoticon(entry.code)
                    popup.dismiss()
                }
                setOnLongClickListener {
                    popup.dismiss()
                    confirmRemoveCustomEmoticon(entry)
                    true
                }
            }
            grid.addView(iv)
        }

        grid.addView(TextView(ctx).apply {
            text = "+"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(0xFF2C598A.toInt())
            setTypeface(typeface, Typeface.BOLD)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_wink_item)
            contentDescription = getString(R.string.add_custom_emoticon)
            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = cell
                height = cell
            }
            setOnClickListener {
                popup.dismiss()
                showAddCustomEmoticonDialog()
            }
        })

        popup = PopupWindow(grid, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(ctx, R.drawable.bg_msn_history_frame))
            elevation = 8f * density
            isOutsideTouchable = true
        }
        grid.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        popup.showAsDropDown(anchor, 0, -(anchor.height + grid.measuredHeight))
    }

    // ---- custom emoticons -------------------------------------------------------------

    private fun showAddCustomEmoticonDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.custom_emoticon_hint)
            setSingleLine()
            setText(":")
            setSelection(1)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.add_custom_emoticon)
            .setMessage(R.string.custom_emoticon_message)
            .setView(input)
            .setPositiveButton(R.string.next, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().trim()
                input.error = when {
                    code.length < 2 || code.length > 24 || code.any { it.isWhitespace() } || code.any { it in "[]{}<>" } ->
                        getString(R.string.custom_emoticon_invalid)
                    !CustomEmoticons.isCodeAvailable(ctx, code) ->
                        getString(R.string.custom_emoticon_duplicate)
                    else -> null
                }
                if (input.error != null) return@setOnClickListener
                pendingCustomEmoticonCode = code
                dialog.dismiss()
                customEmoticonLauncher.launch(arrayOf("image/*"))
            }
        }
        dialog.show()
    }

    private fun confirmRemoveCustomEmoticon(entry: CustomEmoticons.Entry) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remove_custom_emoticon, entry.code))
            .setPositiveButton(R.string.remove) { _, _ ->
                CustomEmoticons.remove(requireContext(), entry.code)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- voice clips / handwriting / photos ---------------------------------------------

    /** MSN-style Activities menu: a shared game both contacts play at the same time. */
    private fun showActivitiesMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.game_tic_tac_toe)
        popup.menu.add(0, 2, 1, R.string.game_rps)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startSharedActivity(GameKind.TIC_TAC_TOE)
                2 -> startSharedActivity(GameKind.ROCK_PAPER_SCISSORS)
            }
            true
        }
        popup.show()
    }

    private fun startSharedActivity(kind: GameKind) {
        val target = contactAccount ?: return
        if (!target.contains('@')) {
            Toast.makeText(requireContext(), R.string.game_one_to_one, Toast.LENGTH_LONG).show()
            return
        }
        if (!GameManager.startGame(target, kind)) {
            Toast.makeText(requireContext(), R.string.game_busy, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(requireContext(), GameActivity::class.java))
    }

    private fun showAttachMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, R.string.send_photo)
        popup.menu.add(0, 2, 1, R.string.send_file)
        popup.menu.add(0, 3, 2, R.string.voice_clip)
        popup.menu.add(0, 4, 3, R.string.handwriting)
        popup.menu.add(0, 5, 4, R.string.call_voice)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> choosePhotoToSend()
                2 -> chooseFileToSend()
                3 -> openVoiceClip()
                4 -> openInkDialog()
                5 -> startCall(CallManager.Media.AUDIO)
            }
            true
        }
        popup.show()
    }

    private fun choosePhotoToSend() {
        val target = contactAccount ?: return
        if (!target.contains('@')) {
            Toast.makeText(requireContext(), R.string.file_transfer_one_to_one_only, Toast.LENGTH_LONG).show()
            return
        }
        sendPhotoLauncher.launch(arrayOf("image/*"))
    }

    private fun openVoiceClip(holdToRecord: Boolean = false) {
        val target = contactAccount ?: return
        if (!target.contains('@')) {
            Toast.makeText(requireContext(), R.string.file_transfer_one_to_one_only, Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            showVoiceClipDialog(holdToRecord)
        } else {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showVoiceClipDialog(holdToRecord: Boolean = false) {
        if (!isAdded) return
        activeVoiceClipDialog = VoiceClipDialog(requireContext()) { clip ->
            activeVoiceClipDialog = null
            sendGeneratedFile(clip, "Voice-Clip-${fileStamp()}.m4a")
        }.also { dialog ->
            dialog.setOnDismissListener { if (activeVoiceClipDialog === dialog) activeVoiceClipDialog = null }
            dialog.show()
            if (holdToRecord) dialog.beginRecording()
        }
    }

    private fun openInkDialog() {
        val target = contactAccount ?: return
        if (!target.contains('@')) {
            Toast.makeText(requireContext(), R.string.file_transfer_one_to_one_only, Toast.LENGTH_LONG).show()
            return
        }
        InkDialog(requireContext()) { drawing ->
            sendInk(drawing)
        }.show()
    }

    /** Publishes a drawing as an inline message that renders on the recipient's device. */
    private fun sendInk(drawing: InkDrawing) {
        val target = contactAccount
        val appContext = context?.applicationContext
        if (target == null || appContext == null || !target.contains('@')) return
        val id = InkStore.newId()

        // Keep a local copy first so the drawing shows in this conversation immediately.
        val png = InkCodec.pngBytes(drawing)
        if (png == null || !InkStore.storeBytes(appContext, id, png)) {
            Toast.makeText(appContext, R.string.handwriting_send_failed, Toast.LENGTH_LONG).show()
            return
        }

        // The drawing rides inside the message itself: MSNP file transfers are peer-to-peer
        // and fail across mobile networks, which left the recipient with "(image not downloaded)".
        val payload = InkCodec.encode(drawing, InkStore.MAX_INLINE_BYTES)
        repository.sendMessage(target, InkStore.marker(id, payload))

        if (payload == null) {
            // Unusually busy drawing: fall back to the peer-to-peer image transfer.
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val file = InkStore.file(appContext, id) ?: return@withContext
                        repository.sendFile(target, InkStore.fileName(id), file)
                    }
                } catch (e: Exception) {
                    Log.e("ChatFragment", "Failed to send handwriting image $id", e)
                }
            }
        }
    }

    /** Starts a live call with the open contact, asking for the permissions it needs first. */
    private fun startCall(media: CallManager.Media) {
        val target = contactAccount ?: return
        if (!target.contains('@')) {
            Toast.makeText(requireContext(), R.string.file_transfer_one_to_one_only, Toast.LENGTH_LONG).show()
            return
        }
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (media == CallManager.Media.VIDEO) required.add(Manifest.permission.CAMERA)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startCallNow(media)
        } else {
            pendingCallMedia = media
            callPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startCallNow(media: CallManager.Media) {
        val target = contactAccount ?: return
        if (!CallManager.startCall(target, media)) {
            Toast.makeText(requireContext(), R.string.call_busy, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(requireContext(), CallActivity::class.java))
    }

    private fun fileStamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun sendGeneratedFile(file: File, displayName: String) {
        val target = contactAccount
        val appContext = context?.applicationContext
        if (target == null || appContext == null) {
            file.delete()
            return
        }
        if (file.length() == 0L) {
            file.delete()
            return
        }
        if (file.length() > FileTransferLimits.MAX_FILE_SIZE_BYTES) {
            file.delete()
            Toast.makeText(appContext, R.string.file_transfer_too_large, Toast.LENGTH_LONG).show()
            return
        }
        val progress = AlertDialog.Builder(requireContext())
            .setMessage(R.string.sending_file)
            .setCancelable(false)
            .create()
        progress.show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.sendFile(target, displayName, file) }
                if (isAdded) Toast.makeText(appContext, R.string.file_sent, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        appContext,
                        getString(R.string.file_send_failed, error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                file.delete()
                progress.dismiss()
            }
        }
    }

    private fun showWinkPicker() {
        val account = contactAccount ?: return
        WinkPickerDialog(requireContext()) { wink ->
            repository.sendMessage(account, WinkCatalog.marker(wink))
        }.show()
    }

    private fun showFontFormattingMenu() {
        val popup = PopupMenu(requireContext(), binding.btnFormatFont)
        popup.menu.setGroupCheckable(0, true, false)
        popup.menu.add(0, 1, 0, R.string.format_bold).apply {
            isCheckable = true
            isChecked = isStyleActive("b")
        }
        popup.menu.add(0, 2, 1, R.string.format_italic).apply {
            isCheckable = true
            isChecked = isStyleActive("i")
        }
        popup.menu.add(0, 3, 2, R.string.format_underline).apply {
            isCheckable = true
            isChecked = isStyleActive("u")
        }
        popup.menu.add(0, 4, 3, R.string.format_color).isCheckable = false
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleStyle("b")
                2 -> toggleStyle("i")
                3 -> toggleStyle("u")
                4 -> showColorPicker()
            }
            true
        }
        popup.show()
    }

    private val textColors = intArrayOf(
        0x000000, 0xC62828, 0xE65100, 0xF9A825, 0x2E7D32, 0x00838F,
        0x1565C0, 0x4527A0, 0x6A1B9A, 0xAD1457, 0x5D4037, 0x616161
    )

    /** Colour used for every message you send (also sent to the other person's client). */
    private fun showColorPicker() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val cell = (44 * density).toInt()
        val margin = (6 * density).toInt()
        val grid = android.widget.GridLayout(ctx).apply {
            columnCount = 4
            setPadding(margin * 2, margin * 2, margin * 2, margin * 2)
        }
        lateinit var dialog: AlertDialog
        val current = securePrefs.messageColor and 0xFFFFFF
        textColors.forEach { rgb ->
            val swatch = View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFF000000.toInt() or rgb)
                    setStroke(
                        (if (rgb == current) 4 else 1) * density.toInt().coerceAtLeast(1),
                        if (rgb == current) 0xFF316AC5.toInt() else 0x55000000
                    )
                }
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                    setMargins(margin, margin, margin, margin)
                }
                setOnClickListener {
                    securePrefs.messageColor = rgb
                    dialog.dismiss()
                }
            }
            grid.addView(swatch)
        }
        dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.format_color)
            .setView(grid)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun isStyleActive(tag: String): Boolean {
        val et = _binding?.etMessage ?: return false
        val text = et.text ?: return false
        val start = minOf(et.selectionStart, et.selectionEnd)
        val end = maxOf(et.selectionStart, et.selectionEnd)
        if (start == end) return false
        val open = "[$tag]"
        val close = "[/$tag]"
        return start >= open.length && end + close.length <= text.length &&
            text.substring(start - open.length, start).equals(open, ignoreCase = true) &&
            text.substring(end, end + close.length).equals(close, ignoreCase = true)
    }

    private fun toggleStyle(tag: String) {
        val et = _binding?.etMessage ?: return
        val text = et.text ?: return
        val selectionStart = minOf(et.selectionStart, et.selectionEnd)
        val selectionEnd = maxOf(et.selectionStart, et.selectionEnd)
        if (selectionStart < 0 || selectionEnd > text.length) return

        val open = "[$tag]"
        val close = "[/$tag]"

        if (selectionStart == selectionEnd) {
            text.insert(selectionStart, open + close)
            et.setSelection(selectionStart + open.length)
            return
        }

        val wrapped = selectionStart >= open.length && selectionEnd + close.length <= text.length &&
            text.substring(selectionStart - open.length, selectionStart).equals(open, ignoreCase = true) &&
            text.substring(selectionEnd, selectionEnd + close.length).equals(close, ignoreCase = true)

        if (wrapped) {
            val innerStart = selectionStart - open.length
            val innerEnd = selectionEnd - open.length
            text.delete(selectionEnd, selectionEnd + close.length)
            text.delete(selectionStart - open.length, selectionStart)
            et.setSelection(innerStart, innerEnd)
        } else {
            text.insert(selectionEnd, close)
            text.insert(selectionStart, open)
            et.setSelection(selectionStart + open.length, selectionEnd + open.length)
        }
    }

    private fun sendNudge() {
        contactAccount?.let { account ->
            if (!repository.sendNudge(account)) {
                val seconds = repository.nudgeCooldowns.value[account.lowercase(Locale.ROOT).trim()] ?: 0
                Toast.makeText(requireContext(), getString(R.string.send_nudge_cooldown, seconds), Toast.LENGTH_SHORT).show()
                return@let
            }
            val root = _binding?.root ?: return@let
            val context = requireContext()
            AnimationHelper.playNudgeKnock(root) { index ->
                if (index == 0) {
                    if (securePrefs.chatSoundsEnabled) SoundUtils.playSound(context, R.raw.nudge)
                    if (securePrefs.vibrationEnabled) VibrationHelper.vibrateNudge(context)
                }
            }
        }
    }

    private fun openProfile() {
        contactAccount?.let { account ->
            val bundle = bundleOf("account" to account)
            val parent = findMainFragment()
            
            if (parent != null) {
                val profileFragment = ProfileFragment().apply {
                    arguments = bundle
                }
                parent.showDetail(profileFragment, addToBackStack = true)
            } else {
                val navController = findNavController()
                if (navController.currentDestination?.id == R.id.ChatFragment) {
                    navController.navigate(R.id.action_ChatFragment_to_ProfileFragment, bundle)
                }
            }
        }
    }

    private fun applyDynamicDesign() {
        val surfaceColor = com.google.android.material.color.MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, 0)
        
        if (securePrefs.chatBackgroundPath.isNullOrEmpty()) {
            binding.root.setBackgroundColor(surfaceColor)
        }

        binding.appBarLayout.setBackgroundColor(surfaceColor)
        // The message field is always a light MSN-style box, even in dark mode.
        binding.layoutInput.setBackgroundColor(android.graphics.Color.WHITE)
        binding.toolbar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        
        binding.appBarLayout.elevation = 0f
        binding.layoutInput.elevation = 4f
    }

    private fun showScrollToBottom() {
        if (binding.btnScrollToBottom.visibility == View.VISIBLE) return
        binding.btnScrollToBottom.visibility = View.VISIBLE
        binding.btnScrollToBottom.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun hideScrollToBottom() {
        if (binding.btnScrollToBottom.visibility == View.GONE) return
        binding.btnScrollToBottom.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(200)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    _binding?.btnScrollToBottom?.visibility = View.GONE
                }
            })
            .start()
    }

    private fun chooseFileToSend() {
        val target = contactAccount ?: return
        if (!target.contains('@')) {
            Toast.makeText(requireContext(), R.string.file_transfer_one_to_one_only, Toast.LENGTH_LONG).show()
            return
        }
        sendFileLauncher.launch(arrayOf("*/*"))
    }

    private fun sendSelectedFile(uri: Uri) {
        val target = contactAccount ?: return
        val appContext = context?.applicationContext ?: return
        val fileName = queryDisplayName(uri)
        val knownSize = queryFileSize(uri)
        if (knownSize != null && knownSize > FileTransferLimits.MAX_FILE_SIZE_BYTES) {
            Toast.makeText(appContext, R.string.file_transfer_too_large, Toast.LENGTH_LONG).show()
            return
        }

        val progress = AlertDialog.Builder(requireContext())
            .setMessage(R.string.sending_file)
            .setCancelable(false)
            .create()
        progress.show()
        viewLifecycleOwner.lifecycleScope.launch {
            var cachedFile: File? = null
            try {
                val stagedFile = withContext(Dispatchers.IO) {
                    val destination = File.createTempFile("msn-send-", ".tmp", appContext.cacheDir)
                    try {
                        val input = appContext.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("Unable to open the selected file")
                        input.use { source ->
                            destination.outputStream().use { sink ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var total = 0L
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > FileTransferLimits.MAX_FILE_SIZE_BYTES) throw FileTooLargeException()
                                    sink.write(buffer, 0, count)
                                }
                            }
                        }
                        if (destination.length() == 0L) throw IllegalArgumentException("Empty files are not supported")
                        destination
                    } catch (error: Throwable) {
                        destination.delete()
                        throw error
                    }
                }
                cachedFile = stagedFile
                withContext(Dispatchers.IO) { repository.sendFile(target, fileName, stagedFile) }
                if (isAdded) Toast.makeText(appContext, R.string.file_sent, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                if (isAdded) {
                    val message = if (error is FileTooLargeException) {
                        getString(R.string.file_transfer_too_large)
                    } else {
                        getString(R.string.file_send_failed, error.message ?: error.javaClass.simpleName)
                    }
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                cachedFile?.delete()
                progress.dismiss()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        val displayName = requireContext().contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
        return (displayName ?: "file")
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .take(120)
            .ifBlank { "file" }
    }

    private fun queryFileSize(uri: Uri): Long? = requireContext().contentResolver.query(
        uri,
        arrayOf(OpenableColumns.SIZE),
        null,
        null,
        null
    )?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
    }

    private fun showIncomingFileRequest(transfer: IncomingFileTransfer) {
        if (!isAdded) return
        val tooLarge = transfer.fileSize > FileTransferLimits.MAX_FILE_SIZE_BYTES.toULong()
        val safeName = transfer.fileName.substringAfterLast('/').substringAfterLast('\\').take(120).ifBlank { "file" }
        val message = if (tooLarge) {
            getString(R.string.file_transfer_too_large)
        } else {
            val size = Formatter.formatFileSize(requireContext(), transfer.fileSize.toLong())
            getString(R.string.incoming_file_message, transfer.sender, safeName, size)
        }
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.incoming_file_title)
            .setMessage(message)
            .setCancelable(false)
        if (tooLarge) {
            builder.setPositiveButton(android.R.string.ok) { _, _ -> declineIncomingFile(transfer) }
        } else {
            builder
                .setPositiveButton(R.string.accept_file) { _, _ ->
                    pendingIncomingTransfer = transfer
                    saveIncomingFileLauncher.launch(safeName)
                }
                .setNegativeButton(R.string.decline_file) { _, _ -> declineIncomingFile(transfer) }
        }
        builder.show()
    }

    /** Drawings are accepted silently and shown inline, never offered as a save-to-device file. */
    private fun receiveInkTransfer(transfer: IncomingFileTransfer, inkId: String) {
        val appContext = context?.applicationContext ?: return
        if (transfer.fileSize > 4UL * 1024UL * 1024UL) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching { repository.declineIncomingFileTransfer(transfer) }
            }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { repository.acceptIncomingFileTransfer(transfer) }
                withContext(Dispatchers.IO) { InkStore.storeBytes(appContext, inkId, bytes) }
            } catch (_: Exception) {
                // A failed drawing transfer should not interrupt the conversation.
            }
        }
    }

    private fun declineIncomingFile(transfer: IncomingFileTransfer) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching { repository.declineIncomingFileTransfer(transfer) }
        }
    }

    private fun saveIncomingFile(transfer: IncomingFileTransfer, uri: Uri) {
        val appContext = context?.applicationContext ?: return
        val progress = AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.file_transfer_save_prompt, transfer.fileName))
            .setCancelable(false)
            .create()
        progress.show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = appContext.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Unable to open the save location")
                    output.use { destination ->
                        val bytes = repository.acceptIncomingFileTransfer(transfer)
                        if (bytes.size.toLong() > FileTransferLimits.MAX_FILE_SIZE_BYTES) throw FileTooLargeException()
                        destination.write(bytes)
                    }
                }
                if (isAdded) Toast.makeText(appContext, R.string.file_received, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                if (repository.incomingFileTransfers.value.any { it.id == transfer.id }) {
                    declineIncomingFile(transfer)
                }
                if (isAdded) {
                    val message = if (error is FileTooLargeException) {
                        getString(R.string.file_transfer_too_large)
                    } else {
                        getString(R.string.file_receive_failed, error.message ?: error.javaClass.simpleName)
                    }
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                }
            } finally {
                progress.dismiss()
            }
        }
    }

    private class FileTooLargeException : Exception()

    // Send is only active when there is text to send and the input is enabled.
    private fun updateSendState() {
        val b = _binding ?: return
        val canSend = b.etMessage.isEnabled && !b.etMessage.text.isNullOrBlank()
        b.btnSend.isEnabled = canSend
        b.btnSend.alpha = if (canSend) 1f else 0.5f
    }

    private fun sendMessage() {
        if (!binding.etMessage.isEnabled) return
        val text = binding.etMessage.text.toString().trim()
        val account = contactAccount
        if (text.isNotEmpty() && account != null) {
            repository.sendMessage(
                account,
                com.limpe.tengerium.util.MsnTextFormat.withColor(text, securePrefs.messageColor)
            )
            binding.etMessage.text.clear()
            updateSendState()
        }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                contactAccount?.let { account ->

                    launch {
                        repository.loginState.collectLatest { loadMyAvatar() }
                    }

                    launch {
                        repository.getMessages(account).collectLatest { messages ->
                            binding.layoutEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                            adapter.submitMessages(messages) {
                                if (messages.isNotEmpty()) {
                                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                                }
                            }
                            playNewWinks(messages)
                        }
                    }

                    launch {
                        repository.incomingFileTransfers.collectLatest { transfers ->
                            val normalizedAccount = account.lowercase(Locale.ROOT).trim()
                            val transfer = transfers.firstOrNull {
                                it.target.lowercase(Locale.ROOT).trim() == normalizedAccount &&
                                    it.id !in shownIncomingTransferIds
                            }
                            if (transfer != null && isAdded) {
                                val inkId = InkStore.idFromFileName(transfer.fileName)
                                if (inkId != null) {
                                    shownIncomingTransferIds.add(transfer.id)
                                    receiveInkTransfer(transfer, inkId)
                                } else {
                                    shownIncomingTransferIds.add(transfer.id)
                                    showIncomingFileRequest(transfer)
                                }
                            }
                        }
                    }

                    launch {
                        InkStore.revision.collectLatest {
                            _binding?.rvMessages?.adapter?.notifyDataSetChanged()
                        }
                    }

                    launch {
                        repository.contacts.collectLatest { contacts ->
                            val contact = contacts.find { it.account.equals(account, ignoreCase = true) }
                            val contactDisplayName = contact?.nickname ?: account
                            val formattedName = FormattingUtils.formatBBCode(contactDisplayName)
                            binding.tvChatName.text = formattedName
                            binding.tvWindowTitle.text = "$formattedName - Conversation"
                            ConversationTabs.updateTitle(account, contact?.nickname ?: account)
                            binding.avatarStatusView.setAvatar(contact?.avatarUrl, account)
                            
                            val status = contact?.status ?: MSNPProto.Status.OFFLINE
                            updateStatusUI(status, contact?.personalMessage)
                            
                            val isFriend = contact != null
                            binding.layoutNotFriend.visibility = if (isFriend) View.GONE else View.VISIBLE

                            if (securePrefs.disableMessageQueue) {
                                val isOffline = status == MSNPProto.Status.OFFLINE || status == "FLN"
                                if (isOffline) {
                                    binding.etMessage.isEnabled = false
                                    binding.etMessage.setText(R.string.messaging_unavailable)
                                } else {
                                    if (!binding.etMessage.isEnabled) {
                                        binding.etMessage.isEnabled = true
                                        binding.etMessage.text.clear()
                                    }
                                }
                                updateSendState()
                            }
                        }
                    }
                    
                    launch {
                        repository.contacts
                            .map { list -> list.find { it.account.equals(account, ignoreCase = true) }?.isTyping ?: false }
                            .distinctUntilChanged()
                            .collectLatest { isTyping ->
                                updateTypingIndicator(isTyping)
                            }
                    }

                    launch {
                        securePrefs.prefsChangedFlow.collectLatest { key ->
                            if (key == "muted_chats") {
                                binding.ivChatMuted.visibility = if (securePrefs.isChatMuted(account)) View.VISIBLE else View.GONE
                            } else if (key == "avatar_path_${myAccountKey()}") {
                                loadMyAvatar()
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun myAccountKey(): String? =
        (repository.getCurrentAccount() ?: securePrefs.getSavedAccount())?.lowercase(Locale.ROOT)?.trim()

    private fun loadMyAvatar() {
        val account = myAccountKey()
        val state = repository.loginState.value as? MSNPLoginState.Success
        updateMyAvatar(state?.avatarUrl?.takeIf { it.isNotBlank() } ?: account?.let { securePrefs.getAvatarPath(it) })
    }

    private fun updateMyAvatar(path: String?) {
        val avatar = _binding?.ivMyAvatar ?: return
        if (!path.isNullOrEmpty()) {
            avatar.load(File(path)) {
                error(R.drawable.duck)
                placeholder(R.drawable.duck)
            }
        } else {
            avatar.setImageResource(R.drawable.duck)
        }
    }

    private fun updateTypingIndicator(isTyping: Boolean) {
        val b = _binding ?: return
        typingJob?.cancel()
        if (isTyping) {
            b.tvTypingIndicator.visibility = View.VISIBLE
            b.tvTypingIndicator.animate().alpha(1f).setDuration(200).start()
            
            val baseText = getString(R.string.is_typing)
            typingJob = AnimationHelper.startTypingAnimation(viewLifecycleOwner.lifecycleScope, b.tvTypingIndicator, baseText)
        } else {
            b.tvTypingIndicator.animate().alpha(0f).setDuration(200).withEndAction {
                _binding?.tvTypingIndicator?.visibility = View.GONE
            }.start()
        }
    }

    private fun updateStatusUI(status: String, psm: String?) {
        val colorRes = StatusUtils.getStatusColorRes(status)
        val color = androidx.core.content.ContextCompat.getColor(requireContext(), colorRes)
        
        if (lastStatus != null && lastStatus != status) {
            AnimationHelper.animateStatusChange(binding.avatarStatusView.statusView, color)
        } else {
            binding.avatarStatusView.setStatusColor(color)
        }
        lastStatus = status
        
        binding.tvChatStatus.setTextColor(color)
        
        if (!psm.isNullOrEmpty()) {
            binding.tvChatStatus.text = psm
        } else {
            binding.tvChatStatus.setText(StatusUtils.getStatusStringRes(status))
        }

        // Initialize mute state
        contactAccount?.let { account ->
            binding.ivChatMuted.visibility = if (securePrefs.isChatMuted(account)) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        activeVoiceClipDialog?.dismiss()
        activeVoiceClipDialog = null
        super.onDestroyView()
        knockShakeAnimator?.cancel()
        knockShakeAnimator = null
        WinkSoundPlayer.stop()
        mainViewModel.setActiveChat(null)
        typingJob?.cancel()
        _binding?.tvTypingIndicator?.animate()?.cancel()
        _binding = null
    }
}



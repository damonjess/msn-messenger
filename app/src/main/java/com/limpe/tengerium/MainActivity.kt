package com.limpe.tengerium

import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.findNavController
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.limpe.tengerium.data.AppConfig
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.protocol.MSNPService
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.ActivityMainBinding
import com.limpe.tengerium.data.protocol.MSNPProto
import com.limpe.tengerium.databinding.ViewInAppNotificationBinding
import com.limpe.tengerium.ui.AvatarUtils
import com.limpe.tengerium.ui.MainFragment
import com.limpe.tengerium.util.AnimationHelper
import com.limpe.tengerium.util.ChangelogManager
import com.limpe.tengerium.util.DeepLinkHandler
import com.limpe.tengerium.util.FormattingUtils
import com.limpe.tengerium.util.SoundUtils
import com.limpe.tengerium.util.UiConstants
import com.limpe.tengerium.util.VibrationHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var notificationJob: Job? = null
    private var reconnectAnim: AnimationDrawable? = null
    private lateinit var securePrefs: SecurePrefs
    private lateinit var deepLinkHandler: DeepLinkHandler

    private val onGlobalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val r = Rect()
        binding.root.getWindowVisibleDisplayFrame(r)
        val screenHeight = binding.root.rootView.height
        val keypadHeight = screenHeight - r.bottom
        
        // Если клавиатура занимает более 15% экрана, считаем её открытой
        val isKeyboardVisible = keypadHeight > screenHeight * 0.15
        
        // Автоматически скрываем любой футер при открытой клавиатуре на экранах входа
        val navController = try { findNavController(R.id.nav_host_fragment_content_main) } catch (_: Exception) { null }
        val currentDest = navController?.currentDestination?.id
        
        if (currentDest == R.id.AuthFragment || currentDest == R.id.OobeFragment) {
            val footer = binding.root.findViewById<View>(R.id.layoutFooter)
            footer?.visibility = if (isKeyboardVisible) View.GONE else View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkTimeBomb()) return

        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val rootPaddingLeft = binding.root.paddingLeft
        val rootPaddingTop = binding.root.paddingTop
        val rootPaddingRight = binding.root.paddingRight
        val rootPaddingBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                rootPaddingLeft + systemBars.left,
                rootPaddingTop + systemBars.top,
                rootPaddingRight + systemBars.right,
                rootPaddingBottom + maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }

        securePrefs = SecurePrefs(this)
        deepLinkHandler = DeepLinkHandler(this, securePrefs)
        
        // Запускаем службу при открытии UI
        MSNPService.start(this)
        
        // Убираем уведомления о входе в сеть при открытии приложения
        MSNPService.cancelAllEventNotifications(this)

        val repository = (application as TengeriumApp).repository
        
        reconnectAnim = binding.ivReconnectingAnim.drawable as? AnimationDrawable

        binding.btnReconnectNow.setOnClickListener {
            val acc = securePrefs.getSavedAccount()
            val pwd = securePrefs.getSavedPassword()
            if (acc != null && pwd != null) {
                repository.login(acc, pwd, true, securePrefs.rememberPassword, securePrefs.autoSignIn)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.loginState.collectLatest { state ->
                    handleLoginStateDude(state)
                }
            }
        }

        // Слушаем переходы, чтобы скрывать/показывать бар в зависимости от экрана
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(100)
                try {
                    val navController = findNavController(R.id.nav_host_fragment_content_main)
                    navController.addOnDestinationChangedListener { _, _, _ ->
                        handleLoginStateDude(repository.loginState.value)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Could not attach destination listener", e)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.incomingMessageFlow.collectLatest { incoming ->
                    val sender = incoming.senderAccount.lowercase(Locale.ROOT).trim()
                    
                    // Если пользователь заглушен (Muted), не показываем уведомление и не проигрываем звуки
                    if (securePrefs.isChatMuted(sender)) {
                        return@collectLatest
                    }

                    val isNudge = incoming.message == "[NUDGE]"
                    val activeChat = repository.activeChatAccount?.lowercase(Locale.ROOT)?.trim()

                    if (isNudge) {
                        if (securePrefs.notifyNudge) {
                            AnimationHelper.animateNudge(binding.navHostFragmentContentMain)
                            if (securePrefs.vibrationEnabled) {
                                VibrationHelper.vibrateNudge(this@MainActivity)
                            }
                            if (securePrefs.chatSoundsEnabled) {
                                SoundUtils.playSound(this@MainActivity, R.raw.nudge)
                            }
                        } else if (securePrefs.notifyMessages) {
                            // Если нуджи выключены, но сообщения включены - показываем как сообщение
                            showInAppNotification(repository, incoming)
                        }
                    } else if (securePrefs.notifyMessages) {
                        if (activeChat != sender) {
                            showInAppNotification(repository, incoming)
                        } else if (securePrefs.chatSoundsEnabled) {
                            // Если мы в чате с этим отправителем, просто проигрываем звук
                            SoundUtils.playSound(this@MainActivity, R.raw.type)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.contactSignIns.collectLatest { showSignInToast(repository, it) }
            }
        }

        binding.root.viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayoutListener)

        deepLinkHandler.handleIntent(intent)
        
        // Автоматический вход при запуске, если сохранены учетные данные
        // A saved password is the user's clear signal to remember this account between
        // launches; connect in the background instead of requiring a tap on the profile.
        if (securePrefs.rememberMe && securePrefs.getSavedAccount() != null &&
            securePrefs.getSavedPassword() != null && repository.loginState.value is MSNPLoginState.Idle) {
            repository.autoLogin()
        }

        // Проверка и показ Changelog
        lifecycleScope.launch {
            ChangelogManager.checkAndShowChangelog(this@MainActivity, securePrefs)
        }
    }

    private fun handleLoginStateDude(state: MSNPLoginState) {
        val navController = try { findNavController(R.id.nav_host_fragment_content_main) } catch (_: Exception) { null }
        val currentDest = navController?.currentDestination?.id
        val isAuthOrOobe = currentDest == R.id.AuthFragment || currentDest == R.id.OobeFragment
        
        // Если мы на экране входа или OOBE и включен новый экспириенс - скрываем бар ВСЕГДА
        if (isAuthOrOobe && AppConfig.USENEWLOGINEXP) {
            binding.dudeReconnectingLayout.visibility = View.GONE
            reconnectAnim?.stop()
            return
        }

        when (state) {
            is MSNPLoginState.NoInternet -> {
                binding.dudeReconnectingLayout.visibility = View.VISIBLE
                binding.btnReconnectNow.visibility = View.GONE
                binding.tvReconnectingStatus.text = getString(R.string.no_internet)
                binding.ivReconnectingAnim.setImageResource(R.drawable.ic_no_internet)
                reconnectAnim?.stop()
            }
            is MSNPLoginState.Reconnecting -> {
                binding.dudeReconnectingLayout.visibility = View.VISIBLE
                binding.btnReconnectNow.visibility = View.VISIBLE
                binding.tvReconnectingStatus.text = getString(R.string.reconnecting_in, state.secondsRemaining)
                binding.ivReconnectingAnim.setImageResource(R.drawable.anim_reconnect)
                reconnectAnim = binding.ivReconnectingAnim.drawable as? AnimationDrawable
                reconnectAnim?.start()
            }
            is MSNPLoginState.Loading -> {
                binding.dudeReconnectingLayout.visibility = View.VISIBLE
                binding.btnReconnectNow.visibility = View.GONE
                binding.tvReconnectingStatus.text = getString(R.string.connecting_to_msn)
                binding.ivReconnectingAnim.setImageResource(R.drawable.anim_reconnect)
                reconnectAnim = binding.ivReconnectingAnim.drawable as? AnimationDrawable
                reconnectAnim?.start()
            }
            is MSNPLoginState.Success -> {
                binding.dudeReconnectingLayout.visibility = View.GONE
                reconnectAnim?.stop()
            }
            is MSNPLoginState.LoggedInElsewhere -> {
                binding.dudeReconnectingLayout.visibility = View.GONE
                reconnectAnim?.stop()
                handleLoggedInElsewhereDude()
            }
            is MSNPLoginState.Error -> {
                val isAuthErr = state.message.contains("AUTH_INVALID_PASSWORD", ignoreCase = true) ||
                        state.message.contains("AUTH_INVALID_ACCOUNT", ignoreCase = true) ||
                        state.message.contains("ERROR_INVALID_PASSWORD", ignoreCase = true) ||
                        state.message.contains("InvalidPassword", ignoreCase = true)
                if (isAuthErr) {
                    binding.dudeReconnectingLayout.visibility = View.GONE
                    reconnectAnim?.stop()
                    if (state.message.contains("INVALID_PASSWORD", ignoreCase = true)) {
                        handleSessionExpired()
                    }
                } else {
                    // Для всех остальных ошибок (CONNECTION_LOST, TIMEOUT и т.д.) показываем плашку переподключения
                    binding.dudeReconnectingLayout.visibility = View.VISIBLE
                    binding.btnReconnectNow.visibility = View.VISIBLE
                    binding.tvReconnectingStatus.text = getString(R.string.connection_lost)
                    binding.ivReconnectingAnim.setImageResource(R.drawable.anim_reconnect)
                    reconnectAnim?.stop()
                }
            }
            else -> {
                binding.dudeReconnectingLayout.visibility = View.GONE
                reconnectAnim?.stop()
            }
        }
    }

    private fun handleLoggedInElsewhereDude() {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id != R.id.AuthFragment) {
            navController.navigate(R.id.AuthFragment)
            
            AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.error_logged_in_another_device)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkHandler.handleIntent(intent)
        intent?.getStringExtra("contact_account")?.let { account ->
            navigateToChat(account)
        }
    }

    private fun navigateToChat(account: String) {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main)
        val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()

        if (currentFragment is MainFragment && currentFragment.isTablet) {
            currentFragment.openChatOnTablet(account)
        } else {
            val bundle = bundleOf("account" to account)
            navController.navigate(R.id.MainFragment, bundle)
        }
    }

    private fun showInAppNotification(repository: MSNPRepository, incoming: com.limpe.tengerium.data.MessageManager.IncomingMessage) {
        notificationJob?.cancel()
        notificationJob = lifecycleScope.launch {
            if (securePrefs.chatSoundsEnabled) {
                SoundUtils.playSound(this@MainActivity, if (incoming.message == "[NUDGE]") R.raw.nudge else R.raw.type)
            }

            val viewBinding = ViewInAppNotificationBinding.inflate(LayoutInflater.from(this@MainActivity), binding.notificationContainer, false)
            viewBinding.tvNotificationName.text = FormattingUtils.formatBBCode(incoming.nickname.ifEmpty { incoming.senderAccount })
            viewBinding.tvNotificationMessage.text = when {
                incoming.message == "[NUDGE]" -> getString(R.string.nudge_received)
                com.limpe.tengerium.ui.InkStore.markerId(incoming.message) != null ->
                    getString(R.string.handwriting_preview)
                else -> com.limpe.tengerium.ui.WinkCatalog.fromMessage(incoming.message)
                    ?.let { "${it.emoji}  ${it.name}" }
                    ?: incoming.message
            }
            val contact = repository.contacts.value.find { it.account.equals(incoming.senderAccount, ignoreCase = true) }
            AvatarUtils.loadAvatar(viewBinding.ivNotificationAvatar, contact?.avatarUrl, incoming.senderAccount)

            viewBinding.root.setOnClickListener {
                navigateToChat(incoming.senderAccount)
                binding.notificationContainer.removeView(viewBinding.root)
                notificationJob?.cancel()
            }

            binding.notificationContainer.removeAllViews()
            binding.notificationContainer.addView(viewBinding.root)
            AnimationHelper.animateNotificationIn(viewBinding.root)
            delay(UiConstants.NOTIFICATION_DISPLAY_DELAY)
            AnimationHelper.animateNotificationOut(viewBinding.root) {
                binding.notificationContainer.removeView(viewBinding.root)
            }
        }
    }

    /** Classic "X has just signed in" alert: a chime plus a small banner. */
    private fun showSignInToast(repository: MSNPRepository, event: MSNPRepository.ContactSignIn) {
        if (!securePrefs.notifyLogin) return
        if (securePrefs.isChatMuted(event.account.lowercase(Locale.ROOT).trim())) return
        // Like the original: stay quiet while you are Busy or on the phone
        val mine = repository.currentStatus
        if (mine == MSNPProto.Status.BUSY || mine == MSNPProto.Status.PHONE) return

        if (securePrefs.chatSoundsEnabled) SoundUtils.playSound(this, R.raw.signin)
        // never replace a message banner that is currently showing
        if (notificationJob?.isActive == true) return

        notificationJob = lifecycleScope.launch {
            val viewBinding = ViewInAppNotificationBinding.inflate(LayoutInflater.from(this@MainActivity), binding.notificationContainer, false)
            viewBinding.tvNotificationName.text = FormattingUtils.formatBBCode(event.nickname)
            viewBinding.tvNotificationMessage.text = getString(R.string.msn_signed_in)
            val contact = repository.contacts.value.find { it.account.equals(event.account, ignoreCase = true) }
            AvatarUtils.loadAvatar(viewBinding.ivNotificationAvatar, contact?.avatarUrl, event.account)
            viewBinding.root.setOnClickListener {
                navigateToChat(event.account)
                binding.notificationContainer.removeView(viewBinding.root)
                notificationJob?.cancel()
            }
            binding.notificationContainer.removeAllViews()
            binding.notificationContainer.addView(viewBinding.root)
            AnimationHelper.animateNotificationIn(viewBinding.root)
            delay(UiConstants.NOTIFICATION_DISPLAY_DELAY)
            AnimationHelper.animateNotificationOut(viewBinding.root) {
                binding.notificationContainer.removeView(viewBinding.root)
            }
        }
    }

    private fun handleSessionExpired() {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        if (navController.currentDestination?.id != R.id.AuthFragment) {
            navController.navigate(R.id.AuthFragment)
            AlertDialog.Builder(this)
                .setTitle(R.string.session_expired_title)
                .setMessage(R.string.session_expired_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun checkTimeBomb(): Boolean {
        return false
    }

    override fun onStart() {
        super.onStart()
        com.limpe.tengerium.util.AppForeground.visible = true
    }

    override fun onStop() {
        com.limpe.tengerium.util.AppForeground.visible = false
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayoutListener)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}



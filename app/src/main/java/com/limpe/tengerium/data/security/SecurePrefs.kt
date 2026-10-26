package com.limpe.tengerium.data.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

class SecurePrefs(private val context: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            Log.e("SecurePrefs", "Failed to initialize EncryptedSharedPreferences, clearing and retrying", e)
            deleteSharedPreferences("secure_prefs")
            createEncryptedPrefs()
        }
    }

    private fun deleteSharedPreferences(name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences(name)
        } else {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            val file = File(dir, "$name.xml")
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val plainPrefs: SharedPreferences = 
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _prefsChangedFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val prefsChangedFlow = _prefsChangedFlow.asSharedFlow()

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) {
            _prefsChangedFlow.tryEmit(key)
        }
    }

    init {
        plainPrefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun saveDatabasePassphrase(passphrase: String) {
        encryptedPrefs.edit().putString("db_passphrase", passphrase).apply()
    }

    fun getDatabasePassphrase(): String? = safeGet { encryptedPrefs.getString("db_passphrase", null) }

    /** Saves the account; the password is saved only when [pass] is non-null, otherwise any stored one is removed. */
    fun saveCredentials(account: String, pass: String?) {
        val editor = encryptedPrefs.edit().putString("saved_account", account)
        if (pass != null) editor.putString("saved_pass", pass) else editor.remove("saved_pass")
        editor.apply()
    }

    fun getSavedAccount(): String? = safeGet { encryptedPrefs.getString("saved_account", null) }
    fun getSavedPassword(): String? = safeGet { encryptedPrefs.getString("saved_pass", null) }

    private fun <T> safeGet(block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            Log.e("SecurePrefs", "Error reading encrypted preference", e)
            null
        }
    }

    fun clearCredentials() {
        encryptedPrefs.edit()
            .remove("saved_account")
            .remove("saved_pass")
            .apply()
    }

    fun getPinnedChats(): Set<String> = plainPrefs.getStringSet("pinned_chats", emptySet()) ?: emptySet()
    
    fun setPinnedChats(chats: Set<String>) {
        plainPrefs.edit().putStringSet("pinned_chats", chats).apply()
        _prefsChangedFlow.tryEmit("pinned_chats")
    }

    fun isChatPinned(account: String): Boolean = getPinnedChats().contains(account.lowercase())

    fun togglePinChat(account: String) {
        val pinned = getPinnedChats().toMutableSet()
        val normalized = account.lowercase()
        if (pinned.contains(normalized)) pinned.remove(normalized) else pinned.add(normalized)
        setPinnedChats(pinned)
    }

    fun getMutedChats(): Set<String> = plainPrefs.getStringSet("muted_chats", emptySet()) ?: emptySet()
    
    fun setMutedChats(chats: Set<String>) {
        plainPrefs.edit().putStringSet("muted_chats", chats).apply()
        _prefsChangedFlow.tryEmit("muted_chats")
    }

    fun isChatMuted(account: String): Boolean = getMutedChats().contains(account.lowercase())

    fun toggleMuteChat(account: String) {
        val muted = getMutedChats().toMutableSet()
        val normalized = account.lowercase()
        if (muted.contains(normalized)) muted.remove(normalized) else muted.add(normalized)
        setMutedChats(muted)
    }

    fun isOobeDone(account: String): Boolean = plainPrefs.getBoolean("oobe_done_$account", false)
    
    fun setOobeDone(account: String, done: Boolean) {
        plainPrefs.edit().putBoolean("oobe_done_$account", done).apply()
    }

    fun getNickname(account: String): String? = plainPrefs.getString("nickname_$account", null)
    
    fun setNickname(account: String, nickname: String) {
        plainPrefs.edit().putString("nickname_$account", nickname).apply()
    }

    fun getMsnObject(account: String): String? = plainPrefs.getString("msn_object_$account", null)

    fun setMsnObject(account: String, msnObject: String?) {
        plainPrefs.edit().putString("msn_object_$account", msnObject).apply()
    }

    fun getAvatarPath(account: String): String? = plainPrefs.getString("avatar_path_$account", null)

    fun setAvatarPath(account: String, path: String?) {
        plainPrefs.edit().putString("avatar_path_$account", path).apply()
    }

    fun getCollapsedGroups(): Set<String> = plainPrefs.getStringSet("collapsed_groups", emptySet()) ?: emptySet()
    
    fun setCollapsedGroups(groups: Set<String>) {
        plainPrefs.edit().putStringSet("collapsed_groups", groups).apply()
    }

    // Позволяет отслеживать версию, на которой пользователь был в последний раз
    var lastUsedVersionCode: Int
        get() = plainPrefs.getInt("last_version_code", 0)
        set(value) = plainPrefs.edit().putInt("last_version_code", value).apply()

    var serverAddress: String
        get() {
            val s = plainPrefs.getString("server_address", "m1.escargot.chat")
            return if (s.isNullOrEmpty() || s == "mserver.escargot.chat" || s == "escargot.chat") "m1.escargot.chat" else s
        }
        set(value) = plainPrefs.edit().putString("server_address", value).apply()

    var serverPort: Int
        get() = plainPrefs.getInt("server_port", 1863)
        set(value) = plainPrefs.edit().putInt("server_port", value).apply()

    var nexusDomain: String
        get() {
            val n = plainPrefs.getString("nexus_domain", "m1.escargot.chat")
            return if (n.isNullOrEmpty() || n == "nexus.escargot.chat" || n == "escargot.chat") "m1.escargot.chat" else n
        }
        set(value) = plainPrefs.edit().putString("nexus_domain", value).apply()

    var configUrl: String
        get() = plainPrefs.getString("config_url", "") ?: ""
        set(value) = plainPrefs.edit().putString("config_url", value).apply()

    /** STUN server used to discover a path between the two callers. */
    var callStunServer: String
        get() = plainPrefs.getString("call_stun", "stun:stun.l.google.com:19302")
            ?: "stun:stun.l.google.com:19302"
        set(value) = plainPrefs.edit().putString("call_stun", value).apply()

    /** Optional TURN relay, needed when the two devices cannot reach each other directly. */
    var callTurnServer: String
        get() = plainPrefs.getString("call_turn", "") ?: ""
        set(value) = plainPrefs.edit().putString("call_turn", value).apply()

    var callTurnUsername: String
        get() = safeGet { encryptedPrefs.getString("call_turn_user", "") } ?: ""
        set(value) {
            encryptedPrefs.edit().putString("call_turn_user", value).apply()
        }

    var callTurnPassword: String
        get() = safeGet { encryptedPrefs.getString("call_turn_pass", "") } ?: ""
        set(value) {
            encryptedPrefs.edit().putString("call_turn_pass", value).apply()
        }

    var themePreset: Int
        get() = plainPrefs.getInt("theme_preset", 0)
        set(value) = plainPrefs.edit().putInt("theme_preset", value).apply()

    var chatBackgroundPath: String?
        get() = plainPrefs.getString("chat_bg_path", null)
        set(value) = plainPrefs.edit().putString("chat_bg_path", value).apply()

    var avatarPath: String?
        get() = plainPrefs.getString("avatar_path", null)
        set(value) = plainPrefs.edit().putString("avatar_path", value).apply()

    var useMaterialYou: Boolean
        get() = plainPrefs.getBoolean("use_material_you", true)
        set(value) = plainPrefs.edit().putBoolean("use_material_you", value).apply()

    var language: String
        get() = plainPrefs.getString("app_language", "system") ?: "system"
        set(value) = plainPrefs.edit().putString("app_language", value).apply()

    var showOnlineFirst: Boolean
        get() = plainPrefs.getBoolean("show_online_first", true)
        set(value) = plainPrefs.edit().putBoolean("show_online_first", value).apply()

    var privacyAllowOnlyFromList: Boolean
        get() = plainPrefs.getBoolean("privacy_only_from_list", false)
        set(value) = plainPrefs.edit().putBoolean("privacy_only_from_list", value).apply()

    var showLinkPreview: Boolean
        get() = plainPrefs.getBoolean("show_link_preview", true)
        set(value) = plainPrefs.edit().putBoolean("show_link_preview", value).apply()

    var sendByEnter: Boolean
        get() = plainPrefs.getBoolean("send_by_enter", true)
        set(value) = plainPrefs.edit().putBoolean("send_by_enter", value).apply()

    var openChatsByDefault: Boolean
        get() = plainPrefs.getBoolean("open_chats_by_default", false)
        set(value) = plainPrefs.edit().putBoolean("open_chats_by_default", value).apply()

    var followSystemTheme: Boolean
        get() = plainPrefs.getBoolean("follow_system_theme", true)
        set(value) = plainPrefs.edit().putBoolean("follow_system_theme", value).apply()

    var darkTheme: Boolean
        get() = plainPrefs.getBoolean("dark_theme", false)
        set(value) = plainPrefs.edit().putBoolean("dark_theme", value).apply()

    var phoneStatusEnabled: Boolean
        get() = plainPrefs.getBoolean("phone_status_enabled", false)
        set(value) = plainPrefs.edit().putBoolean("phone_status_enabled", value).apply()

    var rememberMe: Boolean
        get() = plainPrefs.getBoolean("remember_me", false)
        set(value) = plainPrefs.edit().putBoolean("remember_me", value).apply()

    /** "Remember my Password". Existing users who already had a saved password keep it on. */
    var rememberPassword: Boolean
        get() = if (plainPrefs.contains("remember_password")) plainPrefs.getBoolean("remember_password", false)
                else rememberMe && getSavedPassword() != null
        set(value) = plainPrefs.edit().putBoolean("remember_password", value).apply()

    /** "Sign me in automatically" (applies when the app starts). Existing users keep the old behaviour. */
    var autoSignIn: Boolean
        get() = if (plainPrefs.contains("auto_sign_in")) plainPrefs.getBoolean("auto_sign_in", false)
                else rememberPassword
        set(value) = plainPrefs.edit().putBoolean("auto_sign_in", value).apply()

    var disableMessageQueue: Boolean
        get() = plainPrefs.getBoolean("disable_message_queue", false)
        set(value) = plainPrefs.edit().putBoolean("disable_message_queue", value).apply()

    var notifyMessages: Boolean
        get() = plainPrefs.getBoolean("notify_messages", true)
        set(value) = plainPrefs.edit().putBoolean("notify_messages", value).apply()

    var notifyLogin: Boolean
        get() = plainPrefs.getBoolean("notify_login", true)
        set(value) = plainPrefs.edit().putBoolean("notify_login", value).apply()

    /** Colour (0xRRGGBB) applied to outgoing messages; 0 means the default text colour. */
    var messageColor: Int
        get() = plainPrefs.getInt("message_color", 0)
        set(value) = plainPrefs.edit().putInt("message_color", value).apply()

    var notifyNudge: Boolean
        get() = plainPrefs.getBoolean("notify_nudge", true)
        set(value) = plainPrefs.edit().putBoolean("notify_nudge", value).apply()

    var notifyAddedBy: Boolean
        get() = plainPrefs.getBoolean("notify_added_by", true)
        set(value) = plainPrefs.edit().putBoolean("notify_added_by", value).apply()

    var vibrationEnabled: Boolean
        get() = plainPrefs.getBoolean("vibration_enabled", true)
        set(value) = plainPrefs.edit().putBoolean("vibration_enabled", value).apply()

    var chatSoundsEnabled: Boolean
        get() = plainPrefs.getBoolean("chat_sounds_enabled", true)
        set(value) = plainPrefs.edit().putBoolean("chat_sounds_enabled", value).apply()
}



package com.limpe.tengerium.data.protocol

import java.io.File
import uniffi.msnp11_sdk.FileTransferRequest

/**
 * Интерфейс для Switchboard сессии независимо от версии протокола.
 */
interface IMSNPSwitchboardSession {
    fun connect(host: String, port: Int, account: String, key: String, isIncoming: Boolean, sessionId: String? = null, target: String? = null)
    fun disconnect()
    fun isClosed(): Boolean
    fun isJoined(): Boolean
    fun isConnecting(): Boolean
    fun sendMessage(text: String, messageId: Long = -1L)
    fun sendTyping()
    fun sendNudge(messageId: Long = -1L)
    fun requestAvatar(account: String, msnObject: String)
    suspend fun sendFile(account: String, fileName: String, file: File)
    suspend fun acceptFileRequest(request: FileTransferRequest): ByteArray
    suspend fun declineFileRequest(request: FileTransferRequest)
    
    // Групповые чаты
    fun inviteParticipant(account: String) {}
}

package com.limpe.tengerium.data

import com.limpe.tengerium.data.protocol.IMSNPSwitchboardSession
import uniffi.msnp11_sdk.FileTransferRequest

data class IncomingFileTransfer(
    val id: String,
    val target: String,
    val sender: String,
    val fileName: String,
    val fileSize: ULong,
    val request: FileTransferRequest,
    val session: IMSNPSwitchboardSession
)

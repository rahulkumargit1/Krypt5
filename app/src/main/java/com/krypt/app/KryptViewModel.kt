package com.krypt.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "KryptVM"

data class CallState(
    val isInCall: Boolean = false,
    val remoteUuid: String = "",
    val isIncoming: Boolean = false,
    val pendingOfferSdp: String = ""
)

data class UiState(
    val myUuid: String = "",
    val contacts: List<ContactEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val statuses: List<StatusEntity> = emptyList(),
    val conversationPreviews: Map<String, MessageEntity> = emptyMap(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val currentConversation: String = "",
    val isConnected: Boolean = false,
    val callState: CallState = CallState()
)

class KryptViewModel(private val context: Context) : ViewModel() {

    private val prefs: SharedPreferences = context.getSharedPreferences("krypt_prefs", Context.MODE_PRIVATE)
    private val db = KryptDatabase.getInstance(context)
    private val gson = Gson()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var myUuid: String = ""
    private var myPublicKey: String = ""
    private var myPrivateKey: String = ""

    var webRTCManager: WebRTCManager? = null

    private val incomingChunks = ConcurrentHashMap<String, ConcurrentHashMap<Int, ByteArray>>()
    private val incomingChunkMeta = ConcurrentHashMap<String, EncryptedFileChunk>()

    // Always-available internal files dir for storing received/sent files
    private val filesDir: File get() {
        val dir = File(context.filesDir, "krypt_files")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    companion object {
        private const val NOTIF_CHANNEL_ID = "krypt_messages"
        private const val NOTIF_CHANNEL_NAME = "Krypt Messages"
    }

    init {
        createNotificationChannel()
        initializeIdentity()
        observeContacts()
        observeStatuses()
        observeConversationPreviews()
        observeIncomingMessages()
    }

    // ─── Notifications ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL_ID, NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Krypt encrypted message notifications"
                enableVibration(true)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun showMessageNotification(fromUuid: String, content: String, nickname: String) {
        if (_uiState.value.currentConversation == fromUuid) return
        val displayName = nickname.ifBlank { fromUuid.take(12) + "…" }
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(displayName)
            .setContentText(content)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(fromUuid.hashCode(), notification)
    }

    private fun showFileNotification(fromUuid: String, fileName: String, nickname: String) {
        if (_uiState.value.currentConversation == fromUuid) return
        val displayName = nickname.ifBlank { fromUuid.take(12) + "…" }
        val notification = androidx.core.app.NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(displayName)
            .setContentText("Sent you: $fileName")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(fromUuid.hashCode() + 1, notification)
    }

    // ─── Identity ──────────────────────────────────────────────────────────

    private fun initializeIdentity() {
        myUuid = prefs.getString("uuid", null) ?: run {
            val newUuid = UUID.randomUUID().toString()
            prefs.edit().putString("uuid", newUuid).apply()
            newUuid
        }
        val storedPub = prefs.getString("public_key", null)
        val storedPriv = prefs.getString("private_key", null)
        if (storedPub != null && storedPriv != null) {
            myPublicKey = storedPub; myPrivateKey = storedPriv
        } else {
            val (pub, priv) = CryptoEngine.generateRSAKeyPair()
            myPublicKey = pub; myPrivateKey = priv
            prefs.edit().putString("public_key", pub).putString("private_key", priv).apply()
        }
        _uiState.update { it.copy(myUuid = myUuid) }
        NetworkClient.connect(myUuid, myPublicKey)
    }

    // ─── Contacts ──────────────────────────────────────────────────────────

    private fun observeContacts() {
        viewModelScope.launch {
            db.contactDao().getAllContacts().collect { contacts ->
                _uiState.update { it.copy(contacts = contacts) }
                contacts.forEach { contact ->
                    viewModelScope.launch {
                        db.messageDao().getUnreadCount(contact.uuid).collect { count ->
                            _uiState.update { state ->
                                state.copy(unreadCounts = state.unreadCounts + (contact.uuid to count))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeConversationPreviews() {
        viewModelScope.launch {
            db.messageDao().getConversationPreviews().collect { previews ->
                _uiState.update { it.copy(conversationPreviews = previews.associateBy { it.conversationId }) }
            }
        }
    }

    fun addContact(uuid: String, nickname: String) {
        viewModelScope.launch(Dispatchers.IO) {
            NetworkClient.requestPublicKey(uuid)
            db.contactDao().insertContact(ContactEntity(uuid = uuid, publicKey = "", nickname = nickname))
        }
    }

    fun editContact(uuid: String, newNickname: String) {
        viewModelScope.launch(Dispatchers.IO) { db.contactDao().updateNickname(uuid, newNickname) }
    }

    fun deleteContact(uuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.contactDao().deleteContact(uuid)
            db.messageDao().deleteConversation(uuid)
        }
    }

    fun retryKeyRequest(contactUuid: String) {
        viewModelScope.launch(Dispatchers.IO) { NetworkClient.requestPublicKey(contactUuid) }
    }

    // ─── Messages ──────────────────────────────────────────────────────────

    fun openConversation(contactUuid: String) {
        _uiState.update { it.copy(currentConversation = contactUuid) }
        viewModelScope.launch {
            db.messageDao().getMessages(contactUuid).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().markIncomingRead(contactUuid)
            NetworkClient.sendReadReceipt(contactUuid)
        }
    }

    fun closeConversation() { _uiState.update { it.copy(currentConversation = "") } }

    fun sendTextMessage(to: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val contact = db.contactDao().getContact(to) ?: return@launch
            if (contact.publicKey.isEmpty()) { NetworkClient.requestPublicKey(to); return@launch }
            try {
                val payload = CryptoEngine.encryptMessage(text, contact.publicKey)
                val sent = NetworkClient.sendEncryptedMessage(to, payload)
                db.messageDao().insertMessage(MessageEntity(
                    conversationId = to, fromUuid = myUuid, content = text,
                    contentType = "text", isSent = true, isDelivered = false, isRead = false
                ))
                if (!sent) Log.w(TAG, "Message stored locally — send failed")
            } catch (e: Exception) {
                Log.e(TAG, "sendTextMessage error", e)
                NetworkClient.requestPublicKey(to)
            }
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch(Dispatchers.IO) { db.messageDao().deleteMessage(messageId) }
    }

    fun deleteChat(contactUuid: String) {
        viewModelScope.launch(Dispatchers.IO) { db.messageDao().deleteConversation(contactUuid) }
    }

    fun sendFile(to: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val contact = db.contactDao().getContact(to) ?: return@launch
            if (contact.publicKey.isEmpty()) { NetworkClient.requestPublicKey(to); return@launch }
            try {
                val contentResolver = context.contentResolver
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

                // Only allow image, PDF, text
                val isAllowed = mimeType.startsWith("image/") ||
                        mimeType == "application/pdf" ||
                        mimeType.startsWith("text/")
                if (!isAllowed) {
                    Log.w(TAG, "File type not allowed: $mimeType"); return@launch
                }

                val ext = when {
                    mimeType == "image/jpeg" -> "jpg"
                    mimeType == "image/png"  -> "png"
                    mimeType == "image/gif"  -> "gif"
                    mimeType == "image/webp" -> "webp"
                    mimeType == "application/pdf" -> "pdf"
                    mimeType.startsWith("text/") -> "txt"
                    else -> "bin"
                }

                val fileName = "sent_${System.currentTimeMillis()}.$ext"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: run { Log.e(TAG, "Cannot open stream for $uri"); return@launch }

                Log.d(TAG, "Sending: $fileName size=${bytes.size} mime=$mimeType")

                // Save to internal storage
                val sentFile = File(filesDir, fileName)
                sentFile.writeBytes(bytes)

                // Encrypt and send in chunks
                val transferId = UUID.randomUUID().toString()
                val chunks = CryptoEngine.encryptFileChunks(bytes, fileName, mimeType, contact.publicKey, transferId)

                Log.d(TAG, "Sending ${chunks.size} chunks")
                var failCount = 0
                chunks.forEach { chunk ->
                    if (!NetworkClient.sendFileChunk(to, chunk)) failCount++
                    Thread.sleep(80)
                }
                if (failCount > 0) Log.e(TAG, "$failCount/${chunks.size} chunks failed to send")

                val contentType = if (mimeType.startsWith("image/")) "image" else "file"
                db.messageDao().insertMessage(MessageEntity(
                    conversationId = to, fromUuid = myUuid,
                    content = fileName, contentType = contentType,
                    filePath = sentFile.absolutePath, isSent = true
                ))
            } catch (e: Exception) { Log.e(TAG, "sendFile error", e) }
        }
    }

    // ─── Status ────────────────────────────────────────────────────────────

    private fun observeStatuses() {
        viewModelScope.launch {
            db.statusDao().getActiveStatuses().collect { _uiState.update { s -> s.copy(statuses = it) } }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) { db.statusDao().deleteExpiredStatuses(); Thread.sleep(60_000) }
        }
    }

    fun postStatus(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                NetworkClient.sendStatusPlain(myUuid, text)
                db.statusDao().insertStatus(StatusEntity(fromUuid = myUuid, content = text))
            } catch (e: Exception) { Log.e(TAG, "postStatus error", e) }
        }
    }

    // ─── Incoming ──────────────────────────────────────────────────────────

    private fun observeIncomingMessages() {
        viewModelScope.launch { NetworkClient.incomingMessages.collect { handleIncoming(it) } }
    }

    private suspend fun handleIncoming(raw: String) = withContext(Dispatchers.IO) {
        val json = NetworkClient.parseMessage(raw) ?: return@withContext
        val type = json.get("type")?.asString ?: return@withContext

        when (type) {
            "message" -> {
                val from = json.get("from")?.asString ?: return@withContext
                val receiptType = json.get("receipt_type")?.asString
                if (receiptType != null) {
                    when (receiptType) {
                        "delivered" -> {
                            val msgId = json.get("message_ref_id")?.asLong ?: return@withContext
                            db.messageDao().markDelivered(msgId)
                        }
                        "read_all" -> db.messageDao().markAllRead(from)
                    }
                    return@withContext
                }
                val payloadObj = json.getAsJsonObject("payload") ?: return@withContext
                val payload = EncryptedPayload(
                    encryptedData = payloadObj.get("encryptedData").asString,
                    iv = payloadObj.get("iv").asString,
                    encryptedKey = payloadObj.get("encryptedKey").asString
                )
                try {
                    val text = CryptoEngine.decryptMessage(payload, myPrivateKey)
                    val msg = MessageEntity(conversationId = from, fromUuid = from, content = text,
                        contentType = "text", isSent = false, isDelivered = true, isRead = false)
                    val msgId = db.messageDao().insertMessage(msg)
                    NetworkClient.sendReceipt(from, msgId, "delivered")
                    if (_uiState.value.currentConversation == from) {
                        db.messageDao().markIncomingRead(from)
                        NetworkClient.sendReadReceipt(from)
                    } else {
                        val contact = db.contactDao().getContact(from)
                        withContext(Dispatchers.Main) { showMessageNotification(from, text, contact?.nickname ?: "") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Decrypt message failed", e)
                    NetworkClient.requestPublicKey(from)
                }
            }

            "file_chunk" -> {
                val from = json.get("from")?.asString ?: return@withContext
                val payloadObj = json.getAsJsonObject("payload") ?: return@withContext
                try {
                    val chunk = gson.fromJson(payloadObj, EncryptedFileChunk::class.java)
                    val key = chunk.transferId.ifEmpty { "${from}_${chunk.fileName}_${chunk.totalChunks}" }

                    Log.d(TAG, "Chunk ${chunk.chunkIndex+1}/${chunk.totalChunks} for transfer $key mime=${chunk.mimeType}")

                    val chunkMap = incomingChunks.getOrPut(key) { ConcurrentHashMap() }
                    val decrypted = CryptoEngine.decryptFileChunk(chunk, myPrivateKey)
                    chunkMap[chunk.chunkIndex] = decrypted
                    incomingChunkMeta[key] = chunk

                    Log.d(TAG, "Have ${chunkMap.size}/${chunk.totalChunks} chunks")

                    if (chunkMap.size == chunk.totalChunks) {
                        // Assemble in order
                        val assembled = mutableListOf<Byte>()
                        for (i in 0 until chunk.totalChunks) {
                            val part = chunkMap[i]
                            if (part == null) {
                                Log.e(TAG, "Missing chunk $i — aborting assembly")
                                incomingChunks.remove(key); incomingChunkMeta.remove(key)
                                return@withContext
                            }
                            assembled.addAll(part.toList())
                        }
                        val fullBytes = assembled.toByteArray()

                        // Save to internal storage
                        val receivedFile = File(filesDir, "recv_${System.currentTimeMillis()}_${chunk.fileName}")
                        receivedFile.writeBytes(fullBytes)

                        Log.d(TAG, "File saved: ${receivedFile.absolutePath} (${fullBytes.size} bytes)")

                        val contentType = if (chunk.mimeType.startsWith("image/")) "image" else "file"
                        db.messageDao().insertMessage(MessageEntity(
                            conversationId = from,
                            fromUuid = from,
                            content = chunk.fileName,
                            contentType = contentType,
                            filePath = receivedFile.absolutePath,
                            isSent = false
                        ))

                        incomingChunks.remove(key); incomingChunkMeta.remove(key)

                        if (_uiState.value.currentConversation != from) {
                            val contact = db.contactDao().getContact(from)
                            withContext(Dispatchers.Main) {
                                showFileNotification(from, chunk.fileName, contact?.nickname ?: "")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "file_chunk error", e)
                    try {
                        val chunk = gson.fromJson(payloadObj, EncryptedFileChunk::class.java)
                        val key = chunk.transferId.ifEmpty { "${from}_${chunk.fileName}_${chunk.totalChunks}" }
                        incomingChunks.remove(key); incomingChunkMeta.remove(key)
                    } catch (_: Exception) {}
                }
            }

            "status" -> {
                val from = json.get("from")?.asString ?: return@withContext
                if (from == myUuid) return@withContext
                val content = json.get("content")?.asString ?: return@withContext
                if (db.contactDao().getContact(from) != null) {
                    db.statusDao().insertStatus(StatusEntity(fromUuid = from, content = content))
                }
            }

            "public_key_response" -> {
                val targetUuid = json.get("target")?.asString ?: return@withContext
                val publicKey = json.get("public_key")?.asString ?: return@withContext
                val existing = db.contactDao().getContact(targetUuid)
                if (existing != null) db.contactDao().insertContact(existing.copy(publicKey = publicKey))
                else db.contactDao().insertContact(ContactEntity(uuid = targetUuid, publicKey = publicKey))
                Log.d(TAG, "Public key received for $targetUuid")
            }

            "webrtc_offer" -> {
                val from = json.get("from")?.asString ?: return@withContext
                val sdp = json.get("sdp")?.asString ?: return@withContext
                _uiState.update { it.copy(callState = CallState(isInCall = true, remoteUuid = from, isIncoming = true, pendingOfferSdp = sdp)) }
            }

            "webrtc_answer" -> {
                val sdp = json.get("sdp")?.asString ?: return@withContext
                try { webRTCManager?.setRemoteAnswer(SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
                catch (e: Exception) { Log.e(TAG, "setRemoteAnswer error", e) }
            }

            "webrtc_ice" -> {
                val candidate = json.get("candidate")?.asString ?: return@withContext
                val sdpMid = json.get("sdpMid")?.asString
                val sdpMLineIndex = json.get("sdpMLineIndex")?.asInt ?: 0
                try { webRTCManager?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate)) }
                catch (e: Exception) { Log.e(TAG, "addIceCandidate error", e) }
            }
        }
    }

    // ─── WebRTC ────────────────────────────────────────────────────────────

    fun startCall(remoteUuid: String) {
        _uiState.update { it.copy(callState = CallState(isInCall = true, remoteUuid = remoteUuid)) }
        try {
            webRTCManager = WebRTCManager(
                context = context, localUuid = myUuid, remoteUuid = remoteUuid,
                onIceCandidate = { candidate ->
                    NetworkClient.sendICECandidate(remoteUuid, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                },
                onLocalSdp = { sdp ->
                    if (sdp.type == SessionDescription.Type.OFFER) NetworkClient.sendWebRTCOffer(remoteUuid, sdp.description)
                    else NetworkClient.sendWebRTCAnswer(remoteUuid, sdp.description)
                },
                onCallEnded = { viewModelScope.launch(Dispatchers.Main) { endCall() } }
            )
            webRTCManager?.createOffer()
        } catch (e: Exception) { Log.e(TAG, "startCall error", e); endCall() }
    }

    fun acceptCall() {
        val callState = _uiState.value.callState
        val remoteUuid = callState.remoteUuid
        val offerSdp = callState.pendingOfferSdp
        try {
            webRTCManager = WebRTCManager(
                context = context, localUuid = myUuid, remoteUuid = remoteUuid,
                onIceCandidate = { candidate ->
                    NetworkClient.sendICECandidate(remoteUuid, candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                },
                onLocalSdp = { sdp -> NetworkClient.sendWebRTCAnswer(remoteUuid, sdp.description) },
                onCallEnded = { viewModelScope.launch(Dispatchers.Main) { endCall() } }
            )
            webRTCManager?.createAnswer(SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            _uiState.update { it.copy(callState = callState.copy(isIncoming = false, pendingOfferSdp = "")) }
        } catch (e: Exception) { Log.e(TAG, "acceptCall error", e); endCall() }
    }

    fun endCall() {
        try { webRTCManager?.endCall() } catch (_: Exception) {}
        webRTCManager = null
        _uiState.update { it.copy(callState = CallState()) }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            KryptViewModel(context.applicationContext) as T
    }
}

val Gson = Gson()

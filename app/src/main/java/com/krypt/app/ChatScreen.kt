package com.krypt.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ─── Contacts Screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(
    viewModel: KryptViewModel,
    onOpenChat: (String) -> Unit,
    onOpenStatus: () -> Unit,
    onToggleTheme: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<ContactEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf<ContactEntity?>(null) }
    var newUuid by remember { mutableStateOf("") }
    var newNick by remember { mutableStateOf("") }
    var editNick by remember { mutableStateOf("") }
    var showCopied by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Krypt",
                        color = KryptTheme.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 2.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KryptTheme.bg),
                actions = {
                    // Theme toggle
                    IconButton(onClick = onToggleTheme) {
                        Icon(
                            if (KryptTheme.isDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Theme",
                            tint = KryptTheme.subtext,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onOpenStatus) {
                        Icon(Icons.Default.RadioButtonChecked, "Status", tint = KryptTheme.subtext, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.PersonAdd, "Add", tint = KryptTheme.subtext, modifier = Modifier.size(20.dp))
                    }
                }
            )
        },
        containerColor = KryptTheme.bg
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // My ID card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(KryptTheme.card)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Your ID", color = KryptTheme.subtext, fontSize = 10.sp, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        uiState.myUuid, color = KryptTheme.text, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Krypt ID", uiState.myUuid))
                        showCopied = true
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (showCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                        null,
                        tint = if (showCopied) KryptTheme.success else KryptTheme.subtext,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            LaunchedEffect(showCopied) {
                if (showCopied) { kotlinx.coroutines.delay(2000); showCopied = false }
            }

            HorizontalDivider(color = KryptTheme.divider, thickness = 0.5.dp)

            if (uiState.contacts.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔒", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("No contacts yet", color = KryptTheme.text, fontSize = 16.sp, fontWeight = FontWeight.Light)
                        Spacer(Modifier.height(4.dp))
                        Text("Tap + to add someone securely", color = KryptTheme.subtext, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn {
                    items(uiState.contacts) { contact ->
                        val unread = uiState.unreadCounts[contact.uuid] ?: 0
                        val preview = uiState.conversationPreviews[contact.uuid]
                        ContactRow(contact, unread, preview, onClick = { onOpenChat(contact.uuid) },
                            onLongClick = { showEditDialog = contact })
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = KryptTheme.divider,
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }

    // Add contact dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newUuid = ""; newNick = "" },
            containerColor = KryptTheme.card,
            title = { Text("New Contact", color = KryptTheme.text, fontWeight = FontWeight.Normal, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newUuid, onValueChange = { newUuid = it },
                        label = { Text("Krypt ID", color = KryptTheme.subtext, fontSize = 12.sp) },
                        colors = kryptTextFieldColors(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newNick, onValueChange = { newNick = it },
                        label = { Text("Nickname (optional)", color = KryptTheme.subtext, fontSize = 12.sp) },
                        colors = kryptTextFieldColors(), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newUuid.isNotBlank()) { viewModel.addContact(newUuid.trim(), newNick.trim()); newUuid = ""; newNick = ""; showAddDialog = false }
                }) { Text("Add", color = KryptTheme.text, fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newUuid = ""; newNick = "" }) {
                    Text("Cancel", color = KryptTheme.subtext)
                }
            }
        )
    }

    showEditDialog?.let { contact ->
        LaunchedEffect(contact) { editNick = contact.nickname }
        AlertDialog(
            onDismissRequest = { showEditDialog = null },
            containerColor = KryptTheme.card,
            title = { Text("Contact", color = KryptTheme.text, fontWeight = FontWeight.Normal, fontSize = 18.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = editNick, onValueChange = { editNick = it },
                        label = { Text("Nickname", color = KryptTheme.subtext, fontSize = 12.sp) },
                        colors = kryptTextFieldColors(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { viewModel.deleteChat(contact.uuid); showEditDialog = null },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Clear chat history", color = KryptTheme.danger, fontSize = 13.sp)
                    }
                    TextButton(onClick = { showDeleteDialog = contact; showEditDialog = null },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Delete contact", color = KryptTheme.danger, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.editContact(contact.uuid, editNick.trim()); showEditDialog = null }) {
                    Text("Save", color = KryptTheme.text, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = null }) { Text("Cancel", color = KryptTheme.subtext) } }
        )
    }

    showDeleteDialog?.let { contact ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = KryptTheme.card,
            title = { Text("Delete Contact?", color = KryptTheme.text) },
            text = { Text("This will permanently delete ${contact.nickname.ifBlank { "this contact" }} and all messages.", color = KryptTheme.subtext, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteContact(contact.uuid); showDeleteDialog = null }) {
                    Text("Delete", color = KryptTheme.danger, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel", color = KryptTheme.subtext) } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(
    contact: ContactEntity,
    unreadCount: Int,
    lastMessage: MessageEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val displayName = contact.nickname.ifBlank { contact.uuid.take(12) }
    val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(KryptTheme.card),
            contentAlignment = Alignment.Center
        ) {
            Text(initial, fontSize = 18.sp, fontWeight = FontWeight.Light, color = KryptTheme.text)
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayName, color = KryptTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Normal,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (lastMessage != null) {
                    Text(formatTime(lastMessage.timestamp), color = KryptTheme.subtext, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        lastMessage == null -> "No messages yet"
                        lastMessage.contentType == "image" -> "📷 Photo"
                        lastMessage.contentType == "file" -> "📎 File"
                        else -> lastMessage.content
                    },
                    color = KryptTheme.subtext,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier.size(18.dp).clip(CircleShape).background(KryptTheme.text),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (unreadCount > 99) "99+" else unreadCount.toString(),
                            color = KryptTheme.bg, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ─── Chat Screen ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: KryptViewModel,
    contactUuid: String,
    onStartCall: () -> Unit,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var showCamera by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showDeleteChatDialog by remember { mutableStateOf(false) }
    var showMenuExpanded by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<MessageEntity?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val contact = uiState.contacts.find { it.uuid == contactUuid }
    val displayName = contact?.nickname?.ifBlank { contactUuid.take(12) } ?: contactUuid.take(12)
    val keyReady = contact?.publicKey?.isNotEmpty() == true

    // File pickers
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.sendFile(contactUuid, it) }
    }
    val docLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.sendFile(contactUuid, it) }
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCamera = true
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) listState.animateScrollToItem(uiState.messages.size - 1)
    }

    if (showCamera) {
        CameraScreen(
            onPhotoTaken = { uri -> showCamera = false; viewModel.sendFile(contactUuid, uri) },
            onDismiss = { showCamera = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape).background(KryptTheme.card),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(displayName.first().uppercaseChar().toString(),
                                color = KryptTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Light)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(displayName, color = KryptTheme.text, fontSize = 15.sp, fontWeight = FontWeight.Normal)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Lock, null, tint = KryptTheme.subtext, modifier = Modifier.size(9.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("End-to-end encrypted", color = KryptTheme.subtext, fontSize = 10.sp)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeConversation(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = KryptTheme.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KryptTheme.surface),
                actions = {
                    IconButton(onClick = onStartCall) {
                        Icon(Icons.Default.Call, "Call", tint = KryptTheme.subtext, modifier = Modifier.size(20.dp))
                    }
                    Box {
                        IconButton(onClick = { showMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, "Menu", tint = KryptTheme.subtext, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = showMenuExpanded, onDismissRequest = { showMenuExpanded = false },
                            modifier = Modifier.background(KryptTheme.card)) {
                            DropdownMenuItem(
                                text = { Text("Clear chat", color = KryptTheme.text, fontSize = 14.sp) },
                                onClick = { showDeleteChatDialog = true; showMenuExpanded = false }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Key status banner
                if (!keyReady) {
                    Row(
                        modifier = Modifier.fillMaxWidth().background(KryptTheme.card)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.HourglassEmpty, null, tint = KryptTheme.subtext, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Waiting for encryption key — ask them to open Krypt.",
                            color = KryptTheme.subtext, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.retryKeyRequest(contactUuid) }, contentPadding = PaddingValues(0.dp)) {
                            Text("Retry", color = KryptTheme.text, fontSize = 12.sp)
                        }
                    }
                }
                // Input bar
                Row(
                    modifier = Modifier
                        .background(KryptTheme.surface)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showAttachSheet = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.AttachFile, "Attach", tint = KryptTheme.subtext, modifier = Modifier.size(20.dp))
                    }
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Message", color = KryptTheme.subtext, fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        colors = kryptTextFieldColors(),
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        enabled = keyReady,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inputText.isNotBlank() && keyReady) {
                                viewModel.sendTextMessage(contactUuid, inputText.trim()); inputText = ""
                            }
                        })
                    )
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank() && keyReady) {
                                viewModel.sendTextMessage(contactUuid, inputText.trim()); inputText = ""
                            }
                        },
                        enabled = keyReady && inputText.isNotBlank(),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Send, "Send",
                            tint = if (keyReady && inputText.isNotBlank()) KryptTheme.text else KryptTheme.subtext,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        containerColor = KryptTheme.bg
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(uiState.messages, key = { it.id }) { msg ->
                MessageBubble(msg, uiState.myUuid, onLongClick = { selectedMessage = msg })
            }
        }
    }

    // Attach sheet
    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            containerColor = KryptTheme.card,
            dragHandle = {
                Box(Modifier.padding(vertical = 8.dp).width(36.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(KryptTheme.divider))
            }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Text("Send", color = KryptTheme.subtext, fontSize = 12.sp, letterSpacing = 1.sp,
                    modifier = Modifier.padding(vertical = 12.dp))
                AttachOption(Icons.Default.CameraAlt, "Camera", "Take a photo") {
                    showAttachSheet = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                        showCamera = true
                    else cameraPermLauncher.launch(Manifest.permission.CAMERA)
                }
                AttachOption(Icons.Default.Image, "Photo / Image", "JPG, PNG from gallery") {
                    showAttachSheet = false; imageLauncher.launch("image/*")
                }
                AttachOption(Icons.Default.InsertDriveFile, "Document", "PDF or TXT file") {
                    showAttachSheet = false; docLauncher.launch("*/*")
                }
            }
        }
    }

    // Message options
    selectedMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { selectedMessage = null },
            containerColor = KryptTheme.card,
            title = { Text("Message", color = KryptTheme.text, fontWeight = FontWeight.Normal) },
            text = {
                Column {
                    if (msg.contentType == "text") {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Message", msg.content))
                            selectedMessage = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Copy text", color = KryptTheme.text, fontSize = 14.sp)
                        }
                    }
                    TextButton(onClick = { viewModel.deleteMessage(msg.id); selectedMessage = null },
                        modifier = Modifier.fillMaxWidth()) {
                        Text("Delete message", color = KryptTheme.danger, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedMessage = null }) { Text("Cancel", color = KryptTheme.subtext) } }
        )
    }

    if (showDeleteChatDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteChatDialog = false },
            containerColor = KryptTheme.card,
            title = { Text("Clear chat?", color = KryptTheme.text) },
            text = { Text("All messages will be permanently deleted.", color = KryptTheme.subtext, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteChat(contactUuid); showDeleteChatDialog = false }) {
                    Text("Clear", color = KryptTheme.danger, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteChatDialog = false }) { Text("Cancel", color = KryptTheme.subtext) } }
        )
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(KryptTheme.divider),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = KryptTheme.text, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = KryptTheme.text, fontSize = 14.sp)
            Text(subtitle, color = KryptTheme.subtext, fontSize = 12.sp)
        }
    }
}

// ─── Message Bubble ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: MessageEntity, myUuid: String, onLongClick: () -> Unit) {
    val isMine = message.fromUuid == myUuid
    val context = LocalContext.current

    val bubbleBg = when {
        isMine && KryptTheme.isDark -> Color(0xFF1C1C1C)
        isMine && !KryptTheme.isDark -> Color(0xFFEDEDED)
        !isMine && KryptTheme.isDark -> Color(0xFF111111)
        else -> Color(0xFFFFFFFF)
    }
    val textColor = KryptTheme.text

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isMine) 18.dp else 4.dp,
                    bottomEnd = if (isMine) 4.dp else 18.dp
                ))
                .background(bubbleBg)
                .border(
                    width = if (isMine) 0.dp else 0.5.dp,
                    color = KryptTheme.divider,
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (isMine) 18.dp else 4.dp,
                        bottomEnd = if (isMine) 4.dp else 18.dp)
                )
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 280.dp)
        ) {
            Column {
                when (message.contentType) {
                    "image" -> {
                        val imgFile = if (!message.filePath.isNullOrEmpty()) File(message.filePath) else null
                        if (imgFile != null && imgFile.exists() && imgFile.length() > 0) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(imgFile)
                                    .crossfade(true)
                                    .diskCacheKey(imgFile.absolutePath)
                                    .memoryCacheKey(imgFile.absolutePath)
                                    .build(),
                                contentDescription = "Image",
                                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 240.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Image, null, tint = KryptTheme.subtext, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Photo", color = KryptTheme.subtext, fontSize = 13.sp)
                            }
                        }
                    }
                    "file" -> {
                        val fileName = message.content.removePrefix("[sent: ").removePrefix("[received: ").removeSuffix("]")
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Box(
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(KryptTheme.divider),
                                contentAlignment = Alignment.Center
                            ) {
                                val ext = fileName.substringAfterLast(".").lowercase()
                                Text(if (ext == "pdf") "PDF" else if (ext == "txt") "TXT" else "DOC",
                                    color = textColor, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(fileName, color = textColor, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    else -> Text(message.content, color = textColor, fontSize = 14.sp)
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End,
                    modifier = Modifier.align(Alignment.End)) {
                    Text(formatTime(message.timestamp), color = KryptTheme.subtext, fontSize = 10.sp)
                    if (isMine) {
                        Spacer(Modifier.width(4.dp))
                        MessageStatusIcon(message)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageStatusIcon(message: MessageEntity) {
    val color = when {
        message.isRead -> KryptTheme.text
        message.isDelivered -> KryptTheme.subtext
        else -> KryptTheme.subtext.copy(alpha = 0.5f)
    }
    Text(
        if (message.isDelivered || message.isRead) "✓✓" else "✓",
        color = color, fontSize = 10.sp
    )
}

// ─── CameraX ─────────────────────────────────────────────────────────────────

@Composable
fun CameraScreen(onPhotoTaken: (Uri) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: Exception) { onDismiss() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Shutter button
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Cancel", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Box(
                    modifier = Modifier.size(68.dp).clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                        .padding(6.dp).clip(CircleShape).background(Color.White)
                        .clickable(onClick = {
                            val outputFile = File(context.getExternalFilesDir(null), "krypt_${System.currentTimeMillis()}.jpg")
                            val opts = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                            imageCapture.takePicture(opts, ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { onPhotoTaken(Uri.fromFile(outputFile)) }
                                    override fun onError(e: ImageCaptureException) { onDismiss() }
                                })
                        })
                ) {}
            }
        }
    }
}

// ─── Status Screen ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(viewModel: KryptViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var statusText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Status", color = KryptTheme.text, fontWeight = FontWeight.Light, letterSpacing = 1.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = KryptTheme.text) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = KryptTheme.surface)
            )
        },
        containerColor = KryptTheme.bg
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = statusText, onValueChange = { statusText = it },
                placeholder = { Text("What's on your mind? (expires in 24h)", color = KryptTheme.subtext, fontSize = 13.sp) },
                modifier = Modifier.fillMaxWidth(), colors = kryptTextFieldColors(), maxLines = 4
            )
            Button(
                onClick = { if (statusText.isNotBlank()) { viewModel.postStatus(statusText.trim()); statusText = "" } },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = KryptTheme.text),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Post", color = KryptTheme.bg, fontWeight = FontWeight.Medium) }

            if (uiState.statuses.isNotEmpty()) {
                Text("Active", color = KryptTheme.subtext, fontSize = 11.sp, letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.statuses) { status ->
                        val statusContact = uiState.contacts.find { it.uuid == status.fromUuid }
                        val statusName = when {
                            status.fromUuid == uiState.myUuid -> "You"
                            statusContact?.nickname?.isNotBlank() == true -> statusContact.nickname
                            else -> status.fromUuid.take(12) + "…"
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(KryptTheme.card).padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(28.dp).clip(CircleShape).background(KryptTheme.divider),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(statusName.first().uppercaseChar().toString(),
                                        color = KryptTheme.text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(statusName, color = KryptTheme.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(status.content, color = KryptTheme.text, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────



@Composable
fun kryptTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = KryptTheme.text,
    unfocusedTextColor = KryptTheme.text,
    focusedBorderColor = KryptTheme.text.copy(alpha = 0.4f),
    unfocusedBorderColor = KryptTheme.divider,
    cursorColor = KryptTheme.text,
    focusedContainerColor = KryptTheme.surface,
    unfocusedContainerColor = KryptTheme.surface
)

fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "now"
        diff < 86400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
    }
}

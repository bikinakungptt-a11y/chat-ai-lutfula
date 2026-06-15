package com.example.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.OutlineDark
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.PrimaryNeon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStudio: () -> Unit = {}
) {
    val localContext = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var inputText by remember { mutableStateOf("") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedFileIsImage by remember { mutableStateOf(false) }
    
    var showAttachmentMenu by remember { mutableStateOf(false) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> 
            if (uri != null) {
                val contentResolver = localContext.contentResolver
                val mimeType = contentResolver.getType(uri) ?: ""
                
                var name = "Image"
                var size = 0L
                contentResolver.query(uri, null, null, null, null)?.use { cursor: android.database.Cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) name = cursor.getString(nameIndex)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }
                
                if (size > 20 * 1024 * 1024) {
                    android.widget.Toast.makeText(localContext, "File terlalu besar (max 20MB)", android.widget.Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }

                selectedFileUri = uri
                selectedFileName = name
                selectedFileIsImage = true
            }
        }
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> 
            if (uri != null) {
                val contentResolver = localContext.contentResolver
                val mimeType = contentResolver.getType(uri) ?: ""
                
                var name = "Unknown file"
                var size = 0L
                contentResolver.query(uri, null, null, null, null)?.use { cursor: android.database.Cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) name = cursor.getString(nameIndex)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }
                
                if (size > 20 * 1024 * 1024) {
                    android.widget.Toast.makeText(localContext, "File terlalu besar (max 20MB)", android.widget.Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }

                selectedFileUri = uri
                selectedFileName = name
                selectedFileIsImage = mimeType.startsWith("image/")
            }
        }
    )
    val listState = rememberLazyListState()
    var showHistoryDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<Long?>(null) }
    
    var showMicPermissionDialog by remember { mutableStateOf(false) }

    val micPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.widget.Toast.makeText(localContext, "Microphone access granted. Ready for voice input.", android.widget.Toast.LENGTH_SHORT).show()
            // Voice input logic would go here
        } else {
            android.widget.Toast.makeText(localContext, "Microphone permission denied. You can enable it in Settings.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val lastMessageId = uiState.messages.lastOrNull()?.id
    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)

    LaunchedEffect(lastMessageId, uiState.isLoading, imeBottom) {
        kotlinx.coroutines.delay(150)
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    if (showMicPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showMicPermissionDialog = false },
            title = { Text("Permission Required", color = Color.White) },
            text = { Text("Microphone access is needed for voice input.", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    showMicPermissionDialog = false
                    micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }) { Text("OK", color = PrimaryNeon) }
            },
            dismissButton = {
                TextButton(onClick = { showMicPermissionDialog = false }) { Text("Cancel", color = Color.Gray) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
    
    if (sessionToDelete != null) {
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete Chat", color = Color.White) },
            text = { Text("Delete this chat history?", color = Color.White) },
            confirmButton = {
                TextButton(onClick = { 
                    viewModel.deleteSession(sessionToDelete!!)
                    sessionToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) { Text("Cancel", color = Color.Gray) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("Chat History", color = Color.White) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(uiState.sessions) { session ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectSession(session.id)
                                    showHistoryDialog = false
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(session.title, color = Color.White, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { sessionToDelete = session.id },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete this chat history", tint = Color.Gray, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    if (uiState.sessions.isEmpty()) {
                        item {
                            Text("No history yet.", color = Color.Gray, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text("Close")
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                Spacer(modifier = Modifier.height(56.dp))

            if (uiState.error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(
                        items = uiState.messages,
                        key = { it.id }
                    ) { message ->
                        Box(modifier = Modifier.animateItem()) {
                            MessageBubble(message)
                        }
                    }
                    if (uiState.isLoading) {
                        item(key = "loading_indicator") {
                            Box(modifier = Modifier.animateItem()) {
                                TypingBubble(uiState.loadingText)
                            }
                        }
                    }
                    
                    item(key = "bottom_anchor") {
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }

                val showScrollToBottom by remember {
                    derivedStateOf { listState.canScrollForward }
                }
                
                if (showScrollToBottom) {
                    val scope = rememberCoroutineScope()
                    IconButton(
                        onClick = {
                            scope.launch {
                                kotlinx.coroutines.delay(50)
                                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                                if (lastIndex >= 0) {
                                    listState.animateScrollToItem(lastIndex)
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp)
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDownward,
                            contentDescription = "Scroll to bottom",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Input Row
            Column {
                if (selectedFileUri != null) {
                    Box(modifier = Modifier.padding(start = 24.dp, bottom = 0.dp)) {
                        if (selectedFileIsImage) {
                            AsyncImage(
                                model = selectedFileUri,
                                contentDescription = "Selected image preview",
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(8.dp)
                                    .padding(end = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Description, contentDescription = "File", tint = Color.White)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(selectedFileName ?: "File", color = Color.White, fontSize = 14.sp, maxLines = 1)
                            }
                        }
                        IconButton(
                            onClick = { 
                                selectedFileUri = null 
                                selectedFileName = null
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 8.dp, y = (-8).dp)
                                .size(24.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(Icons.Filled.Clear, contentDescription = "Remove file", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .border(1.dp, OutlineDark, CircleShape)
                        .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(
                                onClick = { showAttachmentMenu = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Filled.AttachFile, contentDescription = "Add file or photo", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DropdownMenu(
                                expanded = showAttachmentMenu,
                                onDismissRequest = { showAttachmentMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Pilih Foto dari Galeri") },
                                    onClick = {
                                        showAttachmentMenu = false
                                        imagePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Pilih File") },
                                    onClick = {
                                        showAttachmentMenu = false
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(Color.White),
                            decorationBox = { innerTextField ->
                                if (inputText.isEmpty()) {
                                    Text("Ask anything...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                innerTextField()
                            },
                            enabled = !uiState.isLoading
                        )
                        
                        if (inputText.isBlank() && selectedFileUri == null) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Mic",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp).clickable {
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(localContext, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        android.widget.Toast.makeText(localContext, "Voice input ready.", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        showMicPermissionDialog = true
                                    }
                                }
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = PrimaryNeon,
                                modifier = Modifier.size(24.dp).clickable {
                                    if ((inputText.isNotBlank() || selectedFileUri != null) && !uiState.isLoading) {
                                        viewModel.sendMessage(inputText, selectedFileUri?.toString())
                                        inputText = ""
                                        selectedFileUri = null
                                        selectedFileName = null
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } // Close the Column

        // Top Navigation Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Floating Menu Button
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(imageVector = Icons.Filled.Menu, contentDescription = "Menu", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        DropdownMenuItem(
                            text = { Text("New Chat", color = Color.White) },
                            onClick = {
                                showMenu = false
                                viewModel.createNewSession()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Chat History", color = Color.White) },
                            onClick = {
                                showMenu = false
                                showHistoryDialog = true
                            }
                        )
                        HorizontalDivider(color = OutlineDark)
                        DropdownMenuItem(
                            text = { Text("Settings", color = Color.White) },
                            onClick = {
                                showMenu = false
                                onNavigateToSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("AI Studio", color = Color.White) },
                            onClick = {
                                showMenu = false
                                onNavigateToStudio()
                            }
                        )
                    }
                }

                // Model Selector
                if (uiState.savedModelsList.isNotEmpty()) {
                    var showModelMenu by remember { mutableStateOf(false) }
                    
                    val displayModel = uiState.currentModel
                        .takeIf { it.isNotBlank() } ?: (uiState.savedModelsList.firstOrNull()?.modelName ?: "Select model")
                    
                    val shortModelName = displayModel.substringAfterLast("/")
                    val modelText = "$shortModelName ▼"

                    Spacer(modifier = Modifier.width(4.dp))
                    Box {
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                .clickable { showModelMenu = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = modelText,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = { showModelMenu = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            uiState.savedModelsList.forEach { modelCfg ->
                                DropdownMenuItem(
                                    text = { Text(modelCfg.modelName.substringAfterLast("/"), color = Color.White) },
                                    onClick = {
                                        showModelMenu = false
                                        viewModel.updateSelectedModel(modelCfg.modelName)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Mode Selector
            var showModeMenu by remember { mutableStateOf(false) }
            val currentMode = uiState.mode
            val modeText = when (currentMode) {
                ChatMode.THINK -> "Think ▼"
                ChatMode.THINK_DEEPLY -> "Think Deeply ▼"
                else -> "Normal ▼"
            }

            Box {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable { showModeMenu = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = modeText,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                DropdownMenu(
                    expanded = showModeMenu,
                    onDismissRequest = { showModeMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    DropdownMenuItem(
                        text = { Text("Normal", color = Color.White) },
                        onClick = {
                            showModeMenu = false
                            viewModel.setMode(ChatMode.NORMAL)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Think", color = Color.White) },
                        onClick = {
                            showModeMenu = false
                            viewModel.setMode(ChatMode.THINK)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Think Deeply", color = Color.White) },
                        onClick = {
                            showModeMenu = false
                            viewModel.setMode(ChatMode.THINK_DEEPLY)
                        }
                    )
                }
            }
        }
        }
    }
}

@Composable
fun MessageBubble(message: UiMessage) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val maxBubbleWidth = if (isUser) configuration.screenWidthDp.dp * 0.76f else configuration.screenWidthDp.dp * 0.94f

    Box(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .let {
                    if (isUser) {
                        it.background(Brush.horizontalGradient(listOf(PrimaryNeon, PrimaryBlue)), shape)
                    } else {
                        it
                            .background(MaterialTheme.colorScheme.surface, shape)
                            .border(1.dp, OutlineDark, shape)
                    }
                }
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            if (!isUser) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(PrimaryNeon, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        // Inner dot
                        Box(modifier = Modifier.size(6.dp).background(Color.White, CircleShape))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Ai Chat Assistant",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (message.imageUri != null) {
                AsyncImage(
                    model = message.imageUri,
                    contentDescription = "Attached image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(bottom = if (message.content.isNotBlank()) 8.dp else 0.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            if (message.content.isNotBlank()) {
                MessageContent(content = message.content, isUser = isUser)
            }
            if (!isUser) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    val clipboardManager = LocalClipboardManager.current
                    var buttonText by remember { mutableStateOf("Copy") }
                    val scope = rememberCoroutineScope()
                    
                    Text(
                        text = buttonText,
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                clipboardManager.setText(AnnotatedString(message.content))
                                buttonText = "Copied"
                                scope.launch {
                                    kotlinx.coroutines.delay(2000)
                                    buttonText = "Copy"
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MessageContent(content: String, isUser: Boolean) {
    if (isUser || !content.contains("```")) {
        Text(
            text = content,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        return
    }

    val blocks = content.split("```")
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column {
        blocks.forEachIndexed { index, block ->
            if (index % 2 == 0) {
                if (block.isNotBlank()) {
                    Text(
                        text = block.trim('\n'),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            } else {
                val lines = block.lines()
                val maybeLanguage = lines.firstOrNull()?.trim() ?: ""
                val hasLanguage = maybeLanguage.isNotBlank() && !maybeLanguage.contains(" ")
                val languageId = if (hasLanguage) maybeLanguage else "Code"
                val codeContent = if (hasLanguage) lines.drop(1).joinToString("\n") else block
                val trimmedContent = codeContent.trim()

                if (trimmedContent.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = languageId,
                                    color = Color.Gray,
                                    fontSize = 12.sp,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(trimmedContent))
                                        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy code",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = trimmedContent,
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TypingBubble(loadingText: String? = null) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dotCount by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dots"
    )
    val dots = ".".repeat(dotCount.toInt().coerceIn(0, 3))
    val text = if (loadingText != null) "$loadingText$dots" else "Ai Chat typing$dots"
    
    MessageBubble(message = UiMessage(role = "model", content = text))
}

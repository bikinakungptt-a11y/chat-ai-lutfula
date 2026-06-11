package com.example.ui.studio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.ui.theme.OutlineDark
import com.example.ui.theme.OutlineGlow
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.PrimaryNeon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(
    viewModel: StudioViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    var showPhotoPermissionDialog by remember { mutableStateOf(false) }
    var showCameraPermissionDialog by remember { mutableStateOf(false) }
    var tempCameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showVideoConfirmation by remember { mutableStateOf(false) }
    
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.selectImage(uri)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            viewModel.selectImage(tempCameraUri!!)
        }
    }
    
    val photoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms.values.all { it }
        if (granted) {
            galleryLauncher.launch(
                androidx.activity.result.PickVisualMediaRequest(
                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        } else {
            // handle denied
            android.widget.Toast.makeText(context, "Photo permission denied. You can enable it in App Settings.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // create temp file
            val file = java.io.File(context.cacheDir, "camera_capture.png")
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            tempCameraUri = uri
            cameraLauncher.launch(uri)
        } else {
            android.widget.Toast.makeText(context, "Camera permission denied. You can enable it in App Settings.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    if (showPhotoPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPhotoPermissionDialog = false },
            title = { Text("Permission Required", color = Color.White) },
            text = { Text("Photo access is needed to select images for editing or photo-to-video.", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    showPhotoPermissionDialog = false
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        photoPermissionLauncher.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        photoPermissionLauncher.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES))
                    } else {
                        photoPermissionLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
                    }
                }) { Text("OK", color = PrimaryNeon) }
            },
            dismissButton = {
                TextButton(onClick = { showPhotoPermissionDialog = false }) { Text("Cancel", color = Color.Gray) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }

    if (showCameraPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showCameraPermissionDialog = false },
            title = { Text("Permission Required", color = Color.White) },
            text = { Text("Camera access is needed to take a photo.", color = Color.White) },
            confirmButton = {
                TextButton(onClick = {
                    showCameraPermissionDialog = false
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }) { Text("OK", color = PrimaryNeon) }
            },
            dismissButton = {
                TextButton(onClick = { showCameraPermissionDialog = false }) { Text("Cancel", color = Color.Gray) }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }

    if (showVideoConfirmation) {
        AlertDialog(
            onDismissRequest = { showVideoConfirmation = false },
            title = { Text("Generate Video?") },
            text = { Text("This may use more API credit. Continue?") },
            confirmButton = {
                TextButton(onClick = {
                    showVideoConfirmation = false
                    viewModel.generate()
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showVideoConfirmation = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Studio", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp)
                .imePadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                listOf(
                    Pair("Create Photo", Icons.Filled.Image),
                    Pair("Edit Photo", Icons.Filled.PhotoCamera),
                    Pair("Photo to Video", Icons.Filled.Movie)
                ).forEachIndexed { index, (label, icon) ->
                    val isSelected = uiState.selectedTab == index
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (isSelected) Brush.horizontalGradient(listOf(PrimaryBlue, PrimaryNeon))
                                else androidx.compose.ui.graphics.SolidColor(Color.Transparent)
                            )
                            .clickable { viewModel.selectTab(index) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = label,
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Result Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, OutlineDark, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isGenerating) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = PrimaryNeon)
                        Spacer(modifier = Modifier.height(16.dp))
                        val loadingText = uiState.videoStatus ?: when (uiState.selectedTab) {
                            0 -> "Generating photo..."
                            1 -> "Editing photo..."
                            2 -> "Generating video..."
                            else -> "Generating content..."
                        }
                        Text(loadingText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (!uiState.error.isNullOrEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                        Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(uiState.error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                } else if (uiState.generatedMediaUrl != null && uiState.selectedTab != 2) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = uiState.generatedMediaUrl,
                            contentDescription = "Generated Media",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        IconButton(
                            onClick = {
                                viewModel.saveMedia(context, uiState.generatedMediaUrl!!, false)
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = "Save Image", tint = PrimaryNeon)
                        }
                    }
                } else if (uiState.selectedImageUri != null && uiState.generatedMediaUrl == null && uiState.generatedVideoUrl == null && (uiState.selectedTab == 1 || uiState.selectedTab == 2)) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = uiState.selectedImageUri,
                            contentDescription = "Selected Media",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else if (uiState.generatedVideoUrl != null && uiState.selectedTab == 2) {
                    // Simulating a video player placeholder
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Movie, contentDescription = null, modifier = Modifier.size(64.dp), tint = PrimaryNeon)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Video generated successfully.\n(Preview unavailable in current implementation)", color = Color.White, textAlign = TextAlign.Center)
                            Text(uiState.generatedVideoUrl!!, color = PrimaryBlue, fontSize = 10.sp, textAlign = TextAlign.Center)
                        }
                        IconButton(
                            onClick = {
                                viewModel.saveMedia(context, uiState.generatedVideoUrl!!, true)
                            },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = "Save Video", tint = PrimaryNeon)
                        }
                    }
                } else {
                    Text("Your creation will appear here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Edit / Video Tools
            if (uiState.selectedTab == 1 || uiState.selectedTab == 2) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            galleryLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (uiState.selectedImageUri != null) "Selected" else "Upload", fontSize = 12.sp)
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                val file = java.io.File(context.cacheDir, "camera_capture.png")
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                tempCameraUri = uri
                                cameraLauncher.launch(uri)
                            } else {
                                showCameraPermissionDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Camera", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Camera", fontSize = 12.sp)
                    }

                    if (uiState.selectedTab == 2 && uiState.generatedMediaUrl != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { 
                                viewModel.selectImage(null) // Clears selection to favor generated
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (uiState.selectedImageUri == null) PrimaryNeon else OutlineDark)
                        ) {
                            Text("Generated", fontSize = 12.sp, color = if (uiState.selectedImageUri == null) PrimaryNeon else Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else if (uiState.selectedTab == 0 && uiState.generatedMediaUrl != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(
                        onClick = { 
                            viewModel.selectTab(2) // Jump to video tab
                        },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Use this photo for video", color = PrimaryNeon)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Input Area
            OutlinedTextField(
                value = uiState.prompt,
                onValueChange = { viewModel.updatePrompt(it) },
                placeholder = { 
                    val hint = when(uiState.selectedTab) {
                        0 -> "Describe the photo you want to create..."
                        1 -> "Describe how you want to edit the photo..."
                        else -> "Describe the video you want to generate..."
                    }
                    Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
                    .heightIn(min = 56.dp, max = 120.dp),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = OutlineDark,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                trailingIcon = {
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .size(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Brush.horizontalGradient(listOf(PrimaryBlue, PrimaryNeon)))
                            .clickable(enabled = !uiState.isGenerating) {
                                if (uiState.selectedTab == 2) {
                                    showVideoConfirmation = true
                                } else {
                                    viewModel.generate()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Generate",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }
    }
}

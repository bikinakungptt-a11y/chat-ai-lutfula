package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.OutlineDark
import com.example.ui.theme.OutlineGlow
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.PrimaryNeon
import com.example.ui.theme.SuccessGreen

import androidx.compose.ui.platform.LocalContext
import android.app.Activity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val presets = listOf(
        ProviderData("OpenAI", "api.openai.com", Icons.Filled.AutoAwesome),
        ProviderData("OpenRouter", "openrouter.ai", Icons.Filled.SettingsSuggest),
        ProviderData("xAI", "api.x.ai", Icons.Filled.Language),
        ProviderData("Custom", "Your own endpoint", Icons.Filled.Code)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("") },
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
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        brush = Brush.radialGradient(colors = listOf(PrimaryBlue.copy(alpha = 0.3f), Color.Transparent))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Filled.SettingsSuggest, contentDescription = null, tint = PrimaryNeon, modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("AI Control Center", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Connect your own provider", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            
            Spacer(modifier = Modifier.height(32.dp))

            // Providers Section
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Provider", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                presets.forEach { provider ->
                    val isSelected = uiState.textProvider == provider.name
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                viewModel.updateTextProvider(provider.name)
                                viewModel.applyPreset(provider.name)
                            }
                            .border(
                                width = if (isSelected) 1.dp else 1.dp,
                                color = if (isSelected) OutlineGlow else OutlineDark,
                                shape = RoundedCornerShape(16.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(imageVector = provider.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(provider.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(provider.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            if (isSelected) {
                                Box(
                                    modifier = Modifier.size(24.dp).background(PrimaryBlue, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Microsoft Account Section
            Text("Microsoft Connection", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(12.dp))
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, OutlineDark, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val account = uiState.microsoftAccount
                    if (account != null) {
                        Text("Connected Account", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(account.username ?: "Unknown", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.signOutMicrosoft() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                        ) {
                            Text("Disconnect", color = Color.White)
                        }
                    } else {
                        Text("No Microsoft Account Connected", fontSize = 14.sp, color = Color.LightGray)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { 
                                val activity = context as? Activity
                                if (activity != null) {
                                    viewModel.signInMicrosoft(activity)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                        ) {
                            Text("Connect Outlook / Hotmail", color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Text & Search Settings", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(12.dp))

            // Input Fields
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = { viewModel.updateBaseUrl(it) },
                label = { Text("API Base URL") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = OutlineDark,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.textPath,
                onValueChange = { viewModel.updateTextPath(it) },
                label = { Text("API Path (e.g. /chat/completions)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = OutlineDark,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = { viewModel.updateApiKey(it) },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(imageVector = image, contentDescription = "Toggle password", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = OutlineDark,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.modelName,
                onValueChange = { viewModel.updateModelName(it) },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = OutlineDark,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.firecrawlApiKey,
                onValueChange = { viewModel.updateFirecrawlApiKey(it) },
                label = { Text("Firecrawl API Key (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = OutlineDark,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
            
            // Buttons
            if (uiState.validationError != null) {
                Text(
                    text = uiState.validationError ?: "",
                    color = Color.Red,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 8.dp).align(Alignment.Start)
                )
            }

            Button(
                onClick = { viewModel.save() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Brush.horizontalGradient(listOf(PrimaryBlue, PrimaryNeon)), RoundedCornerShape(28.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues()
            ) {
                Text("Save Text Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            OutlinedButton(
                onClick = { viewModel.testConnection() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue),
                shape = RoundedCornerShape(28.dp),
                enabled = !uiState.isTesting
            ) {
                if (uiState.isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = PrimaryBlue, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Testing...", fontSize = 16.sp, color = PrimaryBlue)
                } else {
                    Text("Test Connection", fontSize = 16.sp)
                }
            }

            if (uiState.testError != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Red.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.Red.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Connection Failed", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(uiState.testError ?: "", color = Color.Red.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }

                LaunchedEffect(uiState.testError) {
                    if (uiState.testError != null) {
                        kotlinx.coroutines.delay(5000)
                        viewModel.clearTestResult()
                    }
                }
            }

            if (uiState.testResult != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SuccessGreen.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .border(1.dp, SuccessGreen.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null, tint = SuccessGreen)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Connected", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(uiState.testResult ?: "", color = SuccessGreen.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }

                LaunchedEffect(uiState.testResult) {
                    if (uiState.testResult != null) {
                        kotlinx.coroutines.delay(5000)
                        viewModel.clearTestResult()
                    }
                }
            }

            if (uiState.isSaved) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SuccessGreen.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .border(1.dp, SuccessGreen.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Filled.CheckCircle, contentDescription = null, tint = SuccessGreen)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Saved successfully", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = SuccessGreen)
                }

                LaunchedEffect(uiState.isSaved) {
                    if (uiState.isSaved) {
                        kotlinx.coroutines.delay(3000)
                        viewModel.resetSaveState()
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(48.dp))

            // Media Generation Settings
            Text("Media Generation Settings", fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(8.dp))
            
            // Economy Mode Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Economy API Mode", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    Text("Uses lower duration, 1 image limit, and compression to save API credits.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = uiState.economyMode,
                    onCheckedChange = { viewModel.updateEconomyMode(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = PrimaryNeon, checkedTrackColor = PrimaryNeon.copy(alpha=0.5f))
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Text("Configure Create Photo, Edit Photo, and Photo to Video APIs", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(24.dp))

            // A. Create Photo Settings
            MediaSettingsCard("Create Photo Settings", 
                apiKey = uiState.createPhotoApiKey, onApiKeyChange = { viewModel.updateCreatePhotoApiKey(it) },
                baseUrl = uiState.createPhotoBaseUrl, onBaseUrlChange = { viewModel.updateCreatePhotoBaseUrl(it) },
                endpoint = uiState.createPhotoEndpoint, onEndpointChange = { viewModel.updateCreatePhotoEndpoint(it) },
                model = uiState.createPhotoModel, onModelChange = { viewModel.updateCreatePhotoModel(it) },
                format = uiState.createPhotoFormat, onFormatChange = { viewModel.updateCreatePhotoFormat(it) },
                onSave = { viewModel.saveCreatePhotoSettings() },
                isSaved = uiState.isCreatePhotoSaved,
                onTestConnection = { viewModel.testCreatePhotoConnection() },
                isTesting = uiState.isCreatePhotoTesting,
                testResult = uiState.createPhotoTestResult,
                testError = uiState.createPhotoTestError,
                passwordVisible = passwordVisible, onPasswordVisibleChange = { passwordVisible = it }
            )
            Spacer(modifier = Modifier.height(24.dp))

            // B. Edit Photo Settings
            MediaSettingsCard("Edit Photo Settings", 
                apiKey = uiState.editPhotoApiKey, onApiKeyChange = { viewModel.updateEditPhotoApiKey(it) },
                baseUrl = uiState.editPhotoBaseUrl, onBaseUrlChange = { viewModel.updateEditPhotoBaseUrl(it) },
                endpoint = uiState.editPhotoEndpoint, onEndpointChange = { viewModel.updateEditPhotoEndpoint(it) },
                model = uiState.editPhotoModel, onModelChange = { viewModel.updateEditPhotoModel(it) },
                format = uiState.editPhotoFormat, onFormatChange = { viewModel.updateEditPhotoFormat(it) },
                imageFormat = uiState.editPhotoImageFormat, onImageFormatChange = { viewModel.updateEditPhotoImageFormat(it) },
                onSave = { viewModel.saveEditPhotoSettings() },
                isSaved = uiState.isEditPhotoSaved,
                onTestConnection = { viewModel.testEditPhotoConnection() },
                isTesting = uiState.isEditPhotoTesting,
                testResult = uiState.editPhotoTestResult,
                testError = uiState.editPhotoTestError,
                passwordVisible = passwordVisible, onPasswordVisibleChange = { passwordVisible = it }
            )
            Spacer(modifier = Modifier.height(24.dp))

            // C. Photo to Video Settings
            MediaSettingsCard("Photo to Video Settings", 
                apiKey = uiState.photoVideoApiKey, onApiKeyChange = { viewModel.updatePhotoVideoApiKey(it) },
                baseUrl = uiState.photoVideoBaseUrl, onBaseUrlChange = { viewModel.updatePhotoVideoBaseUrl(it) },
                endpoint = uiState.photoVideoCreateEndpoint, onEndpointChange = { viewModel.updatePhotoVideoCreateEndpoint(it) },
                statusEndpoint = uiState.photoVideoStatusEndpoint, onStatusEndpointChange = { viewModel.updatePhotoVideoStatusEndpoint(it) },
                resultEndpoint = uiState.photoVideoResultEndpoint, onResultEndpointChange = { viewModel.updatePhotoVideoResultEndpoint(it) },
                model = uiState.photoVideoModel, onModelChange = { viewModel.updatePhotoVideoModel(it) },
                format = uiState.photoVideoFormat, onFormatChange = { viewModel.updatePhotoVideoFormat(it) },
                imageFormat = uiState.photoVideoImageFormat, onImageFormatChange = { viewModel.updatePhotoVideoImageFormat(it) },
                duration = uiState.photoVideoDuration, onDurationChange = { viewModel.updatePhotoVideoDuration(it) },
                onSave = { viewModel.savePhotoVideoSettings() },
                isSaved = uiState.isPhotoVideoSaved,
                onTestConnection = { viewModel.testPhotoToVideoConnection() },
                isTesting = uiState.isPhotoVideoTesting,
                testResult = uiState.photoVideoTestResult,
                testError = uiState.photoVideoTestError,
                passwordVisible = passwordVisible, onPasswordVisibleChange = { passwordVisible = it }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

data class ProviderData(val name: String, val subtitle: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSettingsCard(
    title: String,
    apiKey: String, onApiKeyChange: (String) -> Unit,
    baseUrl: String, onBaseUrlChange: (String) -> Unit,
    endpoint: String, onEndpointChange: (String) -> Unit,
    statusEndpoint: String? = null, onStatusEndpointChange: ((String) -> Unit)? = null,
    resultEndpoint: String? = null, onResultEndpointChange: ((String) -> Unit)? = null,
    model: String, onModelChange: (String) -> Unit,
    format: String, onFormatChange: (String) -> Unit,
    imageFormat: String? = null, onImageFormatChange: ((String) -> Unit)? = null,
    duration: String? = null, onDurationChange: ((String) -> Unit)? = null,
    onSave: () -> Unit,
    isSaved: Boolean,
    onTestConnection: () -> Unit,
    isTesting: Boolean,
    testResult: String?,
    testError: String?,
    passwordVisible: Boolean, onPasswordVisibleChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, OutlineDark, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            // BaseModel
            OutlinedTextField(
                value = baseUrl, onValueChange = onBaseUrlChange, label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // API Key
            OutlinedTextField(
                value = apiKey, onValueChange = onApiKeyChange, label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    val image = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff
                    IconButton(onClick = { onPasswordVisibleChange(!passwordVisible) }) {
                        Icon(imageVector = image, contentDescription = "Toggle password", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Endpoints
            OutlinedTextField(
                value = endpoint, onValueChange = onEndpointChange, label = { Text("Endpoint Path") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            if (statusEndpoint != null && onStatusEndpointChange != null) {
                OutlinedTextField(
                    value = statusEndpoint, onValueChange = onStatusEndpointChange, label = { Text("Status Endpoint Path") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            if (resultEndpoint != null && onResultEndpointChange != null) {
                OutlinedTextField(
                    value = resultEndpoint, onValueChange = onResultEndpointChange, label = { Text("Result Endpoint Path") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Model
            OutlinedTextField(
                value = model, onValueChange = onModelChange, label = { Text("Model Name") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Format Options
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Request Format:", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Row {
                    val formats = listOf("JSON", "multipart")
                    formats.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onFormatChange(option) }) {
                            RadioButton(selected = format == option, onClick = { onFormatChange(option) })
                            Text(text = option, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Image Format (If present)
            if (imageFormat != null && onImageFormatChange != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Image Input Format:", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
                Row(modifier = Modifier.padding(start = 16.dp)) {
                    val formats = listOf("base64", "multipart", "url")
                    formats.forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onImageFormatChange(option) }.padding(end = 8.dp)) {
                            RadioButton(selected = imageFormat == option, onClick = { onImageFormatChange(option) }, modifier = Modifier.size(24.dp))
                            Text(text = option, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Duration
            if (duration != null && onDurationChange != null) {
                OutlinedTextField(
                    value = duration, onValueChange = onDurationChange, label = { Text("Duration (seconds)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onSave, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                Text(if (isSaved) "Saved!" else "Save", color = Color.White)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedButton(
                onClick = onTestConnection,
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
                enabled = !isTesting
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Testing...", color = Color.White)
                } else {
                    Text("Test Connection", color = Color.White)
                }
            }

            if (testError != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Red.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.Red.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Connection Failed", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(testError, color = Color.Red.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }
            } else if (testResult != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SuccessGreen.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                        .border(1.dp, SuccessGreen.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Connection Successful", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(testResult, color = SuccessGreen.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
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
import androidx.compose.material.icons.filled.Close
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
        ProviderData("bluesminds", "BlueSminds", "api.bluesminds.com", Icons.Filled.AutoAwesome),
        ProviderData("openrouter", "OpenRouter", "openrouter.ai", Icons.Filled.SettingsSuggest),
        ProviderData("xai", "xAI", "api.x.ai", Icons.Filled.Language),
        ProviderData("custom", "Custom", "Your own endpoint", Icons.Filled.Code)
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(brush = Brush.radialGradient(colors = listOf(PrimaryBlue.copy(alpha = 0.3f), Color.Transparent))),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Filled.SettingsSuggest, contentDescription = null, tint = PrimaryNeon, modifier = Modifier.size(32.dp))
            }
            Text("AI Control Center", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            
            // Economy Mode Toggle
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Economy API Mode", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    Text("Uses lower duration, 1 image limit, and compression to save API credits.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = uiState.economyMode,
                    onCheckedChange = { viewModel.updateEconomyMode(it) },
                    colors = SwitchDefaults.colors(checkedThumbColor = PrimaryNeon, checkedTrackColor = PrimaryNeon.copy(alpha=0.5f))
                )
            }

            // 1. Chat API
            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, OutlineDark, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("1. Chat API", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)

                    Text("Provider", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    presets.forEach { provider ->
                        val isSelected = uiState.textProvider == provider.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    viewModel.updateTextProvider(provider.id)
                                    viewModel.applyPreset(provider.id)
                                }
                                .border(if (isSelected) 1.dp else 1.dp, if (isSelected) OutlineGlow else OutlineDark, RoundedCornerShape(12.dp)),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = provider.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(provider.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(provider.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                                if (isSelected) {
                                    Box(modifier = Modifier.size(24.dp).background(PrimaryBlue, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = uiState.baseUrl, onValueChange = { viewModel.updateBaseUrl(it) }, label = { Text("API Base URL") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = uiState.textPath, onValueChange = { viewModel.updateTextPath(it) }, label = { Text("API Path (e.g. /chat/completions)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = uiState.apiKey, onValueChange = { viewModel.updateApiKey(it) }, label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = "Toggle password", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = uiState.modelName, onValueChange = { viewModel.updateModelName(it) }, label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { viewModel.updateSupportsVision(!uiState.supportsVision) }) {
                        androidx.compose.material3.Checkbox(checked = uiState.supportsVision, onCheckedChange = { viewModel.updateSupportsVision(it) }, colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = PrimaryNeon, uncheckedColor = OutlineDark))
                        Text("Supports Vision / Bisa membaca gambar", color = Color.White, fontSize = 14.sp)
                    }

                    if (uiState.savedModelsList.isNotEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Saved Models:", color = Color.Gray, fontSize = 12.sp)
                            uiState.savedModelsList.forEach { savedModel ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { viewModel.updateModelName(savedModel.modelName) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(savedModel.modelName, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                    if (savedModel.supportsVision) Icon(Icons.Filled.Visibility, contentDescription = "Vision", tint = PrimaryNeon, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                                    IconButton(onClick = { viewModel.removeSavedModel(savedModel.modelName) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                                }
                            }
                        }
                    }

                    if (uiState.validationError != null) Text(text = uiState.validationError ?: "", color = Color.Red, fontSize = 14.sp)
                    if (uiState.isSaved) Text("Saved successfully!", color = SuccessGreen, fontSize = 14.sp)

                    Button(
                        onClick = { viewModel.save() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) { Text("Save Text Settings", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                    
                    OutlinedButton(
                        onClick = { viewModel.testConnection() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue),
                        enabled = !uiState.isTextTesting && uiState.textTestResult == null && uiState.textTestError == null
                    ) {
                        if (uiState.isTextTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PrimaryBlue, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing...", fontSize = 14.sp, color = PrimaryBlue)
                        } else Text("Test Connection", fontSize = 14.sp)
                    }

                    if (uiState.textTestError != null) Text("Error: ${uiState.textTestError}", color = Color.Red, fontSize = 12.sp)
                    if (uiState.textTestResult != null) Text("Success: ${uiState.textTestResult}", color = SuccessGreen, fontSize = 12.sp)
                }
            }

            // 3. Firecrawl Search
            Card(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, OutlineDark, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("3. Firecrawl Search", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    
                    OutlinedTextField(
                        value = uiState.firecrawlApiKey, onValueChange = { viewModel.updateFirecrawlApiKey(it) }, label = { Text("Firecrawl API Key") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = "Toggle password", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                        colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            if (uiState.firecrawlApiKey.isNotBlank()) {
                                viewModel.saveFirecrawlKey()
                                android.widget.Toast.makeText(context, "Firecrawl API key saved", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) { Text("Save Firecrawl", color = Color.White) }

                    if (uiState.firecrawlApiKey.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                viewModel.removeFirecrawlKey()
                                android.widget.Toast.makeText(context, "Firecrawl API key removed", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                        ) { Text("Remove", color = Color.Red) }
                    }
                }
            }

            // 4. Create Photo API
            MediaSettingsCard("4. Create Photo API", 
                apiKey = uiState.createPhotoApiKey, onApiKeyChange = { viewModel.updateCreatePhotoApiKey(it) },
                baseUrl = uiState.createPhotoBaseUrl, onBaseUrlChange = { viewModel.updateCreatePhotoBaseUrl(it) },
                endpoint = uiState.createPhotoEndpoint, onEndpointChange = { viewModel.updateCreatePhotoEndpoint(it) },
                model = uiState.createPhotoModel, onModelChange = { viewModel.updateCreatePhotoModel(it) },
                format = uiState.createPhotoFormat, onFormatChange = { viewModel.updateCreatePhotoFormat(it) },
                onSave = { viewModel.saveCreatePhotoSettings() }, isSaved = uiState.isCreatePhotoSaved,
                onTestConnection = { viewModel.testCreatePhotoConnection() }, isTesting = uiState.isCreatePhotoTesting,
                testResult = uiState.createPhotoTestResult, testError = uiState.createPhotoTestError,
                passwordVisible = passwordVisible, onPasswordVisibleChange = { passwordVisible = it }
            )

            // 5. Edit Photo API
            MediaSettingsCard("5. Edit Photo API", 
                apiKey = uiState.editPhotoApiKey, onApiKeyChange = { viewModel.updateEditPhotoApiKey(it) },
                baseUrl = uiState.editPhotoBaseUrl, onBaseUrlChange = { viewModel.updateEditPhotoBaseUrl(it) },
                endpoint = uiState.editPhotoEndpoint, onEndpointChange = { viewModel.updateEditPhotoEndpoint(it) },
                model = uiState.editPhotoModel, onModelChange = { viewModel.updateEditPhotoModel(it) },
                format = uiState.editPhotoFormat, onFormatChange = { viewModel.updateEditPhotoFormat(it) },
                imageFormat = uiState.editPhotoImageFormat, onImageFormatChange = { viewModel.updateEditPhotoImageFormat(it) },
                onSave = { viewModel.saveEditPhotoSettings() }, isSaved = uiState.isEditPhotoSaved,
                onTestConnection = { viewModel.testEditPhotoConnection() }, isTesting = uiState.isEditPhotoTesting,
                testResult = uiState.editPhotoTestResult, testError = uiState.editPhotoTestError,
                passwordVisible = passwordVisible, onPasswordVisibleChange = { passwordVisible = it }
            )

            // 6. Photo to Video / AI Video API
            MediaSettingsCard("6. Photo to Video / AI Video API", 
                apiKey = uiState.photoVideoApiKey, onApiKeyChange = { viewModel.updatePhotoVideoApiKey(it) },
                baseUrl = uiState.photoVideoBaseUrl, onBaseUrlChange = { viewModel.updatePhotoVideoBaseUrl(it) },
                endpoint = uiState.photoVideoCreateEndpoint, onEndpointChange = { viewModel.updatePhotoVideoCreateEndpoint(it) },
                statusEndpoint = uiState.photoVideoStatusEndpoint, onStatusEndpointChange = { viewModel.updatePhotoVideoStatusEndpoint(it) },
                resultEndpoint = uiState.photoVideoResultEndpoint, onResultEndpointChange = { viewModel.updatePhotoVideoResultEndpoint(it) },
                model = uiState.photoVideoModel, onModelChange = { viewModel.updatePhotoVideoModel(it) },
                format = uiState.photoVideoFormat, onFormatChange = { viewModel.updatePhotoVideoFormat(it) },
                imageFormat = uiState.photoVideoImageFormat, onImageFormatChange = { viewModel.updatePhotoVideoImageFormat(it) },
                duration = uiState.photoVideoDuration, onDurationChange = { viewModel.updatePhotoVideoDuration(it) },
                onSave = { viewModel.savePhotoVideoSettings() }, isSaved = uiState.isPhotoVideoSaved,
                onTestConnection = { viewModel.testPhotoToVideoConnection() }, isTesting = uiState.isPhotoVideoTesting,
                testResult = uiState.photoVideoTestResult, testError = uiState.photoVideoTestError,
                passwordVisible = passwordVisible, onPasswordVisibleChange = { passwordVisible = it }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

data class ProviderData(val id: String, val name: String, val subtitle: String, val icon: ImageVector)

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
    onSave: () -> Unit, isSaved: Boolean,
    onTestConnection: () -> Unit, isTesting: Boolean,
    testResult: String?, testError: String?,
    passwordVisible: Boolean, onPasswordVisibleChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, OutlineDark, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)

            OutlinedTextField(
                value = baseUrl, onValueChange = onBaseUrlChange, label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
            )

            OutlinedTextField(
                value = endpoint, onValueChange = onEndpointChange, label = { Text(if (statusEndpoint != null) "Create Endpoint Path" else "Endpoint Path") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
            )

            if (statusEndpoint != null && onStatusEndpointChange != null) {
                OutlinedTextField(
                    value = statusEndpoint, onValueChange = onStatusEndpointChange, label = { Text("Status Endpoint Path") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            if (resultEndpoint != null && onResultEndpointChange != null) {
                OutlinedTextField(
                    value = resultEndpoint, onValueChange = onResultEndpointChange, label = { Text("Result Endpoint Path") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            OutlinedTextField(
                value = apiKey, onValueChange = onApiKeyChange, label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { onPasswordVisibleChange(!passwordVisible) }) { Icon(imageVector = if (passwordVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = "Toggle password", tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
                colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
            )

            OutlinedTextField(
                value = model, onValueChange = onModelChange, label = { Text("Model Name") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Format:", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("JSON", "multipart").forEach { option ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onFormatChange(option) }) {
                            RadioButton(selected = format == option, onClick = { onFormatChange(option) }, modifier = Modifier.size(24.dp))
                            Text(text = option, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            if (imageFormat != null && onImageFormatChange != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Image Format:", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("base64", "multipart").forEach { option ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onImageFormatChange(option) }) {
                                RadioButton(selected = imageFormat == option, onClick = { onImageFormatChange(option) }, modifier = Modifier.size(24.dp))
                                Text(text = option, color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            if (duration != null && onDurationChange != null) {
                OutlinedTextField(
                    value = duration, onValueChange = onDurationChange, label = { Text("Duration (seconds)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(cursorColor = Color.White, focusedBorderColor = PrimaryNeon, unfocusedBorderColor = OutlineDark, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                Text(if (isSaved) "Saved!" else "Save", color = Color.White)
            }

            OutlinedButton(
                onClick = onTestConnection,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
                enabled = !isTesting
            ) {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing...", color = Color.White)
                } else Text("Test Connection", color = Color.White)
            }

            if (testError != null) Text("Error: $testError", color = Color.Red, fontSize = 12.sp)
            if (testResult != null) Text("Success: $testResult", color = SuccessGreen, fontSize = 12.sp)
        }
    }
}

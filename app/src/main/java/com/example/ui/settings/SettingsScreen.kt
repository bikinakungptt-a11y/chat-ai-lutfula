package com.example.ui.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BackgroundDark = Color(0xFF0B0F14)
private val SurfaceDark = Color(0xFF111823)
private val OutlineDark = Color(0xFF253244)
private val PrimaryNeon = Color(0xFF00C2FF)
private val SuccessGreen = Color(0xFF2E7D32)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceDark)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard(title = "Chat API", icon = Icons.Default.Settings) {
                OutlinedTextField(
                    value = uiState.textProvider,
                    onValueChange = viewModel::updateTextProvider,
                    label = { Text("Provider") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = uiState.baseUrl,
                    onValueChange = viewModel::updateBaseUrl,
                    label = { Text("Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = uiState.textPath,
                    onValueChange = viewModel::updateTextPath,
                    label = { Text("Path") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = viewModel::updateApiKey,
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = uiState.modelName,
                    onValueChange = viewModel::updateModelName,
                    label = { Text("Model") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::save) { Text("Save") }
                    OutlinedButton(onClick = viewModel::testConnection) { Text("Test Connection") }
                }
                uiState.validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                uiState.testError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                uiState.testResult?.let { Text(it, color = SuccessGreen) }
            }

            SettingsCard(title = "Microsoft Outlook", icon = Icons.Default.Email) {
                OutlinedTextField(
                    value = uiState.microsoftClientId,
                    onValueChange = viewModel::updateMicrosoftClientId,
                    label = { Text("Microsoft Client ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = uiState.microsoftTenant,
                    onValueChange = viewModel::updateMicrosoftTenant,
                    label = { Text("Tenant, default: common") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    text = "Supported account types: personal Microsoft accounts and organizational accounts. Tenant common tetap dipakai.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = "Redirect URI untuk Azure harus mengikuti build terbaru: msauth://com.aistudio.aichatmobile.xmqpr/<encoded-signature-hash>. Jangan pakai hash lama jika signing key berubah.",
                    fontSize = 12.sp,
                    color = PrimaryNeon,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(OutlineDark.copy(alpha = 0.3f), RoundedCornerShape(4.dp)).padding(4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.saveMicrosoftConfig(context) }) { Text("Save Microsoft") }
                    OutlinedButton(
                        enabled = activity != null && !uiState.isTesting,
                        onClick = { activity?.let { viewModel.signInMicrosoft(it, context) } }
                    ) { Text(if (uiState.isTesting) "Connecting..." else "Connect Outlook") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = viewModel::testMicrosoftProfile) { Text("Test Profile") }
                    OutlinedButton(onClick = viewModel::testMicrosoftInbox) { Text("Check Inbox") }
                }
                uiState.microsoftAccount?.let { account ->
                    Text("Connected: ${account.username ?: account.id}", color = SuccessGreen)
                    OutlinedButton(onClick = { viewModel.signOutMicrosoft(context) }) { Text("Disconnect Outlook") }
                } ?: Text("No Microsoft account connected", color = Color.Gray)
            }

            SettingsCard(title = "Firecrawl Search", icon = Icons.Default.Cloud) {
                OutlinedTextField(
                    value = uiState.firecrawlApiKey,
                    onValueChange = viewModel::updateFirecrawlApiKey,
                    label = { Text("Firecrawl API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::saveFirecrawlKey) { Text("Save Firecrawl") }
                    OutlinedButton(onClick = viewModel::removeFirecrawlKey) { Text("Remove") }
                }
            }

            SettingsCard(title = "Create Photo API", icon = Icons.Default.Cloud) {
                OutlinedTextField(value = uiState.createPhotoProvider, onValueChange = viewModel::updateCreatePhotoProvider, label = { Text("Provider") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.createPhotoBaseUrl, onValueChange = viewModel::updateCreatePhotoBaseUrl, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.createPhotoEndpoint, onValueChange = viewModel::updateCreatePhotoEndpoint, label = { Text("Endpoint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.createPhotoApiKey, onValueChange = viewModel::updateCreatePhotoApiKey, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.createPhotoModel, onValueChange = viewModel::updateCreatePhotoModel, label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::saveCreatePhotoSettings) { Text("Save") }
                    OutlinedButton(onClick = viewModel::testCreatePhotoConnection) { Text("Test") }
                }
                uiState.createPhotoTestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                uiState.createPhotoTestResult?.let { Text(it, color = SuccessGreen) }
            }

            SettingsCard(title = "Edit Photo API", icon = Icons.Default.Cloud) {
                OutlinedTextField(value = uiState.editPhotoProvider, onValueChange = viewModel::updateEditPhotoProvider, label = { Text("Provider") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.editPhotoBaseUrl, onValueChange = viewModel::updateEditPhotoBaseUrl, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.editPhotoEndpoint, onValueChange = viewModel::updateEditPhotoEndpoint, label = { Text("Endpoint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.editPhotoApiKey, onValueChange = viewModel::updateEditPhotoApiKey, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.editPhotoModel, onValueChange = viewModel::updateEditPhotoModel, label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::saveEditPhotoSettings) { Text("Save") }
                    OutlinedButton(onClick = viewModel::testEditPhotoConnection) { Text("Test") }
                }
                uiState.editPhotoTestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                uiState.editPhotoTestResult?.let { Text(it, color = SuccessGreen) }
            }

            SettingsCard(title = "Photo to Video API", icon = Icons.Default.Cloud) {
                OutlinedTextField(value = uiState.photoVideoProvider, onValueChange = viewModel::updatePhotoVideoProvider, label = { Text("Provider") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.photoVideoBaseUrl, onValueChange = viewModel::updatePhotoVideoBaseUrl, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.photoVideoCreateEndpoint, onValueChange = viewModel::updatePhotoVideoCreateEndpoint, label = { Text("Create Endpoint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.photoVideoStatusEndpoint, onValueChange = viewModel::updatePhotoVideoStatusEndpoint, label = { Text("Status Endpoint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.photoVideoResultEndpoint, onValueChange = viewModel::updatePhotoVideoResultEndpoint, label = { Text("Result Endpoint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.photoVideoApiKey, onValueChange = viewModel::updatePhotoVideoApiKey, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.photoVideoModel, onValueChange = viewModel::updatePhotoVideoModel, label = { Text("Model") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = uiState.photoVideoDuration, onValueChange = viewModel::updatePhotoVideoDuration, label = { Text("Duration") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::savePhotoVideoSettings) { Text("Save") }
                    OutlinedButton(onClick = viewModel::testPhotoVideoConnection) { Text("Test") }
                }
                uiState.photoVideoTestError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                uiState.photoVideoTestResult?.let { Text(it, color = SuccessGreen) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = PrimaryNeon)
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}
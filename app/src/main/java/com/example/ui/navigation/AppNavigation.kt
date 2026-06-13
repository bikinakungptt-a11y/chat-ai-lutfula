package com.example.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import com.example.di.AppContainer
import com.example.ui.WelcomeScreen
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel
import com.example.data.GraphEmail

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Welcome : Screen("welcome", "Welcome", Icons.AutoMirrored.Filled.Chat)
    object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    object Studio : Screen("studio", "AI Studio", Icons.Filled.Movie)
    object Outlook : Screen("outlook", "Outlook", Icons.Filled.Email)
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    val settingsRepository = AppContainer.getSettingsRepository(context)
    val chatRepository = AppContainer.getChatRepository(context)

    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.Factory(
            context.applicationContext,
            settingsRepository,
            chatRepository,
            AppContainer.getMemoryRepository(context),
            AppContainer.getLocalStorage(context),
            AppContainer.getMicrosoftAuthService(context),
            AppContainer.getMicrosoftGraphRepository(context),
            AppContainer.okHttpClient,
            AppContainer.moshi
        )
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            settingsRepository,
            AppContainer.getMicrosoftAuthService(context),
            AppContainer.getMicrosoftGraphRepository(context),
            AppContainer.okHttpClient,
            AppContainer.moshi,
            AppContainer.getLocalStorage(context)
        )
    )

    Scaffold { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Welcome.route,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)) },
            exitTransition = { slideOutHorizontally(targetOffsetX = { -it / 2 }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)) },
            popEnterTransition = { slideInHorizontally(initialOffsetX = { -it / 2 }, animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)) },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(300)) }
        ) {
            composable(Screen.Welcome.route) {
                WelcomeScreen(
                    onGetStarted = {
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(Screen.Welcome.route) { inclusive = true }
                        }
                    }
                )
            }
            composable(Screen.Chat.route) {
                ChatScreen(
                    viewModel = chatViewModel,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToStudio = { navController.navigate(Screen.Studio.route) },
                    onNavigateToOutlook = { navController.navigate(Screen.Outlook.route) }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Studio.route) {
                val studioViewModel: com.example.ui.studio.StudioViewModel = viewModel(
                    factory = com.example.ui.studio.StudioViewModel.Factory(
                        settingsRepository,
                        AppContainer.okHttpClient,
                        context
                    )
                )
                com.example.ui.studio.StudioScreen(
                    viewModel = studioViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Outlook.route) {
                val outlookViewModel: com.example.ui.outlook.OutlookViewModel = viewModel(
                    factory = com.example.ui.outlook.OutlookViewModel.Factory(
                        AppContainer.getMicrosoftGraphRepository(context),
                        AppContainer.getMicrosoftAuthService(context)
                    )
                )
                com.example.ui.outlook.OutlookScreen(
                    viewModel = outlookViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onAskAi = { email ->
                        val sender = email.sender?.emailAddress?.name ?: email.sender?.emailAddress?.address ?: "Unknown Sender"
                        val subject = email.subject ?: "(No Subject)"
                        val date = email.receivedDateTime ?: "Unknown Date"
                        val body = email.bodyPreview ?: ""
                        
                        val contextText = "Sender: $sender\nSubject: $subject\nDate: $date\n\nBody Preview:\n$body"
                        
                        chatViewModel.setEmailContext(contextText)
                        
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(Screen.Outlook.route) { inclusive = true }
                        }
                    }
                )
            }
        }
    }
}

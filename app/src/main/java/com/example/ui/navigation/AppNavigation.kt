package com.example.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import com.example.di.AppContainer
import com.example.ui.WelcomeScreen
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.settings.SettingsScreen
import com.example.ui.settings.SettingsViewModel

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Welcome : Screen("welcome", "Welcome", Icons.AutoMirrored.Filled.Chat)
    object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    object Studio : Screen("studio", "AI Studio", Icons.Filled.Movie)
    object Market : Screen("market", "Crypto Info", Icons.Filled.Settings)
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
            AppContainer.okHttpClient,
            AppContainer.moshi
        )
    )
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
            settingsRepository,
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
                    onNavigateToTrading = { navController.navigate(Screen.Market.route) }
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
            composable(Screen.Market.route) {
                val marketInfoViewModel: com.example.ui.market.MarketInfoViewModel = viewModel(
                    factory = com.example.ui.market.MarketInfoViewModel.Factory(
                        AppContainer.okHttpClient
                    )
                )
                com.example.ui.market.MarketInfoScreen(
                    viewModel = marketInfoViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

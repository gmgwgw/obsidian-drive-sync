package com.obsidiandrivesync.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.obsidiandrivesync.AppContainer
import com.obsidiandrivesync.ui.home.HomeScreen
import com.obsidiandrivesync.ui.logs.SyncLogsScreen
import com.obsidiandrivesync.ui.picker.DriveFolderPickerScreen
import com.obsidiandrivesync.ui.picker.LocalFolderPickerScreen
import com.obsidiandrivesync.ui.settings.SettingsScreen

sealed class AppRoute(val route: String, val label: String, val icon: ImageVector? = null) {
    data object Home : AppRoute("home", "ホーム", Icons.Default.Sync)
    data object Logs : AppRoute("logs", "履歴", Icons.Default.History)
    data object Settings : AppRoute("settings", "設定", Icons.Default.Settings)
    data object DrivePicker : AppRoute("drive_picker", "Drive フォルダ選択")
    data object LocalPicker : AppRoute("local_picker", "ローカルフォルダ選択")
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val topTabs = listOf(AppRoute.Home, AppRoute.Logs, AppRoute.Settings)
    val showBottomBar = topTabs.any { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topTabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon!!, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppRoute.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(AppRoute.Home.route) {
                HomeScreen(
                    container = container,
                    onNavigateToSettings = {
                        navController.navigate(AppRoute.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(AppRoute.Logs.route) {
                SyncLogsScreen(container = container)
            }
            composable(AppRoute.Settings.route) {
                SettingsScreen(
                    container = container,
                    onOpenLocalPicker = { navController.navigate(AppRoute.LocalPicker.route) },
                    onOpenDrivePicker = { navController.navigate(AppRoute.DrivePicker.route) }
                )
            }
            composable(AppRoute.LocalPicker.route) {
                LocalFolderPickerScreen(
                    container = container,
                    onFolderSelected = { navController.popBackStack() },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(AppRoute.DrivePicker.route) {
                DriveFolderPickerScreen(
                    container = container,
                    onFolderSelected = { navController.popBackStack() },
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

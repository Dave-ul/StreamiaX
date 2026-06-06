package com.streamiax.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamiax.feature.catalog.CatalogScreen
import com.streamiax.feature.library.LibraryScreen

sealed class Screen(val route: String, val label: String) {
    data object Catalog : Screen("catalog", "Discover")
    data object Library : Screen("library", "Library")
}

private val bottomNavItems = listOf(Screen.Catalog, Screen.Library)

@Composable
fun StreamiaXNavHost() {
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDest = navBackStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            when (screen) {
                                Screen.Catalog -> Icon(Icons.Default.Home, contentDescription = null)
                                Screen.Library -> Icon(Icons.Default.LocalLibrary, contentDescription = null)
                            }
                        },
                        label = { Text(screen.label) },
                        selected = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Catalog.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable(Screen.Catalog.route) { CatalogScreen(navController) }
            composable(Screen.Library.route) { LibraryScreen(navController) }
        }
    }
}

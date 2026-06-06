package com.reelz.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.*
import androidx.navigation.compose.*
import com.reelz.data.model.MediaType
import com.reelz.ui.screens.detail.DetailScreen
import com.reelz.ui.screens.home.HomeScreen
import com.reelz.ui.screens.search.SearchScreen
import com.reelz.ui.screens.watchlist.WatchlistScreen
import com.reelz.ui.theme.*

sealed class Screen(val route: String) {
    object Home      : Screen("home")
    object Search    : Screen("search")
    object Watchlist : Screen("watchlist")
    object Detail    : Screen("detail/{tmdbId}/{mediaType}") {
        fun createRoute(tmdbId: Int, type: MediaType) = "detail/$tmdbId/${type.name}"
    }
}

data class BottomNavItem(val screen: Screen, val label: String, val icon: ImageVector)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Home,      "Home",      Icons.Default.Home),
    BottomNavItem(Screen.Search,    "Search",    Icons.Default.Search),
    BottomNavItem(Screen.Watchlist, "Watchlist", Icons.Default.Bookmark),
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStack  by navController.currentBackStackEntryAsState()
    val currentRoute  = navBackStack?.destination?.route

    val showBottomBar = currentRoute in bottomNavItems.map { it.screen.route }

    Scaffold(
        containerColor = Surface900,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = Surface800,
                    tonalElevation = 0.dp,
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.screen.route
                        NavigationBarItem(
                            selected = selected,
                            onClick  = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            },
                            icon = {
                                Icon(
                                    item.icon,
                                    contentDescription = item.label,
                                    tint = if (selected) Primary else White40,
                                )
                            },
                            label = {
                                Text(item.label, color = if (selected) Primary else White40)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = Primary.copy(.15f),
                            ),
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier.padding(padding),
            enterTransition  = { fadeIn(animationSpec = tween(220)) },
            exitTransition   = { fadeOut(animationSpec = tween(180)) },
        ) {
            composable(Screen.Home.route) {
                HomeScreen(onMediaClick = { id, type ->
                    navController.navigate(Screen.Detail.createRoute(id, type))
                })
            }
            composable(Screen.Search.route) {
                SearchScreen(onMediaClick = { id, type ->
                    navController.navigate(Screen.Detail.createRoute(id, type))
                })
            }
            composable(Screen.Watchlist.route) {
                WatchlistScreen(onMediaClick = { id, type ->
                    navController.navigate(Screen.Detail.createRoute(id, type))
                })
            }
            composable(
                route     = Screen.Detail.route,
                arguments = listOf(
                    navArgument("tmdbId")    { type = NavType.IntType },
                    navArgument("mediaType") { type = NavType.StringType },
                ),
            ) { back ->
                val tmdbId    = back.arguments?.getInt("tmdbId") ?: return@composable
                val typeStr   = back.arguments?.getString("mediaType") ?: "MOVIE"
                val mediaType = if (typeStr == "TV") MediaType.TV else MediaType.MOVIE
                DetailScreen(
                    tmdbId      = tmdbId,
                    mediaType   = mediaType,
                    onBack      = { navController.popBackStack() },
                    onMediaClick = { id, type ->
                        navController.navigate(Screen.Detail.createRoute(id, type))
                    },
                )
            }
        }
    }
}

package com.streamapp.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.*
import androidx.navigation.compose.*
import com.streamapp.data.model.MediaItem
import com.streamapp.ui.screens.detail.DetailScreen
import com.streamapp.ui.screens.home.HomeScreen
import com.streamapp.ui.screens.player.PlayerScreen
import com.streamapp.ui.screens.search.SearchScreen
import com.streamapp.ui.theme.*
import java.net.URLDecoder
import java.net.URLEncoder

sealed class Screen(val route: String) {
    object Home   : Screen("home")
    object Search : Screen("search")
    object Detail : Screen("detail/{id}/{type}") {
        fun create(id: Int, type: String) = "detail/$id/$type"
    }
    object Player : Screen("player/{url}/{title}/{episode}") {
        fun create(url: String, title: String, episode: String = "") =
            "player/${URLEncoder.encode(url, "UTF-8")}/" +
            "${URLEncoder.encode(title, "UTF-8")}/" +
            URLEncoder.encode(episode, "UTF-8")
    }
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector,
    val iconSelected: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Home,   "Home",   Icons.Outlined.Home,   Icons.Filled.Home),
    BottomNavItem(Screen.Search, "Search", Icons.Outlined.Search, Icons.Filled.Search),
)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route

    val showBottomBar = currentRoute in listOf(Screen.Home.route, Screen.Search.route)

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
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) item.iconSelected else item.icon,
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = Primary,
                                selectedTextColor   = Primary,
                                unselectedIconColor = White40,
                                unselectedTextColor = White40,
                                indicatorColor      = Primary.copy(alpha = 0.15f),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController    = navController,
            startDestination = Screen.Home.route,
            modifier         = Modifier.padding(padding),
            enterTransition  = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 6 } },
            exitTransition   = { fadeOut(tween(180)) },
            popEnterTransition  = { fadeIn(tween(220)) },
            popExitTransition   = { fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 6 } },
        ) {
            // ── Home ──────────────────────────────────────────────────────────
            composable(Screen.Home.route) {
                HomeScreen(
                    onItemClick = { item ->
                        navController.navigate(Screen.Detail.create(item.id, item.mediaType))
                    },
                    onSearchClick = { navController.navigate(Screen.Search.route) },
                )
            }

            // ── Search ────────────────────────────────────────────────────────
            composable(Screen.Search.route) {
                SearchScreen(
                    onItemClick = { item ->
                        navController.navigate(Screen.Detail.create(item.id, item.mediaType))
                    },
                    onBack = { navController.popBackStack() },
                )
            }

            // ── Detail ────────────────────────────────────────────────────────
            composable(
                route = Screen.Detail.route,
                arguments = listOf(
                    navArgument("id")   { type = NavType.IntType },
                    navArgument("type") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                // FIX: use named backStackEntry, not implicit "it", to avoid lambda capture bug
                val mediaTitle = backStackEntry.arguments?.getString("type") ?: ""
                DetailScreen(
                    onBack = { navController.popBackStack() },
                    onPlay = { url ->
                        navController.navigate(Screen.Player.create(url, mediaTitle))
                    },
                    onItemClick = { item ->
                        navController.navigate(Screen.Detail.create(item.id, item.mediaType))
                    },
                )
            }

            // ── Player ────────────────────────────────────────────────────────
            composable(
                route = Screen.Player.route,
                arguments = listOf(
                    navArgument("url")     { type = NavType.StringType },
                    navArgument("title")   { type = NavType.StringType },
                    navArgument("episode") { type = NavType.StringType },
                ),
            ) { entry ->
                val url     = URLDecoder.decode(entry.arguments?.getString("url")     ?: "", "UTF-8")
                val title   = URLDecoder.decode(entry.arguments?.getString("title")   ?: "", "UTF-8")
                val episode = URLDecoder.decode(entry.arguments?.getString("episode") ?: "", "UTF-8")
                PlayerScreen(
                    streamUrl    = url,
                    title        = title,
                    episodeLabel = episode,
                    onBack       = { navController.popBackStack() },
                )
            }
        }
    }
}

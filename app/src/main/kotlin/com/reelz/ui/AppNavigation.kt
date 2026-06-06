package com.reelz.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.*
import androidx.navigation.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.reelz.data.model.MediaType
import com.reelz.ui.screens.browse.BrowseScreen
import com.reelz.ui.screens.shorts.ShortsScreen
import com.reelz.ui.screens.downloads.DownloadsScreen
import com.reelz.ui.screens.transfer.TransferScreen
import com.reelz.ui.screens.profile.ProfileScreen
import com.reelz.ui.screens.detail.DetailScreen
import com.reelz.ui.screens.search.SearchScreen
import com.reelz.ui.theme.*

// ── Route definitions ─────────────────────────────────────────────────────────
sealed class Route(val path: String) {
    object Browse   : Route("browse")
    object Shorts   : Route("shorts")
    object Downloads: Route("downloads")
    object Transfer : Route("transfer")
    object Profile  : Route("profile")
    object Search   : Route("search")
    object Detail   : Route("detail/{tmdbId}/{mediaType}") {
        fun go(id: Int, type: MediaType) = "detail/$id/${type.name}"
    }
}

data class NavTab(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val activeIcon: ImageVector,
)

val navTabs = listOf(
    NavTab(Route.Browse.path,    "Browse",    Icons.Outlined.Explore,       Icons.Filled.Explore),
    NavTab(Route.Shorts.path,    "Shorts",    Icons.Outlined.PlayCircle,    Icons.Filled.PlayCircle),
    NavTab(Route.Downloads.path, "Downloads", Icons.Outlined.Download,      Icons.Filled.Download),
    NavTab(Route.Transfer.path,  "Transfer",  Icons.Outlined.SwapHoriz,     Icons.Filled.SwapHoriz),
    NavTab(Route.Profile.path,   "Profile",   Icons.Outlined.Person,        Icons.Filled.Person),
)

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val topLevelRoutes = navTabs.map { it.route }
    val showBottomBar = currentRoute in topLevelRoutes

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter   = slideInVertically { it },
                exit    = slideOutVertically { it },
            ) {
                ReelzBottomNav(
                    currentRoute = currentRoute,
                    onTabSelected = { route ->
                        nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState    = true
                        }
                    }
                )
            }
        },
    ) { padding ->
        NavHost(
            navController    = nav,
            startDestination = Route.Browse.path,
            modifier         = Modifier.padding(padding),
            enterTransition  = { fadeIn(tween(220)) + scaleIn(tween(220), 0.96f) },
            exitTransition   = { fadeOut(tween(180)) },
            popEnterTransition  = { fadeIn(tween(220)) },
            popExitTransition   = { fadeOut(tween(180)) + scaleOut(tween(220), 0.96f) },
        ) {
            composable(Route.Browse.path)    { BrowseScreen(nav) }
            composable(Route.Shorts.path)    { ShortsScreen(nav) }
            composable(Route.Downloads.path) { DownloadsScreen(nav) }
            composable(Route.Transfer.path)  { TransferScreen() }
            composable(Route.Profile.path)   { ProfileScreen(nav) }
            composable(Route.Search.path)    { SearchScreen(nav) }
            composable(
                route     = Route.Detail.path,
                arguments = listOf(
                    navArgument("tmdbId")    { type = NavType.IntType },
                    navArgument("mediaType") { type = NavType.StringType },
                ),
            ) { back ->
                val id   = back.arguments?.getInt("tmdbId") ?: return@composable
                val type = if (back.arguments?.getString("mediaType") == "TV") MediaType.TV else MediaType.MOVIE
                DetailScreen(tmdbId = id, mediaType = type, nav = nav)
            }
        }
    }
}

// ── Bottom nav matching Flutter's blurred frosted glass bar ──────────────────
@Composable
fun ReelzBottomNav(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    Box {
        // Frosted glass background
        Box(
            Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(
                    Brush.verticalGradient(listOf(Bg.copy(.0f), Bg.copy(.97f)))
                )
        )
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth().border(
                BorderStroke(0.8.dp, GlassBorder),
            ),
        ) {
            navTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                NavigationBarItem(
                    selected = selected,
                    onClick  = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTabSelected(tab.route)
                    },
                    icon = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimatedContent(selected, label = "icon") { sel ->
                                Icon(
                                    if (sel) tab.activeIcon else tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (sel) Brand else White40,
                                )
                            }
                            // Active indicator dot (like Flutter)
                            AnimatedVisibility(selected) {
                                Box(
                                    Modifier
                                        .padding(top = 3.dp)
                                        .width(16.dp)
                                        .height(2.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(Brand),
                                )
                            }
                        }
                    },
                    label  = {
                        Text(
                            tab.label,
                            color = if (selected) Brand else White40,
                            fontSize = 10.sp,
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

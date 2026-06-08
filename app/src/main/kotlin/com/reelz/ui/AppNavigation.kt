package com.reelz.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.*
import androidx.navigation.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.reelz.data.model.MediaType
import com.reelz.ui.components.*
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
    NavTab(Route.Browse.path,    "Browse",    IconCompass,     IconCompass),
    NavTab(Route.Shorts.path,    "Shorts",    IconPlayCircle,  IconPlayCircle),
    NavTab(Route.Downloads.path, "Downloads", IconDownloadCloud, IconDownloadCloud),
    NavTab(Route.Transfer.path,  "Transfer",  IconSwap,        IconSwap),
    NavTab(Route.Profile.path,   "Profile",   IconUser,        IconUser),
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
                enter   = slideInVertically { it } + fadeIn(),
                exit    = slideOutVertically { it } + fadeOut(),
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
            enterTransition  = { fadeIn(tween(280)) + scaleIn(tween(280), 0.97f) },
            exitTransition   = { fadeOut(tween(200)) + scaleOut(tween(200), 1.02f) },
            popEnterTransition  = { fadeIn(tween(280)) + scaleIn(tween(280), 0.97f) },
            popExitTransition   = { fadeOut(tween(200)) + scaleOut(tween(200), 0.96f) },
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

// ── Premium frosted-glass bottom navigation bar ───────────────────────────────
@Composable
fun ReelzBottomNav(
    currentRoute: String?,
    onTabSelected: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // Top glow line
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Brand.copy(.5f), Brand2.copy(.3f), Color.Transparent)
                    ),
                    start = Offset(0f, 0f),
                    end   = Offset(size.width, 0f),
                    strokeWidth = 1.5f,
                )
            }
            .background(
                Brush.verticalGradient(
                    listOf(Bg.copy(0f), Bg.copy(0.92f), Bg.copy(0.98f))
                )
            )
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            navTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                val iconAlpha by animateFloatAsState(if (selected) 1f else 0.45f, tween(250), label = "ia")
                val scale     by animateFloatAsState(if (selected) 1.1f else 1f, spring(0.5f, 400f), label = "sc")

                NavigationBarItem(
                    selected = selected,
                    onClick  = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTabSelected(tab.route)
                    },
                    icon = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                // Active pill glow background
                                AnimatedVisibility(
                                    visible = selected,
                                    enter = fadeIn(tween(200)) + scaleIn(tween(200), 0.5f),
                                    exit  = fadeOut(tween(150)),
                                ) {
                                    Box(
                                        Modifier
                                            .width(46.dp).height(28.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(
                                                Brush.radialGradient(
                                                    listOf(AmberGlass, Color.Transparent)
                                                )
                                            )
                                            .border(1.dp, AmberBorder, RoundedCornerShape(14.dp))
                                    )
                                }
                                Icon(
                                    if (selected) tab.activeIcon else tab.icon,
                                    contentDescription = tab.label,
                                    tint = if (selected) Brand else White.copy(0.45f),
                                    modifier = Modifier.size(20.dp).scale(scale),
                                )
                            }
                        }
                    },
                    label = {
                        Text(
                            tab.label,
                            color    = if (selected) Brand else White40,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
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

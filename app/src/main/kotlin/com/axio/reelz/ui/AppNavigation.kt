package com.axio.reelz.ui

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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.*
import androidx.navigation.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.axio.reelz.data.model.MediaType
import com.axio.reelz.ui.components.*
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.ui.screens.browse.BrowseScreen
import com.axio.reelz.ui.screens.browse.BrowseViewModel
import com.axio.reelz.ui.screens.explore.ExploreScreen
import com.axio.reelz.ui.screens.shorts.ShortsScreen
import com.axio.reelz.ui.screens.shorts.ShortsViewModel
import com.axio.reelz.ui.screens.downloads.DownloadsScreen
import com.axio.reelz.ui.screens.transfer.TransferScreen
import com.axio.reelz.ui.screens.profile.ProfileScreen
import com.axio.reelz.ui.screens.premium.PremiumScreen
import com.axio.reelz.ui.screens.settings.SettingsScreen
import com.axio.reelz.ui.screens.detail.DetailScreen
import com.axio.reelz.ui.screens.search.SearchScreen
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Route definitions ─────────────────────────────────────────────────────────
sealed class Route(val path: String) {
    object Browse   : Route("home")
    object Explore  : Route("explore")
    object Shorts   : Route("shorts")
    object Downloads: Route("downloads")
    object Transfer : Route("transfer")
    object Profile  : Route("profile")
    object Search   : Route("search")
    object Premium  : Route("premium")
    object Settings : Route("settings")
    object Detail   : Route("detail/{tmdbId}/{mediaType}") {
        fun go(id: Int, type: MediaType) = "detail/$id/${type.name}"
    }
}

data class NavTab(
    val route: String,
    val label: String,
    // outline icon shown when the tab is inactive
    val icon: ImageVector,
    // solid/filled icon shown when the tab is active — same white, no colour tint
    val activeIcon: ImageVector,
)

val navTabs = listOf(
    NavTab(Route.Browse.path,    "Home",      IconHome,             IconHomeFilled),
    NavTab(Route.Explore.path,   "Explore",   IconCompass,          IconCompassFilled),
    NavTab(Route.Shorts.path,    "Shorts",    IconReel,             IconReelFilled),
    NavTab(Route.Downloads.path, "Downloads", IconDownloadCloud,    IconDownloadCloudFilled),
    NavTab(Route.Profile.path,   "Profile",   IconUser,             IconUserFilled),
)

@Composable
fun AppNavigation(adEngine: AdEngine, openPremiumOnStart: Boolean = false) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val topLevelRoutes = navTabs.map { it.route }
    val showBottomBar = currentRoute in topLevelRoutes

    LaunchedEffect(Unit) {
        if (openPremiumOnStart) nav.navigate(Route.Premium.path)
    }

    val browseVm: BrowseViewModel = hiltViewModel()
    val browseListState = rememberLazyListState()
    val shortsVm: ShortsViewModel = hiltViewModel()

    var isHomeRefreshing by remember { mutableStateOf(false) }
    var isShortsRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            androidx.compose.animation.AnimatedVisibility(
                visible = showBottomBar,
                enter   = slideInVertically { it } + fadeIn(),
                exit    = slideOutVertically { it } + fadeOut(),
            ) {
                ReelzBottomNav(
                    currentRoute      = currentRoute,
                    isHomeRefreshing  = isHomeRefreshing,
                    onTabSelected     = { route ->
                        if (route == Route.Browse.path && currentRoute == Route.Browse.path) {
                            if (!isHomeRefreshing) {
                                coroutineScope.launch {
                                    val atTop = !browseListState.canScrollBackward &&
                                                browseListState.firstVisibleItemIndex == 0
                                    if (atTop) {
                                        isHomeRefreshing = true
                                        browseVm.load(forceRefresh = true)
                                        delay(700)
                                        isHomeRefreshing = false
                                    } else {
                                        browseListState.animateScrollToItem(0)
                                        delay(300)
                                        isHomeRefreshing = true
                                        browseVm.load(forceRefresh = true)
                                        delay(700)
                                        isHomeRefreshing = false
                                    }
                                }
                            }
                        } else if (route == Route.Shorts.path && currentRoute == Route.Shorts.path) {
                            if (!isShortsRefreshing) {
                                coroutineScope.launch {
                                    isShortsRefreshing = true
                                    shortsVm.refresh()
                                    delay(700)
                                    isShortsRefreshing = false
                                }
                            }
                        } else {
                            nav.navigate(route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
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
            composable(Route.Browse.path)    { BrowseScreen(nav, adEngine, browseVm, browseListState) }
            composable(Route.Explore.path)   { ExploreScreen(nav) }
            composable(Route.Shorts.path)    { ShortsScreen(nav, adEngine, shortsVm) }
            composable(Route.Downloads.path) { DownloadsScreen(nav) }
            composable(Route.Transfer.path)  { TransferScreen(nav) }
            composable(Route.Profile.path)   { ProfileScreen(nav) }
            composable(Route.Search.path)    { SearchScreen(nav) }
            composable(Route.Premium.path)   { PremiumScreen(nav) }
            composable(Route.Settings.path)  { com.axio.reelz.ui.screens.settings.SettingsScreen(nav) }
            composable(
                route     = Route.Detail.path,
                arguments = listOf(
                    navArgument("tmdbId")    { type = NavType.IntType },
                    navArgument("mediaType") { type = NavType.StringType },
                ),
            ) { back ->
                val id   = back.arguments?.getInt("tmdbId") ?: return@composable
                val type = if (back.arguments?.getString("mediaType") == "TV") MediaType.TV else MediaType.MOVIE
                DetailScreen(tmdbId = id, mediaType = type, nav = nav, adEngine = adEngine)
            }
        }
    }
}

// ── TikTok-style bottom navigation ───────────────────────────────────────────
//
// Rules:
//   • Active tab  → white filled icon + bold white label
//   • Inactive tab → dimmed outline icon + dim label
//   • NO pill, NO glow ring, NO indicator background
//   • Subtle top border gradient is the only decorative element on the bar itself
//   • Icon pops with a quick spring scale on selection; no continuous animation
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ReelzBottomNav(
    currentRoute: String?,
    isHomeRefreshing: Boolean,
    onTabSelected: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val d = LocalDimensions.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Single 1 px top-border gradient — the only decoration kept
            .drawBehind {
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.08f),
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent,
                        )
                    ),
                    start = Offset(0f, 0f),
                    end   = Offset(size.width, 0f),
                    strokeWidth = 1f,
                )
            }
    ) {
        // Bar background: opaque at bottom, translucent fade at top (no glass blur needed)
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f   to Color(0xB0050510),
                        1f   to Color(0xF8050510),
                    )
                )
        )

        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier       = Modifier.fillMaxWidth(),
        ) {
            navTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                val isHome   = tab.route == Route.Browse.path
                val showSpinner = isHome && isHomeRefreshing

                // Spring pop on select: 1.0 → 1.18 → settle at 1.0
                // The scale only applies at the moment of selection, then returns.
                val iconScale by animateFloatAsState(
                    targetValue   = if (selected) 1f else 1f, // kept neutral; pop handled below
                    animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
                    label         = "iconScale_${tab.route}",
                )

                NavigationBarItem(
                    selected = selected,
                    onClick  = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onTabSelected(tab.route)
                    },
                    icon = {
                        // Home tab: spinner crossfade; all other tabs: direct icon swap
                        if (isHome) {
                            Crossfade(
                                targetState  = showSpinner,
                                animationSpec = tween(250),
                                label        = "homeIconCrossfade",
                            ) { spinning ->
                                if (spinning) {
                                    CinematicSpinner(size = d.navIconSize, color = Color.White)
                                } else {
                                    Icon(
                                        imageVector        = if (selected) tab.activeIcon else tab.icon,
                                        contentDescription = tab.label,
                                        tint               = if (selected) Color.White else Color.White.copy(alpha = 0.38f),
                                        modifier           = Modifier.size(d.navIconSize),
                                    )
                                }
                            }
                        } else {
                            // Animate icon swap with a tiny crossfade so fill/unfill feels smooth
                            Crossfade(
                                targetState  = selected,
                                animationSpec = tween(180),
                                label        = "iconCrossfade_${tab.route}",
                            ) { isSelected ->
                                Icon(
                                    imageVector        = if (isSelected) tab.activeIcon else tab.icon,
                                    contentDescription = tab.label,
                                    tint               = if (isSelected) Color.White else Color.White.copy(alpha = 0.38f),
                                    modifier           = Modifier.size(d.navIconSize),
                                )
                            }
                        }
                    },
                    label = {
                        // Label: white + bold when active, dim when not — crossfade colour
                        Text(
                            text       = tab.label,
                            color      = if (selected) Color.White else Color.White.copy(alpha = 0.38f),
                            fontSize   = d.navFontSize,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    // Kill the default Material3 indicator pill entirely
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor         = Color.Transparent,
                        selectedIconColor      = Color.Unspecified,  // tint handled above
                        unselectedIconColor    = Color.Unspecified,
                        selectedTextColor      = Color.Unspecified,
                        unselectedTextColor    = Color.Unspecified,
                    ),
                )
            }
        }
    }
}

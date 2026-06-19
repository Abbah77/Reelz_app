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
import com.axio.reelz.ui.screens.shorts.ShortsScreen
import com.axio.reelz.ui.screens.shorts.ShortsViewModel
import com.axio.reelz.ui.screens.downloads.DownloadsScreen
import com.axio.reelz.ui.screens.transfer.TransferScreen
import com.axio.reelz.ui.screens.profile.ProfileScreen
import com.axio.reelz.ui.screens.premium.PremiumScreen
import com.axio.reelz.ui.screens.detail.DetailScreen
import com.axio.reelz.ui.screens.search.SearchScreen
import com.axio.reelz.ui.theme.*
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Route definitions ─────────────────────────────────────────────────────────
sealed class Route(val path: String) {
    object Browse   : Route("home")
    object Shorts   : Route("shorts")
    object Downloads: Route("downloads")
    object Transfer : Route("transfer")
    object Profile  : Route("profile")
    object Search   : Route("search")
    object Premium  : Route("premium")
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
    NavTab(Route.Browse.path,    "Home",      IconHome,          IconHomeFilled),
    NavTab(Route.Shorts.path,    "Shorts",    IconReel,          IconReelFilled),
    NavTab(Route.Downloads.path, "Downloads", IconDownloadCloud, IconDownloadCloud),
    NavTab(Route.Transfer.path,  "Transfer",  IconSwap,          IconSwap),
    NavTab(Route.Profile.path,   "Profile",   IconUser,          IconUserFilled),
)

@Composable
fun AppNavigation(adEngine: AdEngine, openPremiumOnStart: Boolean = false) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val topLevelRoutes = navTabs.map { it.route }
    val showBottomBar = currentRoute in topLevelRoutes

    // One-shot: PlayerActivity (a separate Activity, not part of this NavHost)
    // relaunches MainActivity with an extra when a free user taps "Upgrade to
    // Premium" from the subtitle drawer. Consumed once so back-navigation or a
    // config change doesn't re-trigger it.
    LaunchedEffect(Unit) {
        if (openPremiumOnStart) nav.navigate(Route.Premium.path)
    }

    // Shared across AppNavigation + BrowseScreen so the home button can control the list
    val browseVm: BrowseViewModel = hiltViewModel()
    val browseListState = rememberLazyListState()

    // Shared ShortsViewModel so the Shorts bottom tab can scroll-to-top + refresh
    val shortsVm: ShortsViewModel = hiltViewModel()

    // Home button state — drives TikTok-style spinner in bottom nav
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
                            // Already on Home:
                            //   • If scrolled down → scroll to top smoothly (no refresh yet)
                            //   • If already at top → refresh (spinner shows)
                            if (!isHomeRefreshing) {
                                coroutineScope.launch {
                                    val atTop = !browseListState.canScrollBackward &&
                                                browseListState.firstVisibleItemIndex == 0
                                    if (atTop) {
                                        // Already at top — refresh
                                        isHomeRefreshing = true
                                        browseVm.load(forceRefresh = true)
                                        delay(700)
                                        isHomeRefreshing = false
                                    } else {
                                        // Scroll to top first, then a brief pause, then refresh
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
                            // Already on Shorts — refresh feed
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
            // Pass shared vm + listState so home button can control both
            composable(Route.Browse.path)    { BrowseScreen(nav, adEngine, browseVm, browseListState) }
            composable(Route.Shorts.path)    { ShortsScreen(nav, adEngine, shortsVm) }
            composable(Route.Downloads.path) { DownloadsScreen(nav) }
            composable(Route.Transfer.path)  { TransferScreen() }
            composable(Route.Profile.path)   { ProfileScreen(nav) }
            composable(Route.Search.path)    { SearchScreen(nav) }
            composable(Route.Premium.path)   { PremiumScreen(nav) }
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

// ── Liquid glass bottom navigation bar ───────────────────────────────────────
@Composable
fun ReelzBottomNav(
    currentRoute: String?,
    isHomeRefreshing: Boolean,
    onTabSelected: (String) -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Brand.copy(.6f), Brand2.copy(.4f), Color.Transparent)
                    ),
                    start = Offset(0f, 0f),
                    end   = Offset(size.width, 0f),
                    strokeWidth = 1f,
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f   to Color(0x00050510),
                        0.1f to Color(0xB0050510),
                        1f   to Color(0xF5050510),
                    )
                )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x08FFFFFF), Color(0x04FFFFFF), Color(0x00FFFFFF))
                    )
                )
        )

        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            navTabs.forEach { tab ->
                val selected = currentRoute == tab.route
                val scale by animateFloatAsState(if (selected) 1.12f else 1f, spring(0.5f, 400f), label = "sc")

                // For the Home tab, show spinner while refreshing
                val isHome = tab.route == Route.Browse.path
                val showSpinner = isHome && isHomeRefreshing

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
                                // Active pill glow
                                // Use AnimatedVisibility via a Column to satisfy the ColumnScope receiver requirement
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = selected,
                                    enter = fadeIn(tween(200)) + scaleIn(tween(200), 0.5f),
                                    exit  = fadeOut(tween(150)),
                                ) {
                                    Box(
                                        Modifier
                                            .width(48.dp).height(30.dp)
                                            .clip(RoundedCornerShape(15.dp))
                                            .background(
                                                Brush.radialGradient(
                                                    listOf(Brand.copy(0.25f), Brand.copy(0.08f), Color.Transparent)
                                                )
                                            )
                                            .border(
                                                1.dp,
                                                Brush.linearGradient(listOf(Brand.copy(.5f), Brand2.copy(.3f))),
                                                RoundedCornerShape(15.dp)
                                            )
                                    )
                                }
                                } // end Column for AnimatedVisibility

                                // Home icon ↔ spinner crossfade
                                if (isHome) {
                                    Crossfade(
                                        targetState = showSpinner,
                                        animationSpec = tween(300),
                                        label = "homeIconCrossfade",
                                    ) { spinning ->
                                        if (spinning) {
                                            CinematicSpinner(
                                                size  = 21.dp,
                                                color = Brand,
                                            )
                                        } else {
                                            Icon(
                                                imageVector  = if (selected) tab.activeIcon else tab.icon,
                                                contentDescription = tab.label,
                                                tint         = if (selected) Brand else White.copy(0.4f),
                                                modifier     = Modifier.size(21.dp).scale(scale),
                                            )
                                        }
                                    }
                                } else {
                                    Icon(
                                        imageVector  = if (selected) tab.activeIcon else tab.icon,
                                        contentDescription = tab.label,
                                        tint         = if (selected) Brand else White.copy(0.4f),
                                        modifier     = Modifier.size(21.dp).scale(scale),
                                    )
                                }
                            }
                        }
                    },
                    label = {
                        Text(
                            tab.label,
                            color      = if (selected) Brand else White40,
                            fontSize   = 10.sp,
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

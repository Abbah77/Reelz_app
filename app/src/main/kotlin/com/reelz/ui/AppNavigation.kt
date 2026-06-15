package com.reelz.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.reelz.data.MediaItem
import com.reelz.ui.screens.*
import com.reelz.ui.theme.Bg
import com.reelz.ui.theme.Brand
import com.reelz.ui.theme.White40

private object Routes {
    const val BROWSE  = "browse"
    const val PLAYER  = "player?id={id}&type={type}&title={title}"
    const val PROFILE = "profile"

    fun player(id: String, type: String, title: String) =
        "player?id=${encode(id)}&type=$type&title=${encode(title)}"

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val navBack by nav.currentBackStackEntryAsState()
    val current = navBack?.destination?.route

    val browseVm: BrowseViewModel = viewModel()
    val playerVm: PlayerViewModel = viewModel()

    // Hide bottom bar on player screen
    val showBottomBar = current?.startsWith("player") == false

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Bg, tonalElevation = 0.dp) {
                    listOf(
                        Triple("Browse",  Icons.Default.Home,       Routes.BROWSE),
                        Triple("Profile", Icons.Default.Person,     Routes.PROFILE),
                    ).forEach { (label, icon, route) ->
                        NavigationBarItem(
                            selected = current == route,
                            onClick  = {
                                if (current != route) {
                                    nav.navigate(route) {
                                        popUpTo(Routes.BROWSE) { saveState = true }
                                        launchSingleTop = true
                                        restoreState    = true
                                    }
                                }
                            },
                            icon  = { Icon(icon, label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor   = Brand,
                                selectedTextColor   = Brand,
                                unselectedIconColor = White40,
                                unselectedTextColor = White40,
                                indicatorColor      = Bg,
                            ),
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(nav, startDestination = Routes.BROWSE, modifier = Modifier.padding(padding)) {

            composable(Routes.BROWSE) {
                BrowseScreen(vm = browseVm) { item ->
                    nav.navigate(Routes.player(item.id, item.type, item.title))
                }
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("id")    { type = NavType.StringType },
                    navArgument("type")  { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                )
            ) { back ->
                val id    = java.net.URLDecoder.decode(back.arguments?.getString("id") ?: "", "UTF-8")
                val type  = back.arguments?.getString("type") ?: "movie"
                val title = java.net.URLDecoder.decode(back.arguments?.getString("title") ?: "", "UTF-8")

                LaunchedEffect(id, type) {
                    if (type == "tv") playerVm.loadTv(id) else playerVm.loadMovie(id)
                }

                PlayerScreen(vm = playerVm, title = title, onBack = { nav.popBackStack() })
            }

            composable(Routes.PROFILE) { ProfileScreen() }
        }
    }
}

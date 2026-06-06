package com.reelz.ui.screens.profile

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.credentials.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.reelz.data.local.LikedDao
import com.reelz.data.local.WatchlistDao
import com.reelz.data.local.WatchHistoryDao
import com.reelz.data.model.*
import com.reelz.ui.components.*
import com.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val isSignedIn: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val watchlistDao: WatchlistDao,
    private val likedDao: LikedDao,
    private val historyDao: WatchHistoryDao,
) : ViewModel() {
    data class UiState(
        val profile: UserProfile = UserProfile(),
        val watchlist: List<WatchlistItem> = emptyList(),
        val liked: List<LikedItem> = emptyList(),
        val history: List<WatchHistory> = emptyList(),
        val activeTab: Int = 0,  // 0=Watchlist, 1=Liked, 2=History
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            watchlistDao.getAll().collect { wl -> _ui.update { it.copy(watchlist = wl) } }
        }
        viewModelScope.launch {
            likedDao.getAll().collect { l -> _ui.update { it.copy(liked = l) } }
        }
        viewModelScope.launch {
            historyDao.getRecent().collect { h -> _ui.update { it.copy(history = h) } }
        }
    }

    fun setTab(i: Int) { _ui.update { it.copy(activeTab = i) } }

    fun onSignIn(name: String, email: String, photoUrl: String?) {
        _ui.update { it.copy(profile = UserProfile(name, email, photoUrl, true)) }
    }

    fun signOut() { _ui.update { it.copy(profile = UserProfile()) } }

    fun clearHistory() { viewModelScope.launch { historyDao.clear() } }
}

@Composable
fun ProfileScreen(nav: NavController, vm: ProfileViewModel = hiltViewModel()) {
    val ui  by vm.ui.collectAsState()
    val ctx = LocalContext.current

    LazyColumn(Modifier.fillMaxSize().background(Bg).statusBarsPadding(), contentPadding = PaddingValues(bottom = 90.dp)) {

        // ── Header ─────────────────────────────────────────────────────
        item {
            Text("Profile", style = MaterialTheme.typography.headlineMedium.copy(color = White, fontWeight = FontWeight.Black),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
        }

        // ── Auth card ──────────────────────────────────────────────────
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (ui.profile.isSignedIn) {
                    // Signed-in state
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(56.dp).clip(CircleShape).background(BgRaised)) {
                            if (ui.profile.photoUrl != null) {
                                AsyncImage(ui.profile.photoUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(Icons.Default.Person, null, tint = White40, modifier = Modifier.fillMaxSize().padding(10.dp))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(ui.profile.name, color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(ui.profile.email, color = White60, fontSize = 13.sp)
                        }
                        TextButton(onClick = { vm.signOut() }) {
                            Text("Sign Out", color = Error, fontSize = 13.sp)
                        }
                    }
                } else {
                    // Sign-in prompt
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.AccountCircle, null, tint = White40, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Sign in to sync your watchlist", color = White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text("Your likes, saves & history are always saved locally", color = White60, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 17.sp)
                        Spacer(Modifier.height(14.dp))
                        GoogleSignInButton(ctx = ctx) { name, email, photo -> vm.onSignIn(name, email, photo) }
                    }
                }
            }
        }

        // ── Stats row ──────────────────────────────────────────────────
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Saved", ui.watchlist.size.toString(), Icons.Default.Bookmark, Modifier.weight(1f))
                StatCard("Liked", ui.liked.size.toString(), Icons.Default.Favorite, Modifier.weight(1f))
                StatCard("Watched", ui.history.size.toString(), Icons.Default.History, Modifier.weight(1f))
            }
        }

        // ── Tab selector ───────────────────────────────────────────────
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Watchlist", "Liked", "History").forEachIndexed { i, label ->
                    GenrePill(label, ui.activeTab == i) { vm.setTab(i) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ── Tab content ────────────────────────────────────────────────
        when (ui.activeTab) {
            0 -> if (ui.watchlist.isEmpty()) {
                item { EmptyTabHint("Nothing saved yet", "Tap the bookmark icon on any movie") }
            } else {
                items(ui.watchlist, key = { it.tmdbId }) { w ->
                    val type = if (w.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                    LibraryRow(w.title, w.posterPath, "Saved") { nav.navigate(com.reelz.ui.Route.Detail.go(w.tmdbId, type)) }
                }
            }
            1 -> if (ui.liked.isEmpty()) {
                item { EmptyTabHint("Nothing liked yet", "Tap the heart on any movie or show") }
            } else {
                items(ui.liked, key = { it.tmdbId }) { l ->
                    val type = if (l.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                    LibraryRow(l.title, l.posterPath, "Liked") { nav.navigate(com.reelz.ui.Route.Detail.go(l.tmdbId, type)) }
                }
            }
            2 -> {
                if (ui.history.isEmpty()) {
                    item { EmptyTabHint("No watch history yet", "Start watching something!") }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { vm.clearHistory() }) { Text("Clear All", color = Error, fontSize = 12.sp) }
                        }
                    }
                    items(ui.history, key = { it.key }) { h ->
                        val type = if (h.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                        val progress = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else 0f
                        LibraryRow(
                            title     = h.title,
                            poster    = h.posterPath,
                            subtitle  = if (h.season > 0) "S${h.season} E${h.episode}" else "Movie",
                            progress  = progress,
                            onClick   = { nav.navigate(com.reelz.ui.Route.Detail.go(h.tmdbId, type)) },
                        )
                    }
                }
            }
        }

        // ── App settings ───────────────────────────────────────────────
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Text("Settings", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SettingRow(Icons.Default.Storage,      "Storage Usage")
                SettingRow(Icons.Default.Security,     "Privacy & Security")
                SettingRow(Icons.Default.Notifications,"Notifications")
                SettingRow(Icons.Default.Info,         "About Reelz")
            }
        }
    }
}

@Composable
fun GoogleSignInButton(ctx: Context, onSignedIn: (String, String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    OutlinedButton(
        onClick = {
            scope.launch {
                try {
                    val credManager = CredentialManager.create(ctx)
                    val req = GetCredentialRequest(listOf(
                        GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(false)
                            .setServerClientId("855194597614-8qh36vk8ijg3uq8aqse3nu6ffduus9m8.apps.googleusercontent.com")  // Replace in production
                            .build()
                    ))
                    val result = credManager.getCredential(ctx as android.app.Activity, req)
                    val credential = result.credential
                    if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                        onSignedIn(googleCred.displayName ?: "", googleCred.id, googleCred.profilePictureUri?.toString())
                    }
                } catch (_: Exception) {
                    // Sign-in cancelled or failed — show friendly message
                }
            }
        },
        shape  = RoundedCornerShape(100.dp),
        border = BorderStroke(1.dp, GlassBorderMd),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.AccountCircle, null, tint = Brand, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Continue with Google", color = White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(BgCard).border(1.dp, GlassBorder, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Brand, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, color = White, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text(label, color = White60, fontSize = 11.sp)
        }
    }
}

@Composable
fun LibraryRow(title: String, poster: String?, subtitle: String = "", progress: Float = 0f, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(56.dp).height(78.dp).clip(RoundedCornerShape(8.dp)).background(BgRaised)) {
            AsyncImage(model = "https://image.tmdb.org/t/p/w342$poster", contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress > 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = Brand, trackColor = White20)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            if (subtitle.isNotBlank()) Text(subtitle, color = White60, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = White40, modifier = Modifier.size(20.dp))
    }
    Divider(color = GlassBorder, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
fun EmptyTabHint(title: String, subtitle: String) {
    Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = White60, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = White40, fontSize = 13.sp)
        }
    }
}

@Composable
fun SettingRow(icon: ImageVector, label: String, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .background(BgCard).border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = White60, modifier = Modifier.size(20.dp))
        Text(label, color = White80, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = White40, modifier = Modifier.size(18.dp))
    }
    Spacer(Modifier.height(2.dp))
}

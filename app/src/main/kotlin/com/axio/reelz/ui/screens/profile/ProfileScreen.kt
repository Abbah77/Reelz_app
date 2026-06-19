package com.axio.reelz.ui.screens.profile

import android.content.Context
import androidx.compose.animation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.credentials.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.axio.reelz.data.local.LikedDao
import com.axio.reelz.data.local.WatchlistDao
import com.axio.reelz.data.local.WatchHistoryDao
import com.axio.reelz.data.model.*
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// Custom icon vectors for settings
private val IconStorage: ImageVector get() = ImageVector.Builder("Storage", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(2f, 6f); arcTo(2f, 2f, 0f, false, true, 4f, 4f); lineTo(20f, 4f); arcTo(2f, 2f, 0f, false, true, 22f, 6f)
        arcTo(2f, 2f, 0f, false, true, 20f, 8f); lineTo(4f, 8f); arcTo(2f, 2f, 0f, false, true, 2f, 6f); close()
        moveTo(2f, 12f); arcTo(2f, 2f, 0f, false, true, 4f, 10f); lineTo(20f, 10f); arcTo(2f, 2f, 0f, false, true, 22f, 12f)
        arcTo(2f, 2f, 0f, false, true, 20f, 14f); lineTo(4f, 14f); arcTo(2f, 2f, 0f, false, true, 2f, 12f); close()
        moveTo(2f, 18f); arcTo(2f, 2f, 0f, false, true, 4f, 16f); lineTo(20f, 16f); arcTo(2f, 2f, 0f, false, true, 22f, 18f)
        arcTo(2f, 2f, 0f, false, true, 20f, 20f); lineTo(4f, 20f); arcTo(2f, 2f, 0f, false, true, 2f, 18f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, fill = SolidColor(Color.Transparent))
}.build()

private val IconShield: ImageVector get() = ImageVector.Builder("Shield", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); lineTo(20f, 6f); lineTo(20f, 11f)
        arcTo(9f, 9f, 0f, false, true, 12f, 20f)
        arcTo(9f, 9f, 0f, false, true, 4f, 11f); lineTo(4f, 6f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, fill = SolidColor(Color.Transparent))
}.build()

private val IconBell: ImageVector get() = ImageVector.Builder("Bell", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(18f, 8f); arcTo(6f, 6f, 0f, false, false, 6f, 8f)
        lineTo(5f, 17f); lineTo(19f, 17f); lineTo(18f, 8f)
        moveTo(10.27f, 21f); arcTo(2f, 2f, 0f, false, false, 13.73f, 21f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconInfoOutline: ImageVector get() = ImageVector.Builder("Info", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
        moveTo(12f, 8f); lineTo(12f, 8.01f); moveTo(12f, 11f); lineTo(12f, 16f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconChevronRight: ImageVector get() = ImageVector.Builder("ChevRight", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(9f, 18f); lineTo(15f, 12f); lineTo(9f, 6f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconBookmarkSolid: ImageVector get() = ImageVector.Builder("BookmarkFill", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(12f, 16f); lineTo(5f, 21f); close()
    }, fill = SolidColor(Color(0xFFE8A020)))
}.build()

private val IconHeartSolid: ImageVector get() = ImageVector.Builder("HeartFill", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(20.84f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 8.5f)
        arcTo(5.5f, 5.5f, 0f, false, false, 3.16f, 4.61f)
        arcTo(5.5f, 5.5f, 0f, false, false, 12f, 20f)
        arcTo(5.5f, 5.5f, 0f, false, false, 20.84f, 4.61f); close()
    }, fill = SolidColor(Color(0xFFFF3D6E)))
}.build()

private val IconHistory: ImageVector get() = ImageVector.Builder("History", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 2f, 12f)
        arcTo(10f, 10f, 0f, false, false, 12f, 22f)
        arcTo(10f, 10f, 0f, false, false, 22f, 12f)
        moveTo(12f, 6f); lineTo(12f, 12f); lineTo(16f, 14f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconCrown: ImageVector get() = ImageVector.Builder("Crown", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f, 8.5f); lineTo(7f, 13f); lineTo(12f, 5.5f); lineTo(17f, 13f); lineTo(21f, 8.5f)
        lineTo(19.2f, 17.5f); lineTo(4.8f, 17.5f); close()
        moveTo(4.8f, 19.2f); lineTo(19.2f, 19.2f)
    }, fill = SolidColor(Color.White))
}.build()

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val isSignedIn: Boolean = false,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val watchlistDao: WatchlistDao,
    private val likedDao: LikedDao,
    private val historyDao: WatchHistoryDao,
    private val userSessionRepository: com.axio.reelz.data.repository.UserSessionRepository,
    private val premiumGate: com.axio.reelz.remoteconfig.PremiumGate,
) : ViewModel() {
    data class UiState(
        val profile: UserProfile = UserProfile(),
        val watchlist: List<WatchlistItem> = emptyList(),
        val liked: List<LikedItem> = emptyList(),
        val history: List<WatchHistory> = emptyList(),
        val activeTab: Int = 0,
        val userState: com.axio.reelz.remoteconfig.UserState = com.axio.reelz.remoteconfig.UserState.GUEST,
        val daysUntilExpiry: Int = 0,
        val showRenewBanner: Boolean = false,
    )
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { watchlistDao.getAll().collect { wl -> _ui.update { it.copy(watchlist = wl) } } }
        viewModelScope.launch { likedDao.getAll().collect { l -> _ui.update { it.copy(liked = l) } } }
        viewModelScope.launch { historyDao.getRecent().collect { h -> _ui.update { it.copy(history = h) } } }

        restoreProfileFromSession()

        viewModelScope.launch {
            premiumGate.state.collect { state ->
                val session = premiumGate.currentSession()
                _ui.update {
                    it.copy(
                        userState       = state,
                        daysUntilExpiry = premiumGate.daysUntilExpiry(),
                        showRenewBanner = premiumGate.shouldShowRenewBanner(),
                        profile         = if (session != null)
                            UserProfile(session.name, session.email, session.photoUrl, true)
                        else
                            it.profile, // keep whatever profile is already set
                    )
                }
            }
        }
    }

    private fun restoreProfileFromSession() {
        viewModelScope.launch {
            val session = userSessionRepository.currentSessionOrNull()
            if (session != null) {
                _ui.update {
                    it.copy(profile = UserProfile(session.name, session.email, session.photoUrl, true))
                }
            }
        }
    }

    fun setTab(i: Int) { _ui.update { it.copy(activeTab = i) } }

    fun onSignIn(idToken: String?, name: String, email: String, photoUrl: String?) {
        _ui.update { it.copy(profile = UserProfile(name, email, photoUrl, true)) }
        viewModelScope.launch { userSessionRepository.onSignedIn(idToken, name, email, photoUrl) }
    }

    fun signOut() {
        _ui.update { it.copy(profile = UserProfile()) }
        viewModelScope.launch {
            userSessionRepository.signOut()
            try {
                CredentialManager.create(appContext)
                    .clearCredentialState(ClearCredentialStateRequest())
            } catch (_: Exception) {}
        }
    }

    fun clearHistory() { viewModelScope.launch { historyDao.clear() } }
}

@Composable
fun ProfileScreen(nav: NavController, vm: ProfileViewModel = hiltViewModel()) {
    val ui  by vm.ui.collectAsState()
    val ctx = LocalContext.current

    LazyColumn(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        // ── Header ─────────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Profile", style = MaterialTheme.typography.headlineMedium.copy(
                        color = White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
                    ))
                    Text("Your personal cinema", color = Brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Icon(IconUser, null, tint = Brand.copy(.6f), modifier = Modifier.size(26.dp))
            }
        }

        // ── Auth card ──────────────────────────────────────────────────
        item {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (ui.profile.isSignedIn)
                            Brush.linearGradient(listOf(BgCard, BgRaised))
                        else
                            Brush.linearGradient(listOf(BrandDim.copy(.4f), BgCard))
                    )
                    .border(
                        1.dp,
                        if (ui.profile.isSignedIn) GlassBorderMd else AmberBorder,
                        RoundedCornerShape(20.dp)
                    )
            ) {
                if (ui.profile.isSignedIn) {
                    Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(58.dp).clip(CircleShape).background(BgRaised).border(2.dp, Brand.copy(.5f), CircleShape)) {
                            if (ui.profile.photoUrl != null) {
                                AsyncImage(ui.profile.photoUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(IconUser, null, tint = White40, modifier = Modifier.fillMaxSize().padding(12.dp))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(ui.profile.name, color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                if (ui.userState == com.axio.reelz.remoteconfig.UserState.PREMIUM_ACTIVE ||
                                    ui.userState == com.axio.reelz.remoteconfig.UserState.PREMIUM_GRACE) {
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Brand.copy(.18f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    ) {
                                        Icon(IconCrown, null, tint = Brand, modifier = Modifier.size(10.dp))
                                        Text("PREMIUM", color = Brand, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(ui.profile.email, color = White60, fontSize = 13.sp)
                        }
                        TextButton(onClick = { vm.signOut() }) {
                            Text("Sign Out", color = Error, fontSize = 12.sp)
                        }
                    }
                } else {
                    Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(68.dp).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(AmberGlass, Color.Transparent)))
                                .border(1.dp, AmberBorder, CircleShape))
                            Icon(IconUser, null, tint = Brand, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Sync your watchlist", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Your likes, saves & history are always saved locally",
                            color = White60, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(16.dp))
                        GoogleSignInButton(ctx = ctx) { idToken, name, email, photo -> vm.onSignIn(idToken, name, email, photo) }
                    }
                }
            }
        }

        // ── Premium entry / renew banner ──────────────────────────────────
        item {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (ui.showRenewBanner) AmberGlass else BgCard)
                    .border(1.dp, if (ui.showRenewBanner) AmberBorder else GlassBorderMd, RoundedCornerShape(16.dp))
                    .clickable { nav.navigate(com.axio.reelz.ui.Route.Premium.path) }
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Brand.copy(.15f)),
                        Alignment.Center,
                    ) { Icon(IconCrown, null, tint = Brand, modifier = Modifier.size(18.dp)) }
                    Column(Modifier.weight(1f)) {
                        val (title, subtitle) = when (ui.userState) {
                            com.axio.reelz.remoteconfig.UserState.PREMIUM_GRACE ->
                                "Payment due" to "Your premium access ends soon — renew to keep it"
                            com.axio.reelz.remoteconfig.UserState.PREMIUM_ACTIVE ->
                                if (ui.showRenewBanner) "Renews in ${ui.daysUntilExpiry} day${if (ui.daysUntilExpiry == 1) "" else "s"}" to "Tap to renew your plan"
                                else "Premium active" to "Unlimited downloads, 4K, no ads"
                            com.axio.reelz.remoteconfig.UserState.PREMIUM_EXPIRED ->
                                "Premium expired" to "Renew to get your benefits back"
                            else -> "Go Premium" to "4K streaming, unlimited downloads, no ads"
                        }
                        Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(subtitle, color = White60, fontSize = 11.sp)
                    }
                    Icon(IconChevronRight, null, tint = White40, modifier = Modifier.size(18.dp))
                }
            }
        }

        // ── Stats row ──────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatCard("Saved",   ui.watchlist.size.toString(), IconBookmarkSolid, Modifier.weight(1f))
                StatCard("Liked",   ui.liked.size.toString(),     IconHeartSolid,    Modifier.weight(1f))
                StatCard("Watched", ui.history.size.toString(),   IconHistory,       Modifier.weight(1f))
            }
        }

        // ── Tab selector ───────────────────────────────────────────────
        item {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Watchlist", "Liked", "History").forEachIndexed { i, label ->
                    GenrePill(label, ui.activeTab == i) { vm.setTab(i) }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ── Tab content ────────────────────────────────────────────────
        when (ui.activeTab) {
            0 -> if (ui.watchlist.isEmpty()) {
                item { EmptyTabHint("Nothing saved yet", "Bookmark movies to find them here") }
            } else {
                items(ui.watchlist, key = { it.tmdbId }) { w ->
                    val type = if (w.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                    LibraryRow(w.title, w.posterPath, "Saved") { nav.navigate(com.axio.reelz.ui.Route.Detail.go(w.tmdbId, type)) }
                }
            }
            1 -> if (ui.liked.isEmpty()) {
                item { EmptyTabHint("Nothing liked yet", "Tap the heart on any movie or show") }
            } else {
                items(ui.liked, key = { it.tmdbId }) { l ->
                    val type = if (l.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                    LibraryRow(l.title, l.posterPath, "Liked") { nav.navigate(com.axio.reelz.ui.Route.Detail.go(l.tmdbId, type)) }
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
                            title    = h.title,
                            poster   = h.posterPath,
                            subtitle = if (h.season > 0) "S${h.season} · E${h.episode}" else "Movie",
                            progress = progress,
                            onClick  = { nav.navigate(com.axio.reelz.ui.Route.Detail.go(h.tmdbId, type)) },
                        )
                    }
                }
            }
        }

        // ── Settings ───────────────────────────────────────────────────
        item { SectionHeader("Settings") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingRow(IconStorage,     "Storage Usage")
                SettingRow(IconShield,      "Privacy & Security")
                SettingRow(IconBell,        "Notifications")
                SettingRow(IconInfoOutline, "About Reelz")
            }
        }
    }
}

@Composable
fun GoogleSignInButton(ctx: Context, onSignedIn: (String?, String, String, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val activity = ctx as? android.app.Activity
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(100.dp))
                .background(BgRaised)
                .border(1.dp, GlassBorderMd, RoundedCornerShape(100.dp))
                .clickable {
                    if (activity == null) {
                        errorMsg = "Sign-in unavailable. Please restart the app."
                        return@clickable
                    }
                    errorMsg = null
                    scope.launch {
                        val credManager = CredentialManager.create(ctx)
                        val webClientId = "179017454626-db23ivhrbgn25pe41s4jeuo8293o2mds.apps.googleusercontent.com"

                        suspend fun handleCredentialResult(result: GetCredentialResponse) {
                            val credential = result.credential
                            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                                // idToken is now threaded through to the backend for server-side verification
                                val idToken = googleCred.idToken
                                android.util.Log.d("ReelzAuth", "signed in as: id=${googleCred.id} name=${googleCred.displayName} hasToken=${idToken.isNotBlank()}")
                                withContext(Dispatchers.Main) {
                                    onSignedIn(idToken, googleCred.displayName ?: "", googleCred.id, googleCred.profilePictureUri?.toString())
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    errorMsg = "Sign-in failed. Unexpected credential type."
                                }
                            }
                        }

                        // Step 1 — try One Tap (fast, no UI if already authorized)
                        try {
                            val req = GetCredentialRequest(listOf(
                                GetGoogleIdOption.Builder()
                                    .setFilterByAuthorizedAccounts(false)
                                    .setServerClientId(webClientId)
                                    .build()
                            ))
                            val result = credManager.getCredential(activity, req)
                            handleCredentialResult(result)
                            return@launch
                        } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                            android.util.Log.w("ReelzAuth", "One Tap failed (${e.message}), falling back to Sign In With Google")
                        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                            android.util.Log.d("ReelzAuth", "Sign-in cancelled by user")
                            return@launch
                        }

                        // Step 2 — fallback to full Google Sign-In bottom sheet
                        try {
                            val req = GetCredentialRequest(listOf(
                                GetSignInWithGoogleOption.Builder(webClientId).build()
                            ))
                            val result = credManager.getCredential(activity, req)
                            handleCredentialResult(result)
                        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                            android.util.Log.d("ReelzAuth", "Sign-in cancelled by user")
                        } catch (e: Exception) {
                            android.util.Log.e("ReelzAuth", "Sign-in fallback error: ${e.javaClass.name}: ${e.message}")
                            errorMsg = "Sign-in failed: ${e.message}"
                        }
                    }
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("G", color = Brand, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text("Continue with Google", color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        if (errorMsg != null) {
            Spacer(Modifier.height(8.dp))
            Text(errorMsg!!, color = Error, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun StatCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(14.dp))
            .padding(vertical = 16.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Brand, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(value, color = White, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(label, color = White60, fontSize = 11.sp)
        }
    }
}

@Composable
fun LibraryRow(title: String, poster: String?, subtitle: String = "", progress: Float = 0f, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.width(58.dp).height(82.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, GlassBorderMd, RoundedCornerShape(10.dp))
                .background(BgRaised)
        ) {
            AsyncImage(model = "https://image.tmdb.org/t/p/w342$poster", contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(White20))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp)
                    .background(Brush.horizontalGradient(listOf(Brand, Brand2))))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = White60, fontSize = 12.sp)
            }
        }
        Icon(IconChevronRight, null, tint = White40, modifier = Modifier.size(18.dp))
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).padding(horizontal = 16.dp).background(GlassBorder))
}

@Composable
fun EmptyTabHint(title: String, subtitle: String) {
    Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title,    color = White60, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(subtitle, color = White40, fontSize = 13.sp)
        }
    }
}

@Composable
fun SettingRow(icon: ImageVector, label: String, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(GlassMd)
                .border(1.dp, GlassBorderMd, RoundedCornerShape(10.dp)),
            Alignment.Center,
        ) { Icon(icon, null, tint = White60, modifier = Modifier.size(18.dp)) }
        Text(label, color = White80, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Icon(IconChevronRight, null, tint = White30, modifier = Modifier.size(16.dp))
    }
}

private val White30 = Color(0x4DF8F4EE)

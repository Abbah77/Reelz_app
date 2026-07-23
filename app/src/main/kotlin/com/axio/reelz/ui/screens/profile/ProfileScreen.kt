package com.axio.reelz.ui.screens.profile

import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.geometry.Offset
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
import com.axio.reelz.data.local.SavedVideoDao
import com.axio.reelz.data.local.WatchlistDao
import com.axio.reelz.data.local.WatchHistoryDao
import com.axio.reelz.data.model.*
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Icon vectors ──────────────────────────────────────────────────────────────

private val IconSettings: ImageVector get() = ImageVector.Builder("Settings", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 8f); arcTo(4f, 4f, 0f, false, false, 12f, 16f); arcTo(4f, 4f, 0f, false, false, 12f, 8f); close()
        moveTo(12f, 2f); lineTo(11f, 2f); lineTo(11f, 4.07f)
        arcTo(8f, 8f, 0f, false, false, 5.14f, 7.28f); lineTo(3.71f, 5.86f); lineTo(2.29f, 7.28f)
        lineTo(3.72f, 8.71f); arcTo(8f, 8f, 0f, false, false, 2f, 12f)
        arcTo(8f, 8f, 0f, false, false, 3.72f, 15.29f); lineTo(2.29f, 16.71f); lineTo(3.71f, 18.14f)
        lineTo(5.14f, 16.71f); arcTo(8f, 8f, 0f, false, false, 11f, 19.93f); lineTo(11f, 22f)
        lineTo(13f, 22f); lineTo(13f, 19.93f)
        arcTo(8f, 8f, 0f, false, false, 18.86f, 16.71f); lineTo(20.29f, 18.14f); lineTo(21.71f, 16.71f)
        lineTo(20.28f, 15.28f); arcTo(8f, 8f, 0f, false, false, 22f, 12f)
        arcTo(8f, 8f, 0f, false, false, 20.28f, 8.71f); lineTo(21.71f, 7.29f); lineTo(20.29f, 5.86f)
        lineTo(18.86f, 7.29f); arcTo(8f, 8f, 0f, false, false, 13f, 4.07f); lineTo(13f, 2f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.4f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconBookmarkSolid: ImageVector get() = ImageVector.Builder("BookmarkFill", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(12f, 16f); lineTo(5f, 21f); close()
    }, fill = SolidColor(Color(0xFFE8A020)))
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

private val IconVideoSolid: ImageVector get() = ImageVector.Builder("VideoFill", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(2f, 8f); arcTo(2f, 2f, 0f, false, true, 4f, 6f); lineTo(14f, 6f)
        arcTo(2f, 2f, 0f, false, true, 16f, 8f); lineTo(16f, 16f)
        arcTo(2f, 2f, 0f, false, true, 14f, 18f); lineTo(4f, 18f)
        arcTo(2f, 2f, 0f, false, true, 2f, 16f); close()
        moveTo(22f, 8.5f); lineTo(18f, 11f); lineTo(18f, 13f); lineTo(22f, 15.5f); close()
    }, fill = SolidColor(Color(0xFF5B7FFF)))
}.build()

private val IconChevronRight: ImageVector get() = ImageVector.Builder("ChevRight", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(9f, 18f); lineTo(15f, 12f); lineTo(9f, 6f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val White30 = Color(0x4DF8F4EE)

// ── Data ──────────────────────────────────────────────────────────────────────

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val isSignedIn: Boolean = false,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val watchlistDao: WatchlistDao,
    private val historyDao: WatchHistoryDao,
    private val savedVideoDao: SavedVideoDao,
    private val userSessionRepository: com.axio.reelz.data.repository.UserSessionRepository,
    private val premiumGate: com.axio.reelz.remoteconfig.PremiumGate,
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 20
    }

    data class UiState(
        val profile: UserProfile = UserProfile(),
        val watchlist: List<WatchlistItem> = emptyList(),
        // History is paginated — only loaded IDs/metadata, no images stored
        val historyPage: List<WatchHistory> = emptyList(),
        val historyTotal: Int = 0,
        val historyLoading: Boolean = false,
        val historyAllLoaded: Boolean = false,
        val saved: List<SavedVideoItem> = emptyList(),
        val activeTab: Int = 0,
        val userState: com.axio.reelz.remoteconfig.UserState = com.axio.reelz.remoteconfig.UserState.GUEST,
        val daysUntilExpiry: Int = 0,
        val showRenewBanner: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var historyOffset = 0

    init {
        viewModelScope.launch { watchlistDao.getAll().collect { wl -> _ui.update { it.copy(watchlist = wl) } } }
        viewModelScope.launch { savedVideoDao.getAll().collect { s -> _ui.update { it.copy(saved = s) } } }
        // History: load first page and total count — everything on demand
        loadNextHistoryPage()

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
                            it.profile,
                    )
                }
            }
        }
    }

    /** Load the next page of history lazily — called as user scrolls. */
    fun loadNextHistoryPage() {
        if (_ui.value.historyLoading || _ui.value.historyAllLoaded) return
        _ui.update { it.copy(historyLoading = true) }
        viewModelScope.launch {
            val page = historyDao.getPage(limit = PAGE_SIZE, offset = historyOffset)
            val total = historyDao.count()
            historyOffset += page.size
            _ui.update { state ->
                state.copy(
                    historyPage    = state.historyPage + page,
                    historyTotal   = total,
                    historyLoading = false,
                    historyAllLoaded = historyOffset >= total,
                )
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

    fun setTab(i: Int) {
        _ui.update { it.copy(activeTab = i) }
        // When user switches to history tab, ensure we have data
        if (i == 2 && _ui.value.historyPage.isEmpty()) loadNextHistoryPage()
    }

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

    fun clearHistory() {
        viewModelScope.launch {
            historyDao.clear()
            historyOffset = 0
            _ui.update { it.copy(historyPage = emptyList(), historyTotal = 0, historyAllLoaded = false) }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun ProfileScreen(nav: NavController, vm: ProfileViewModel = hiltViewModel()) {
    val d = LocalDimensions.current
    val ui  by vm.ui.collectAsState()
    val ctx = LocalContext.current

    LazyColumn(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = d.spaceXxl * 3.1f),
    ) {
        // ── Header ─────────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad + d.spaceXs, vertical = d.spaceLg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Profile", style = MaterialTheme.typography.headlineMedium.copy(
                        color = White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
                    ))
                    Text("Your personal cinema", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                // Settings gear icon — navigates to Settings screen
                Box(
                    Modifier
                        .size(d.avatarSm + d.spaceMd)
                        .clip(CircleShape)
                        .background(GlassMd)
                        .border(d.borderThin, GlassBorderMd, CircleShape)
                        .clickable { nav.navigate(com.axio.reelz.ui.Route.Settings.path) },
                    Alignment.Center,
                ) {
                    Icon(IconSettings, "Settings", tint = White60, modifier = Modifier.size(d.iconMd - 2.dp))
                }
            }
        }

        // ── Auth card ──────────────────────────────────────────────────
        item {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad)
                    .clip(RoundedCornerShape(d.radiusLg))
                    .background(
                        if (ui.profile.isSignedIn)
                            Brush.linearGradient(listOf(BgCard, BgRaised))
                        else
                            Brush.linearGradient(listOf(BrandDim.copy(.4f), BgCard))
                    )
                    .border(
                        d.borderThin,
                        if (ui.profile.isSignedIn) GlassBorderMd else AmberBorder,
                        RoundedCornerShape(d.radiusLg)
                    )
            ) {
                if (ui.profile.isSignedIn) {
                    Row(Modifier.padding(d.spaceLg + d.spaceXs), horizontalArrangement = Arrangement.spacedBy(d.spaceMd + d.spaceXs), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(d.avatarMd + d.spaceMd).clip(CircleShape).background(BgRaised).border(d.borderMed + 0.5.dp, Brand.copy(.5f), CircleShape)) {
                            if (ui.profile.photoUrl != null) {
                                AsyncImage(ui.profile.photoUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(IconUser, null, tint = White40, modifier = Modifier.fillMaxSize().padding(d.spaceMd))
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                                Text(ui.profile.name, color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
                                if (ui.userState == com.axio.reelz.remoteconfig.UserState.PREMIUM_ACTIVE ||
                                    ui.userState == com.axio.reelz.remoteconfig.UserState.PREMIUM_GRACE) {
                                    Row(
                                        Modifier
                                            .clip(RoundedCornerShape(d.radiusSm))
                                            .background(Brand.copy(.18f))
                                            .padding(horizontal = d.spaceSm, vertical = d.spaceXxs),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp),
                                    ) {
                                        Icon(IconCrown, null, tint = Brand, modifier = Modifier.size(d.iconSm - 2.dp))
                                        Text("PREMIUM", color = Brand, fontSize = d.textXxs, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(ui.profile.email, color = White60, fontSize = d.textMd)
                        }
                        TextButton(onClick = { vm.signOut() }) {
                            Text("Sign Out", color = Error, fontSize = d.textSm)
                        }
                    }
                } else {
                    Column(Modifier.padding(d.spaceXl - d.spaceXs), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(d.avatarLg + d.spaceXs).clip(CircleShape)
                                .background(Brush.radialGradient(listOf(AmberGlass, Color.Transparent)))
                                .border(d.borderThin, AmberBorder, CircleShape))
                            Icon(IconUser, null, tint = Brand, modifier = Modifier.size(d.iconLg + 2.dp))
                        }
                        Spacer(Modifier.height(d.spaceMd + d.spaceXs))
                        Text("Sync your watchlist", color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg)
                        Spacer(Modifier.height(d.spaceXs))
                        Text(
                            "Login to save your favorites",
                            color = White60, fontSize = d.textSm, textAlign = TextAlign.Center, lineHeight = (d.textSm.value * 1.5f).sp,
                        )
                        Spacer(Modifier.height(d.spaceLg))
                        GoogleSignInButton(ctx = ctx) { idToken, name, email, photo -> vm.onSignIn(idToken, name, email, photo) }
                    }
                }
            }
        }

        // ── Premium entry / renew banner ──────────────────────────────────
        item {
            Spacer(Modifier.height(d.spaceMd + d.spaceXs))
            Box(
                Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad)
                    .clip(RoundedCornerShape(d.radiusMd + d.spaceXs))
                    .background(if (ui.showRenewBanner) AmberGlass else BgCard)
                    .border(d.borderThin, if (ui.showRenewBanner) AmberBorder else GlassBorderMd, RoundedCornerShape(d.radiusMd + d.spaceXs))
                    .clickable { nav.navigate(com.axio.reelz.ui.Route.Premium.path) }
                    .padding(d.spaceLg),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                    Box(
                        Modifier.size(d.avatarSm + d.spaceXs).clip(CircleShape).background(Brand.copy(.15f)),
                        Alignment.Center,
                    ) { Icon(IconCrown, null, tint = Brand, modifier = Modifier.size(d.iconMd - 2.dp)) }
                    Column(Modifier.weight(1f)) {
                        val (title, subtitle) = when (ui.userState) {
                            com.axio.reelz.remoteconfig.UserState.PREMIUM_GRACE ->
                                "Payment due" to "Your premium access ends soon — renew to keep it"
                            com.axio.reelz.remoteconfig.UserState.PREMIUM_ACTIVE ->
                                if (ui.showRenewBanner) "Renews in ${ui.daysUntilExpiry} day${if (ui.daysUntilExpiry == 1) "" else "s"}" to "Tap to renew your plan"
                                else "Premium active" to "Unlimited downloads, 4K, no ads"
                            com.axio.reelz.remoteconfig.UserState.PREMIUM_EXPIRED ->
                                "Premium expired" to "Renew to get your benefits back"
                            else -> "Go Premium" to "Upto 4K streaming, unlimited downloads, no ads"
                        }
                        Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = (d.textMd.value + 1).sp)
                        Text(subtitle, color = White60, fontSize = d.textXs)
                    }
                    Icon(IconChevronRight, null, tint = White40, modifier = Modifier.size(d.iconMd - 2.dp))
                }
            }
        }

        // ── Stats row ──────────────────────────────────────────────────
        item {
            Spacer(Modifier.height(d.spaceLg - d.spaceXs))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad + d.spaceXs),
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 2.dp),
            ) {
                StatCard("Watchlist", ui.watchlist.size.toString(), IconBookmarkSolid, Modifier.weight(1f))
                StatCard("Saved",    ui.saved.size.toString(),     IconVideoSolid,    Modifier.weight(1f))
                StatCard("Watched",  ui.historyTotal.toString(),   IconHistory,       Modifier.weight(1f))
            }
        }

        // ── Tab selector ───────────────────────────────────────────────
        item {
            Spacer(Modifier.height(d.spaceLg))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad + d.spaceXs),
                horizontalArrangement = Arrangement.spacedBy(d.spaceSm + 2.dp),
            ) {
                listOf("Watchlist", "Saved", "History").forEachIndexed { i, label ->
                    GenrePill(label, ui.activeTab == i) { vm.setTab(i) }
                }
            }
            Spacer(Modifier.height(d.spaceMd - d.spaceXs))
        }

        // ── Tab content ────────────────────────────────────────────────
        when (ui.activeTab) {
            // ── Watchlist ──────────────────────────────────────────────
            0 -> if (ui.watchlist.isEmpty()) {
                item { EmptyTabHint("Nothing in your watchlist", "Tap + Watchlist on any movie or show\nIt auto-removes once you've watched it") }
            } else {
                items(ui.watchlist, key = { it.tmdbId }) { w ->
                    val type = if (w.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                    LibraryRow(w.title, w.posterPath, "Watchlist") { nav.navigate(com.axio.reelz.ui.Route.Detail.go(w.tmdbId, type)) }
                }
                // Subtle hint about auto-removal
                item {
                    Text(
                        "Temporary save the movies to watch",
                        color = White30,
                        fontSize = d.textXs,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = d.spaceMd, horizontal = d.spaceXl),
                    )
                }
            }

            // ── Saved ──────────────────────────────────────────────────
            1 -> if (ui.saved.isEmpty()) {
                item { EmptyTabHint("No saved videos yet", "Tap Save on any movie or show") }
            } else {
                items(ui.saved, key = { it.tmdbId }) { s ->
                    val type = if (s.mediaType == "TV") MediaType.TV else MediaType.MOVIE
                    LibraryRow(s.title, s.posterPath, "Saved") { nav.navigate(com.axio.reelz.ui.Route.Detail.go(s.tmdbId, type)) }
                }
            }

            // ── History (paginated + skeleton) ─────────────────────────
            2 -> {
                if (ui.historyPage.isEmpty() && ui.historyLoading) {
                    // Initial load — show skeletons
                    items(8) { HistoryRowSkeleton() }
                } else if (ui.historyPage.isEmpty()) {
                    item { EmptyTabHint("No watch history yet", "Start watching something!") }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad + d.spaceXs, vertical = d.spaceXs), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { vm.clearHistory() }) { Text("Clear All", color = Error, fontSize = d.textSm) }
                        }
                    }
                    items(ui.historyPage, key = { it.key }) { h ->
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
                    // Load-more trigger
                    if (!ui.historyAllLoaded) {
                        if (ui.historyLoading) {
                            items(4) { HistoryRowSkeleton() }
                        } else {
                            item {
                                LaunchedEffect(Unit) { vm.loadNextHistoryPage() }
                            }
                        }
                    } else if (ui.historyTotal > 20) {
                        item {
                            Text(
                                "That's all your history (${ui.historyTotal} items)",
                                color = White30,
                                fontSize = d.textXs,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = d.spaceLg),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Shimmer skeleton for a history row ───────────────────────────────────────

@Composable
fun HistoryRowSkeleton() {
    val d = LocalDimensions.current
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by infiniteTransition.animateFloat(
        initialValue = -400f, targetValue = 400f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    val shimmer = Brush.linearGradient(
        colors = listOf(
            White.copy(alpha = 0.04f),
            White.copy(alpha = 0.12f),
            White.copy(alpha = 0.04f),
        ),
        start = Offset(shimmerX, 0f),
        end   = Offset(shimmerX + 300f, 200f),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad + d.spaceXs, vertical = d.spaceMd - d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd + d.spaceXs),
    ) {
        // Poster skeleton
        Box(
            Modifier.width(d.avatarLg - d.spaceXs).height(d.continueCardThumbHeight - d.spaceXs)
                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                .background(BgRaised)
                .background(shimmer)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
            // Title skeleton
            Box(Modifier.fillMaxWidth(0.65f).height(d.spaceLg - d.spaceXxs).clip(RoundedCornerShape(d.spaceSm))
                .background(BgRaised).background(shimmer))
            // Subtitle skeleton
            Box(Modifier.fillMaxWidth(0.35f).height(d.spaceMd).clip(RoundedCornerShape(d.spaceSm))
                .background(BgRaised).background(shimmer))
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).padding(horizontal = d.screenHorizPad + d.spaceXs).background(GlassBorder))
}

// ── Google Sign-In button with loading/lock feedback ─────────────────────────

@Composable
fun GoogleSignInButton(ctx: Context, onSignedIn: (String?, String, String, String?) -> Unit) {
    val d = LocalDimensions.current
    val scope = rememberCoroutineScope()
    val activity = ctx as? android.app.Activity
    var errorMsg   by remember { mutableStateOf<String?>(null) }
    var isLoading  by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(d.radiusPill))
                .background(if (isLoading) BgRaised.copy(alpha = 0.7f) else BgRaised)
                .border(d.borderThin, if (isLoading) Brand.copy(.4f) else GlassBorderMd, RoundedCornerShape(d.radiusPill))
                .clickable(enabled = !isLoading) {
                    if (activity == null) {
                        errorMsg = "Sign-in unavailable. Please restart the app."
                        return@clickable
                    }
                    errorMsg = null
                    isLoading = true
                    scope.launch {
                        val credManager = CredentialManager.create(ctx)
                        val webClientId = "52667585435-dak9bl4krql0qdgv6he8p037u1se06lj.apps.googleusercontent.com"

                        suspend fun handleCredentialResult(result: GetCredentialResponse) {
                            val credential = result.credential
                            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                                val idToken = googleCred.idToken
                                android.util.Log.d("ReelzAuth", "signed in as: id=${googleCred.id} name=${googleCred.displayName} hasToken=${idToken.isNotBlank()}")
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    onSignedIn(idToken, googleCred.displayName ?: "", googleCred.id, googleCred.profilePictureUri?.toString())
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    errorMsg = "Sign-in failed. Unexpected credential type."
                                }
                            }
                        }

                        // Step 1 — One Tap (fast path)
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
                            android.util.Log.w("ReelzAuth", "One Tap failed (${e.message}), falling back")
                        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                            android.util.Log.d("ReelzAuth", "Sign-in cancelled by user")
                            withContext(Dispatchers.Main) { isLoading = false }
                            return@launch
                        }

                        // Step 2 — Full Google Sign-In bottom sheet
                        try {
                            val req = GetCredentialRequest(listOf(
                                GetSignInWithGoogleOption.Builder(webClientId).build()
                            ))
                            val result = credManager.getCredential(activity, req)
                            handleCredentialResult(result)
                        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                            android.util.Log.d("ReelzAuth", "Sign-in cancelled by user")
                            withContext(Dispatchers.Main) { isLoading = false }
                        } catch (e: Exception) {
                            android.util.Log.e("ReelzAuth", "Sign-in fallback error: ${e.javaClass.name}: ${e.message}")
                            withContext(Dispatchers.Main) {
                                isLoading = false
                                errorMsg = "Sign-in failed. Please try again."
                            }
                        }
                    }
                }
                .padding(vertical = d.spaceMd + d.spaceXs),
            contentAlignment = Alignment.Center,
        ) {
            // Show spinner while loading — prevents user re-tapping
            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs)) {
                    CinematicSpinner(size = d.iconMd - 4.dp, color = Brand)
                    Text("Opening Google…", color = White60, fontWeight = FontWeight.SemiBold, fontSize = (d.textMd.value + 1).sp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs)) {
                    Text("G", color = Brand, fontWeight = FontWeight.Black, fontSize = (d.textLg.value + 1).sp)
                    Text("Continue with Google", color = White, fontWeight = FontWeight.SemiBold, fontSize = (d.textMd.value + 1).sp)
                }
            }
        }
        if (errorMsg != null) {
            Spacer(Modifier.height(d.spaceSm))
            Text(errorMsg!!, color = Error, fontSize = d.textSm, textAlign = TextAlign.Center)
        }
    }
}

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
fun StatCard(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val d = LocalDimensions.current
    Box(
        modifier
            .clip(RoundedCornerShape(d.radiusMd + d.spaceXs))
            .background(BgCard)
            .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd + d.spaceXs))
            .padding(vertical = d.spaceLg),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Brand, modifier = Modifier.size(d.iconMd))
            Spacer(Modifier.height(d.spaceSm))
            Text(value, color = White, fontWeight = FontWeight.Black, fontSize = d.textXxl)
            Text(label, color = White60, fontSize = d.textXs)
        }
    }
}

@Composable
fun LibraryRow(title: String, poster: String?, subtitle: String = "", progress: Float = 0f, onClick: () -> Unit) {
    val d = LocalDimensions.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = d.screenHorizPad + d.spaceXs, vertical = d.spaceMd - d.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceMd + d.spaceXs),
    ) {
        Box(
            Modifier.width(d.avatarLg - d.spaceXs).height(d.continueCardThumbHeight - d.spaceXs)
                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                .border(d.borderThin, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs))
                .background(BgRaised)
        ) {
            // Shimmer behind the image while it loads
            val infiniteTransition = rememberInfiniteTransition(label = "imgShimmer")
            val shimmerX by infiniteTransition.animateFloat(
                initialValue = -200f, targetValue = 200f,
                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
                label = "imgShimmerX",
            )
            Box(Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    listOf(White.copy(0.03f), White.copy(0.08f), White.copy(0.03f)),
                    start = Offset(shimmerX, 0f), end = Offset(shimmerX + 200f, 200f),
                )
            ))
            AsyncImage(
                model = "https://image.tmdb.org/t/p/w185$poster",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(d.progressBarHeight).background(White20))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(d.progressBarHeight)
                    .background(Brush.horizontalGradient(listOf(Brand, Brand2))))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontWeight = FontWeight.SemiBold, fontSize = (d.textMd.value + 1).sp, maxLines = 1)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(d.spaceXxs))
                Text(subtitle, color = White60, fontSize = d.textSm)
            }
        }
        Icon(IconChevronRight, null, tint = White40, modifier = Modifier.size(d.iconMd - 2.dp))
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).padding(horizontal = d.screenHorizPad + d.spaceXs).background(GlassBorder))
}

@Composable
fun EmptyTabHint(title: String, subtitle: String) {
    val d = LocalDimensions.current
    Box(Modifier.fillMaxWidth().height(d.spaceXxl * 6.25f), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
            Text(title,    color = White60, fontWeight = FontWeight.SemiBold, fontSize = d.textLg)
            Text(subtitle, color = White40, fontSize = d.textMd, textAlign = TextAlign.Center, lineHeight = (d.textMd.value * 1.45f).sp)
        }
    }
}

@Composable
fun ProfileSectionHeader(text: String) {
    val d = LocalDimensions.current
    Text(
        text, color = White60, fontSize = d.textSm, fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = d.screenHorizPad + d.spaceMd, top = d.spaceXl, bottom = d.spaceSm + d.spaceXxs),
    )
}

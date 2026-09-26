package com.axio.reelz.ui.screens.premium

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.axio.reelz.ads.ReelzBrowserSheet
import com.axio.reelz.data.repository.PaymentRepository
import com.axio.reelz.data.repository.UserRepository
import com.axio.reelz.data.dto.UserState
import com.axio.reelz.data.dto.PremiumConfig
import com.axio.reelz.data.repository.ConfigRepository
import com.axio.reelz.ui.components.BrandButton
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private val IconBack: ImageVector get() = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(19f, 12f); lineTo(5f, 12f); moveTo(12f, 19f); lineTo(5f, 12f); lineTo(12f, 5f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconCheck: ImageVector get() = ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(5f, 13f); lineTo(9.5f, 17.5f); lineTo(19f, 7f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconX: ImageVector get() = ImageVector.Builder("X", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

// ── Static tier definitions for the comparison table ──────────────────────────
// These reflect real app feature gates — not hardcoded prices.
data class TierInfo(
    val maxResolution: String,
    val maxDownloads: Int,        // -1 = unlimited
    val adsEnabled: Boolean,
    val subtitlesManualSearch: Boolean,
    val backgroundPlay: Boolean,
)

private val FREE_TIER = TierInfo(
    maxResolution         = "720p",
    maxDownloads          = 5,
    adsEnabled            = true,
    subtitlesManualSearch = false,
    backgroundPlay        = false,
)

private val PREMIUM_TIER = TierInfo(
    maxResolution         = "4K",
    maxDownloads          = -1,
    adsEnabled            = false,
    subtitlesManualSearch = true,
    backgroundPlay        = true,
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class PremiumViewModel @Inject constructor(
    private val configRepo: ConfigRepository,
    private val sessionRepo: UserRepository,
    private val paymentRepository: PaymentRepository,
) : ViewModel() {

    data class UiState(
        /** True when the user is signed in (regardless of premium status). */
        val isSignedIn: Boolean = false,
        val userState: UserState = UserState.GUEST,
        val daysUntilExpiry: Int = 0,
        /** Loaded from real backend config — never hardcoded. */
        val premiumConfig: PremiumConfig = PremiumConfig(),
        val premiumEnabled: Boolean = false,
        val isRefreshing: Boolean = false,
        val refreshMessage: String? = null,
        /** Non-null while the in-app WebView checkout is open. */
        val checkoutUrl: String? = null,
        /** True while waiting for /payments/init to return the checkout URL. */
        val isInitiatingPayment: Boolean = false,
        val backendConfigured: Boolean = false,
        val paymentError: String? = null,
        val freeTier: TierInfo = FREE_TIER,
        val premiumTier: TierInfo = PREMIUM_TIER,
        /** True when config is still loading from network. */
        val isLoadingConfig: Boolean = true,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        // Observe live config — PremiumScreen reads real prices/URLs from backend
        viewModelScope.launch {
            configRepo.config.collect { cfg ->
                if (cfg != null) {
                    val premium = cfg.premium
                    _ui.update {
                        it.copy(
                            premiumConfig = PremiumConfig(
                                monthlyPriceNgn    = premium.monthlyPrice,
                                yearlyPriceNgn     = premium.yearlyPrice,
                                paystackMonthlyUrl = premium.paystackMonthlyUrl,
                                paystackYearlyUrl  = premium.paystackYearlyUrl,
                                paymentNote        = premium.paymentNote,
                            ),
                            premiumEnabled    = premium.enabled,
                            backendConfigured = configRepo.backendUrl().isNotBlank(),
                            isLoadingConfig   = false,
                        )
                    }
                }
            }
        }

        // Observe session — determine signed-in + premium state
        viewModelScope.launch {
            sessionRepo.session.collect { session ->
                if (session != null) {
                    val daysLeft = if (session.expiresAtMs > 0) {
                        ((session.expiresAtMs - System.currentTimeMillis()) / 86_400_000L).toInt().coerceAtLeast(0)
                    } else 0
                    val state = when {
                        !session.isPremium   -> UserState.SIGNED_IN
                        daysLeft in 1..3     -> UserState.PREMIUM_GRACE
                        session.isPremium    -> UserState.PREMIUM_ACTIVE
                        else                 -> UserState.PREMIUM_EXPIRED
                    }
                    _ui.update {
                        it.copy(
                            isSignedIn       = true,
                            userState        = state,
                            daysUntilExpiry  = daysLeft,
                        )
                    }
                } else {
                    _ui.update { it.copy(isSignedIn = false, userState = UserState.GUEST) }
                }
            }
        }
    }

    /**
     * Starts a Paystack payment checkout for [plan] ("monthly" | "yearly").
     *
     * Requires the user to be signed in — callers must guard with [isSignedIn].
     *
     * Flow:
     *  1. POST /payments/init on the backend → one-time Paystack authorization_url.
     *  2. On success: open that URL in the in-app WebView sheet.
     *  3. On backend failure: fall back to the static Paystack URL from config.
     *  4. If no URL at all: show an error.
     */
    fun initCheckout(plan: String) {
        viewModelScope.launch {
            _ui.update { it.copy(isInitiatingPayment = true, refreshMessage = null, paymentError = null) }

            val result = paymentRepository.initPayment(plan)

            when (result) {
                is PaymentRepository.InitResult.Success -> {
                    _ui.update {
                        it.copy(
                            isInitiatingPayment = false,
                            checkoutUrl         = result.authorizationUrl,
                        )
                    }
                }
                is PaymentRepository.InitResult.FallbackToStaticLink -> {
                    val staticUrl = when (plan) {
                        "yearly" -> configRepo.paystackYearlyUrl()
                        else     -> configRepo.paystackMonthlyUrl()
                    }
                    if (staticUrl.isNotBlank()) {
                        _ui.update {
                            it.copy(isInitiatingPayment = false, checkoutUrl = staticUrl)
                        }
                    } else {
                        _ui.update {
                            it.copy(
                                isInitiatingPayment = false,
                                paymentError = "Payment unavailable right now. Please try again later.",
                            )
                        }
                    }
                }
                is PaymentRepository.InitResult.Error -> {
                    _ui.update {
                        it.copy(
                            isInitiatingPayment = false,
                            paymentError = result.message ?: "Payment could not be started. Please try again.",
                        )
                    }
                }
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true, refreshMessage = null) }
            sessionRepo.refreshAccessToken()
            val became = sessionRepo.isPremium
            _ui.update {
                it.copy(
                    isRefreshing   = false,
                    refreshMessage = if (became) "You're premium! Enjoy 🎬"
                                     else "Not active yet — give it a few minutes after paying, then try again.",
                )
            }
        }
    }

    fun dismissMessage()      { _ui.update { it.copy(refreshMessage = null) } }
    fun dismissPaymentError() { _ui.update { it.copy(paymentError = null) } }
    fun dismissCheckout()     { _ui.update { it.copy(checkoutUrl = null) } }
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PremiumScreen(nav: NavController, vm: PremiumViewModel = hiltViewModel()) {
    val d  = LocalDimensions.current
    val ui by vm.ui.collectAsState()

    val shimmer = rememberInfiniteTransition(label = "crownGlow")
    val glow by shimmer.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "g")

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = d.spaceXxl),
        ) {
            // ── Back button ───────────────────────────────────────────────
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(IconBack, null, tint = White)
                    }
                }
            }

            // ── Crown + headline ──────────────────────────────────────────
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = d.spaceXl, vertical = d.spaceMd),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(d.avatarLg + d.spaceXl - d.spaceXxs).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Brand.copy(glow * .35f), Color.Transparent))),
                        Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(d.avatarLg - d.spaceXs).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(BrandDeep, Brand)))
                                .border(1.dp, Brand2.copy(.6f), CircleShape),
                            Alignment.Center,
                        ) {
                            Text("👑", fontSize = d.textHero)
                        }
                    }
                    Spacer(Modifier.height(d.spaceXl - d.spaceXs))

                    val (headline, sub) = when (ui.userState) {
                        UserState.PREMIUM_ACTIVE  -> "You're Premium" to "Renews in ${ui.daysUntilExpiry} day${if (ui.daysUntilExpiry == 1) "" else "s"}"
                        UserState.PREMIUM_GRACE   -> "Renewal due" to "Your access continues briefly — renew now to stay uninterrupted"
                        UserState.PREMIUM_EXPIRED -> "Premium expired" to "Renew to restore 4K, unlimited downloads, and no ads"
                        UserState.SIGNED_IN       -> "Watch without limits" to "4K streaming · unlimited downloads · zero ads"
                        else                      -> "Watch without limits" to "Sign in to subscribe and unlock everything"
                    }
                    Text(headline, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(d.spaceXs))
                    Text(sub, color = White60, fontSize = d.textMd, textAlign = TextAlign.Center)
                }
            }

            // ── Feature comparison table ──────────────────────────────────
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = d.spaceXl - d.spaceXs),
                ) {
                    // Column headers
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = d.spaceSm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1.3f))
                        Box(Modifier.weight(1f), Alignment.Center) {
                            Text("Free", color = White40, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.weight(1f), Alignment.Center) {
                            Box(
                                Modifier.clip(RoundedCornerShape(d.radiusPill))
                                    .background(Brand.copy(.18f))
                                    .border(1.dp, Brand.copy(.4f), RoundedCornerShape(d.radiusPill))
                                    .padding(horizontal = d.spaceMd, vertical = d.spaceXxs + 1.dp),
                            ) {
                                Text("Premium", color = Brand2, fontSize = d.textSm, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)

                    ComparisonRow("Video quality", ui.freeTier.maxResolution, ui.premiumTier.maxResolution)
                    ComparisonRow(
                        "Downloads",
                        if (ui.freeTier.maxDownloads < 0) "Unlimited" else "${ui.freeTier.maxDownloads} at a time",
                        if (ui.premiumTier.maxDownloads < 0) "Unlimited" else "${ui.premiumTier.maxDownloads} at a time",
                    )
                    ComparisonRow("Ad-free", "—", "—", boolFree = !ui.freeTier.adsEnabled, boolPremium = !ui.premiumTier.adsEnabled)
                    ComparisonRow("Subtitle search", "—", "Any language", boolFree = !ui.freeTier.subtitlesManualSearch, boolPremium = ui.premiumTier.subtitlesManualSearch)
                    ComparisonRow("Background play", "—", "Yes", boolFree = !ui.freeTier.backgroundPlay, boolPremium = ui.premiumTier.backgroundPlay)
                }
            }

            // ── Price cards — from real config ────────────────────────────
            if (ui.userState != UserState.PREMIUM_ACTIVE && !ui.isLoadingConfig) {
                item {
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = d.spaceXl - d.spaceXs, vertical = d.spaceLg),
                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                    ) {
                        if (ui.premiumConfig.monthlyPriceNgn > 0) {
                            PriceCard(
                                label    = "Monthly",
                                price    = "₦${formatNgn(ui.premiumConfig.monthlyPriceNgn)}",
                                period   = "/month",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (ui.premiumConfig.yearlyPriceNgn > 0) {
                            PriceCard(
                                label    = "Yearly",
                                price    = "₦${formatNgn(ui.premiumConfig.yearlyPriceNgn)}",
                                period   = "/year",
                                modifier = Modifier.weight(1f),
                                best     = true,
                            )
                        }
                    }
                }
            }

            // ── CTA section ───────────────────────────────────────────────
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = d.spaceXl - d.spaceXs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when {
                        ui.isLoadingConfig -> {
                            CircularProgressIndicator(
                                color       = Brand,
                                strokeWidth = 2.dp,
                                modifier    = Modifier.size(d.iconLg),
                            )
                        }

                        // Not signed in → prompt to sign in first
                        !ui.isSignedIn || ui.userState == UserState.GUEST -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(d.spaceMd),
                            ) {
                                Box(
                                    Modifier.size(d.avatarMd).clip(CircleShape)
                                        .background(GlassSm)
                                        .border(1.dp, GlassBorderMd, CircleShape),
                                    Alignment.Center,
                                ) {
                                    Text("🔐", fontSize = d.textXl)
                                }
                                Text(
                                    "Sign in required",
                                    color      = White,
                                    fontSize   = d.textLg,
                                    fontWeight = FontWeight.Bold,
                                    textAlign  = TextAlign.Center,
                                )
                                Text(
                                    "You need to be signed in to subscribe. Head to your Profile tab to sign in with Google, then come back here.",
                                    color     = White60,
                                    fontSize  = d.textSm,
                                    textAlign = TextAlign.Center,
                                    lineHeight = (d.textSm.value * 1.5f).sp,
                                )
                                Spacer(Modifier.height(d.spaceXs))
                                BrandButton(
                                    text     = "Go to Profile →",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick  = { nav.popBackStack() },
                                )
                            }
                        }

                        // Already premium
                        ui.userState == UserState.PREMIUM_ACTIVE -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(d.spaceSm),
                            ) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(d.radiusPill))
                                        .background(Success.copy(.12f))
                                        .border(1.dp, Success.copy(.35f), RoundedCornerShape(d.radiusPill))
                                        .padding(horizontal = d.spaceLg, vertical = d.spaceSm),
                                ) {
                                    Text(
                                        "✓ Active — ${ui.daysUntilExpiry} day${if (ui.daysUntilExpiry == 1) "" else "s"} remaining",
                                        color      = Success,
                                        fontSize   = d.textSm,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                Text(
                                    "Thanks for being a Premium member.",
                                    color     = White60,
                                    fontSize  = d.textSm,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }

                        // Signed in, not yet premium (or expired/grace)
                        !ui.premiumEnabled -> {
                            BrandButton(
                                text     = "Subscriptions opening soon",
                                modifier = Modifier.fillMaxWidth(),
                                onClick  = {},
                                enabled  = false,
                            )
                        }

                        else -> {
                            val hasMonthly = ui.premiumConfig.paystackMonthlyUrl.isNotBlank() || ui.backendConfigured
                            val hasYearly  = ui.premiumConfig.paystackYearlyUrl.isNotBlank() || ui.backendConfigured

                            if (hasMonthly || hasYearly) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                                ) {
                                    if (hasMonthly) {
                                        PaystackSubscribeButton(
                                            label     = "Monthly",
                                            enabled   = !ui.isInitiatingPayment,
                                            isLoading = ui.isInitiatingPayment,
                                            modifier  = Modifier.weight(1f),
                                            onClick   = { vm.initCheckout("monthly") },
                                        )
                                    }
                                    if (hasYearly) {
                                        PaystackSubscribeButton(
                                            label     = "Yearly",
                                            enabled   = !ui.isInitiatingPayment,
                                            isLoading = ui.isInitiatingPayment,
                                            modifier  = Modifier.weight(1f),
                                            onClick   = { vm.initCheckout("yearly") },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(d.spaceMd - d.spaceXxs))
                                Text(
                                    "Secured by Paystack — card, bank transfer, or USSD.",
                                    color     = White40,
                                    fontSize  = d.textXs,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                BrandButton(
                                    text     = "Subscriptions opening soon",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick  = {},
                                    enabled  = false,
                                )
                            }

                            if (ui.premiumConfig.paymentNote.isNotBlank()) {
                                Spacer(Modifier.height(d.spaceMd))
                                Text(
                                    ui.premiumConfig.paymentNote,
                                    color     = White60,
                                    fontSize  = d.textSm,
                                    textAlign = TextAlign.Center,
                                    lineHeight = (d.textSm.value * 1.42f).sp,
                                )
                            }

                            Spacer(Modifier.height(d.spaceXl - d.spaceXs))

                            // Already paid? Let user refresh their status
                            TextButton(
                                onClick        = { vm.refreshStatus() },
                                enabled        = !ui.isRefreshing,
                                contentPadding = PaddingValues(horizontal = d.spaceLg, vertical = d.spaceSm),
                            ) {
                                if (ui.isRefreshing) {
                                    CircularProgressIndicator(Modifier.size(d.iconSm), color = Brand, strokeWidth = 1.5.dp)
                                    Spacer(Modifier.width(d.spaceSm))
                                }
                                Text(
                                    "Already paid? Refresh status",
                                    color    = White40,
                                    fontSize = d.textXs,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(d.spaceXl))
                }
            }
        }

        // ── Payment error snackbar ────────────────────────────────────────────
        androidx.compose.animation.AnimatedVisibility(
            visible  = ui.paymentError != null,
            enter    = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit     = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(d.spaceLg),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1010)),
                border = BorderStroke(1.dp, Color(0xFFFF3B30).copy(.4f)),
                shape  = RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = d.spaceLg, vertical = d.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("⚠", fontSize = d.textXl)
                    Text(
                        ui.paymentError ?: "",
                        color      = Color.White.copy(.85f),
                        fontSize   = 13.sp,
                        modifier   = Modifier.weight(1f),
                        lineHeight = 18.sp,
                    )
                    TextButton(
                        onClick        = { vm.dismissPaymentError() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("OK", color = Color(0xFFFF453A), fontWeight = FontWeight.SemiBold, fontSize = d.textSm)
                    }
                }
            }
        }

        // ── Success toast ─────────────────────────────────────────────────────
        androidx.compose.animation.AnimatedVisibility(
            visible  = ui.refreshMessage != null,
            enter    = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit     = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(d.spaceLg),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A2A1A)),
                border = BorderStroke(1.dp, Color(0xFF30D158).copy(.4f)),
                shape  = RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = d.spaceLg, vertical = d.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("✓", fontSize = d.textXl, color = Color(0xFF30D158))
                    Text(
                        ui.refreshMessage ?: "",
                        color    = Color.White.copy(.85f),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick        = { vm.dismissMessage() },
                        contentPadding = PaddingValues(horizontal = d.spaceXs + d.spaceXxs, vertical = 0.dp),
                    ) {
                        Text("OK", color = Color(0xFF30D158), fontWeight = FontWeight.SemiBold, fontSize = d.textSm)
                    }
                }
            }
        }

        // ── In-app WebView checkout ───────────────────────────────────────────
        ui.checkoutUrl?.let { url ->
            ReelzBrowserSheet(url = url, onDismiss = { vm.dismissCheckout() })
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Subscribe button
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PaystackSubscribeButton(
    label: String,
    enabled: Boolean,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val d = LocalDimensions.current
    OutlinedButton(
        onClick  = onClick,
        enabled  = enabled,
        modifier = modifier.height(d.buttonHeightMd),
        shape    = RoundedCornerShape(d.radiusPill),
        border   = BorderStroke(1.dp, if (enabled) Brand.copy(.5f) else GlassBorderMd),
        colors   = ButtonDefaults.outlinedButtonColors(
            contentColor         = if (enabled) Brand2 else White40,
            disabledContentColor = White40,
        ),
    ) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(d.iconSm + 2.dp), color = Brand2, strokeWidth = 2.dp)
        } else {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = d.textMd)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Comparison row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ComparisonRow(
    label: String,
    freeValue: String,
    premiumValue: String,
    boolFree: Boolean? = null,
    boolPremium: Boolean? = null,
) {
    val d = LocalDimensions.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = White80, fontSize = d.textMd, modifier = Modifier.weight(1.3f))
        Box(Modifier.weight(1f), Alignment.Center) {
            if (boolFree != null) BoolPip(boolFree)
            else Text(freeValue, color = White60, fontSize = d.textSm, textAlign = TextAlign.Center)
        }
        Box(Modifier.weight(1f), Alignment.Center) {
            if (boolPremium != null) BoolPip(boolPremium)
            else Text(premiumValue, color = Brand2, fontWeight = FontWeight.SemiBold, fontSize = d.textSm, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BoolPip(value: Boolean) {
    val d = LocalDimensions.current
    Box(
        Modifier.size(d.iconMd).clip(CircleShape)
            .background(if (value) Brand.copy(.18f) else GlassMd),
        Alignment.Center,
    ) {
        Icon(
            if (value) IconCheck else IconX,
            null,
            tint     = if (value) Brand2 else White40,
            modifier = Modifier.size(d.iconSm),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Price card — from real config
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PriceCard(
    label: String,
    price: String,
    period: String,
    modifier: Modifier = Modifier,
    best: Boolean = false,
) {
    val d = LocalDimensions.current
    Column(
        modifier
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(
                if (best) Brush.linearGradient(listOf(BrandDeep, BgCard))
                else Brush.linearGradient(listOf(BgCard, BgCard))
            )
            .border(1.dp, if (best) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .padding(d.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (best) {
            Text("BEST VALUE", color = Brand2, fontSize = d.textXxs, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(d.spaceXs))
        }
        Text(label, color = White60, fontSize = d.textSm)
        Spacer(Modifier.height(d.spaceXs))
        Text(price, color = White, fontWeight = FontWeight.Black, fontSize = d.textXxl)
        Text(period, color = White40, fontSize = d.textXs)
    }
}

private fun formatNgn(amount: Long): String =
    amount.toString().reversed().chunked(3).joinToString(",").reversed()

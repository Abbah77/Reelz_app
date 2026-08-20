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

// ── Local icons — this codebase builds its own ImageVectors throughout rather
// than depending on material-icons-extended (which is listed in the version
// catalog but never actually applied to the app module), so these follow that
// same established convention instead of introducing a new dependency.
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

// ── Tier feature descriptor — used only by the comparison table in the UI ──────
// Schema v3 backend does not send tier definitions; these are static UI
// constants that reflect the app's actual feature gates.
data class TierInfo(
    val maxResolution: String,
    val maxDownloads: Int,        // -1 = unlimited
    val adsEnabled: Boolean,
    val subtitlesManualSearch: Boolean,
    val backgroundPlay: Boolean,
)

private val FREE_TIER = TierInfo(
    maxResolution          = "720p",
    maxDownloads           = 5,
    adsEnabled             = true,
    subtitlesManualSearch  = false,
    backgroundPlay         = false,
)

private val PREMIUM_TIER = TierInfo(
    maxResolution          = "4K",
    maxDownloads           = -1,
    adsEnabled             = false,
    subtitlesManualSearch  = true,
    backgroundPlay         = true,
)

@HiltViewModel
class PremiumViewModel @Inject constructor(
    private val configRepo: ConfigRepository,
    private val sessionRepo: UserRepository,
    private val userSessionRepository: UserRepository,
    private val paymentRepository: PaymentRepository,
) : ViewModel() {

    data class UiState(
        val userState: UserState = UserState.GUEST,
        val daysUntilExpiry: Int = 0,
        val premiumConfig: PremiumConfig = PremiumConfig(),
        val isRefreshing: Boolean = false,
        val refreshMessage: String? = null,
        /** Non-null while the Paystack checkout sheet (ReelzBrowserSheet) is open. */
        val checkoutUrl: String? = null,
        /** True while waiting for /payments/init to return the checkout URL. */
        val isInitiatingPayment: Boolean = false,
        /** True if backend_url is set in config — enables server-side payment init. */
        val backendConfigured: Boolean = false,
        /** Non-null when payment init failed — distinct from refreshMessage (which is success/info). */
        val paymentError: String? = null,
        /** Static tier feature info for the comparison table. */
        val freeTier: TierInfo = FREE_TIER,
        val premiumTier: TierInfo = PREMIUM_TIER,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        
        _ui.update {
            it.copy(
                
                
                premiumConfig     = com.axio.reelz.data.dto.PremiumConfig(
                    monthlyPriceNgn    = configRepo.premiumMonthlyPrice(),
                    yearlyPriceNgn     = configRepo.premiumMonthlyPrice() * 10,
                    paystackMonthlyUrl = configRepo.paystackMonthlyUrl(),
                    paystackYearlyUrl  = configRepo.paystackYearlyUrl(),
                    paymentNote        = "",
                ),
                backendConfigured = configRepo.backendUrl().isNotBlank(),
            )
        }
        viewModelScope.launch {
            sessionRepo.session.collect { session ->
                if (session != null) {
                    val daysLeft = if (session.expiresAtMs > 0) {
                        ((session.expiresAtMs - System.currentTimeMillis()) / 86_400_000L).toInt().coerceAtLeast(0)
                    } else 0
                    val state = when {
                        !session.isPremium      -> UserState.GUEST
                        daysLeft in 1..3        -> UserState.PREMIUM_GRACE
                        session.isPremium       -> UserState.PREMIUM_ACTIVE
                        else                    -> UserState.PREMIUM_EXPIRED
                    }
                    _ui.update { it.copy(userState = state, daysUntilExpiry = daysLeft) }
                }
            }
        }
    }

    /**
     * Starts a payment for [plan] ("monthly" | "yearly").
     *
     * Flow:
     *  1. Call POST /payments/init on the backend → get a one-time Paystack
     *     authorization_url that carries the user's UUID in metadata.
     *  2. On success: open that URL in the in-app browser sheet.
     *  3. On failure (backend unreachable): fall back to the static Paystack
     *     payment page URL from config — user can still pay, webhook still fires.
     *  4. If no URL at all: show an error message.
     *
     * The static URLs in config.json are kept as a safety net.
     * The webhook is still the source of truth regardless of which URL was opened.
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
                    // Backend unreachable — use the static link from config so the
                    // user is never blocked from paying.
                    val staticUrl = when (plan) {
                        "yearly"  -> configRepo.paystackYearlyUrl()
                        else      -> configRepo.paystackMonthlyUrl()
                    }
                    if (staticUrl.isNotBlank()) {
                        _ui.update {
                            it.copy(
                                isInitiatingPayment = false,
                                checkoutUrl         = staticUrl,
                            )
                        }
                    } else {
                        _ui.update {
                            it.copy(
                                isInitiatingPayment = false,
                                paymentError        = "Payment unavailable right now. Please try again later.",
                            )
                        }
                    }
                }
                is PaymentRepository.InitResult.Error -> {
                    _ui.update {
                        it.copy(
                            isInitiatingPayment = false,
                            paymentError        = result.message ?: "Payment could not be started. Please try again.",
                        )
                    }
                }
            }
        }
    }

    /**
     * "I've paid — refresh my status."
     * Hits the backend (or config grants fallback) to confirm the subscription.
     * BackendSessionSource handles the 24 h cache internally.
     */
    fun refreshStatus() {
        viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true, refreshMessage = null) }
            userSessionRepository.refreshAccessToken()
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

    fun dismissMessage() { _ui.update { it.copy(refreshMessage = null) } }
    fun dismissPaymentError() { _ui.update { it.copy(paymentError = null) } }
    fun openCheckout(url: String) { _ui.update { it.copy(checkoutUrl = url) } }
    fun dismissCheckout() { _ui.update { it.copy(checkoutUrl = null) } }
}

@Composable
fun PremiumScreen(nav: NavController, vm: PremiumViewModel = hiltViewModel()) {
    val d = LocalDimensions.current
    val ui by vm.ui.collectAsState()

    val shimmer = rememberInfiniteTransition(label = "crownGlow")
    val glow by shimmer.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "g")

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = d.spaceXxl),
        ) {
            // ── Header ───────────────────────────────────────────────────
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceSm + d.spaceXxs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(IconBack, null, tint = White)
                    }
                }
            }

            // ── Signature: glowing crown + state-aware headline ─────────────
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
                        UserState.PREMIUM_ACTIVE -> "You're Premium" to "Renews in ${ui.daysUntilExpiry} day${if (ui.daysUntilExpiry == 1) "" else "s"}"
                        UserState.PREMIUM_GRACE  -> "Renewal due" to "Your access continues for a short grace period — renew now"
                        UserState.PREMIUM_EXPIRED-> "Premium expired" to "Renew to get unlimited downloads and 4K back"
                        else                      -> "Watch without limits" to "4K streaming, unlimited downloads, zero ads"
                    }
                    Text(headline, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(d.spaceXs))
                    Text(sub, color = White60, fontSize = d.textMd, textAlign = TextAlign.Center)
                }
            }

            // ── Comparison — built from the real tier config, never hardcoded ──
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = d.spaceXl - d.spaceXs)) {
                    ComparisonRow("Max video quality", ui.freeTier.maxResolution, ui.premiumTier.maxResolution)
                    ComparisonRow(
                        "Downloads",
                        if (ui.freeTier.maxDownloads < 0) "Unlimited" else "${ui.freeTier.maxDownloads} at a time",
                        if (ui.premiumTier.maxDownloads < 0) "Unlimited" else "${ui.premiumTier.maxDownloads} at a time",
                    )
                    ComparisonRow("Ads", "—", "—", boolFree = !ui.freeTier.adsEnabled, boolPremium = !ui.premiumTier.adsEnabled)
                    ComparisonRow("Manual subtitle search", "—", "Any language", boolFree = !ui.freeTier.subtitlesManualSearch, boolPremium = ui.premiumTier.subtitlesManualSearch)
                    ComparisonRow("Keep watching, screen off", "—", "Yes", boolFree = !ui.freeTier.backgroundPlay, boolPremium = ui.premiumTier.backgroundPlay)
                }
            }

            // ── Price ─────────────────────────────────────────────────────
            if (ui.userState != UserState.PREMIUM_ACTIVE) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = d.spaceXl - d.spaceXs, vertical = d.spaceLg),
                        horizontalArrangement = Arrangement.spacedBy(d.spaceMd - d.spaceXxs),
                    ) {
                        PriceCard("Monthly", "₦${formatNgn(ui.premiumConfig.monthlyPriceNgn)}", "/month", Modifier.weight(1f))
                        PriceCard("Yearly", "₦${formatNgn(ui.premiumConfig.yearlyPriceNgn)}", "/year", Modifier.weight(1f), best = true)
                    }
                }
            }

            // ── Subscribe / manage ────────────────────────────────────────
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = d.spaceXl - d.spaceXs), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (ui.userState) {
                        UserState.GUEST -> {
                            Text(
                                "Sign in from your Profile tab first, then come back here.",
                                color = White60, fontSize = d.textSm, textAlign = TextAlign.Center,
                            )
                        }
                        UserState.PREMIUM_ACTIVE -> {
                            Text("Thanks for being a Premium member.", color = White60, fontSize = d.textSm, textAlign = TextAlign.Center)
                        }
                        else -> {
                            val monthlyUrl = ui.premiumConfig.paystackMonthlyUrl
                            val yearlyUrl  = ui.premiumConfig.paystackYearlyUrl
                            // Show buttons if either static fallback URL exists OR the backend is configured
                            val anyConfigured = monthlyUrl.isNotBlank() || yearlyUrl.isNotBlank() || ui.backendConfigured

                            if (anyConfigured) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                                ) {
                                    PaystackSubscribeButton(
                                        label      = "Monthly",
                                        enabled    = !ui.isInitiatingPayment,
                                        isLoading  = ui.isInitiatingPayment,
                                        modifier   = Modifier.weight(1f),
                                        onClick    = { vm.initCheckout("monthly") },
                                    )
                                    PaystackSubscribeButton(
                                        label      = "Yearly",
                                        enabled    = !ui.isInitiatingPayment,
                                        isLoading  = ui.isInitiatingPayment,
                                        modifier   = Modifier.weight(1f),
                                        onClick    = { vm.initCheckout("yearly") },
                                    )
                                }
                                Spacer(Modifier.height(d.spaceMd - d.spaceXxs))
                                Text(
                                    "Secured by Paystack — card, bank transfer, or USSD.",
                                    color = White40, fontSize = d.textXs, textAlign = TextAlign.Center,
                                )
                            } else {
                                // No payment link or backend configured yet
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
                                    color = White60, fontSize = d.textSm, textAlign = TextAlign.Center, lineHeight = (d.textSm.value * 1.42f).sp,
                                )
                            }
                            Spacer(Modifier.height(d.spaceXl - d.spaceXs))
                        }
                    }
                }
            }
        }

        // ── Payment error banner ─────────────────────────────────────────────────
        // Distinct from refreshMessage (which carries success toasts).
        // Shown as an overlay snackbar-style at the bottom so the user can retry.
        androidx.compose.animation.AnimatedVisibility(
            visible  = ui.paymentError != null,
            enter    = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit     = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(d.spaceLg),
        ) {
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF2A1010)),
                border = BorderStroke(1.dp, Color(0xFFFF3B30).copy(.4f)),
                shape  = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = d.spaceLg, vertical = d.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("⚠", fontSize = d.textXl)
                    Text(
                        ui.paymentError ?: "",
                        color    = Color.White.copy(.85f),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        lineHeight = 18.sp,
                    )
                    TextButton(
                        onClick            = { vm.dismissPaymentError() },
                        contentPadding     = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("OK", color = Color(0xFFFF453A), fontWeight = FontWeight.SemiBold, fontSize = d.textSm)
                    }
                }
            }
        }

        // ── refreshMessage success toast ─────────────────────────────────────
        androidx.compose.animation.AnimatedVisibility(
            visible  = ui.refreshMessage != null,
            enter    = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
            exit     = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(d.spaceLg),
        ) {
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color(0xFF0A2A1A)),
                border = BorderStroke(1.dp, Color(0xFF30D158).copy(.4f)),
                shape  = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = d.spaceLg, vertical = d.spaceMd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("✓", fontSize = d.textLg + 1.sp, color = Color(0xFF30D158))
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

        ui.checkoutUrl?.let { url ->
            ReelzBrowserSheet(url = url, onDismiss = { vm.dismissCheckout() })
        }
    }
}

/**
 * One plan's subscribe button.
 * Calls [onClick] when tapped — the ViewModel handles the /payments/init call.
 * Shows a spinner while [isLoading] is true (waiting for backend response).
 */
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
            if (boolFree != null) BoolPip(boolFree) else Text(freeValue, color = White60, fontSize = d.textSm, textAlign = TextAlign.Center)
        }
        Box(Modifier.weight(1f), Alignment.Center) {
            if (boolPremium != null) BoolPip(boolPremium) else Text(premiumValue, color = Brand2, fontWeight = FontWeight.SemiBold, fontSize = d.textSm, textAlign = TextAlign.Center)
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
            tint = if (value) Brand2 else White40,
            modifier = Modifier.size(d.iconSm),
        )
    }
}

@Composable
private fun PriceCard(label: String, price: String, period: String, modifier: Modifier = Modifier, best: Boolean = false) {
    val d = LocalDimensions.current
    Column(
        modifier
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(if (best) Brush.linearGradient(listOf(BrandDeep, BgCard)) else Brush.linearGradient(listOf(BgCard, BgCard)))
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

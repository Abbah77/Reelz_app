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
import com.axio.reelz.data.repository.UserSessionRepository
import com.axio.reelz.remoteconfig.PremiumConfig
import com.axio.reelz.remoteconfig.PremiumGate
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import com.axio.reelz.remoteconfig.TierConfig
import com.axio.reelz.remoteconfig.UserState
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

@HiltViewModel
class PremiumViewModel @Inject constructor(
    private val remoteConfig: RemoteConfigRepository,
    private val premiumGate: PremiumGate,
    private val userSessionRepository: UserSessionRepository,
    private val paymentRepository: PaymentRepository,
) : ViewModel() {

    data class UiState(
        val userState: UserState = UserState.GUEST,
        val daysUntilExpiry: Int = 0,
        val freeTier: TierConfig = TierConfig(),
        val premiumTier: TierConfig = TierConfig(),
        val premiumConfig: PremiumConfig = PremiumConfig(),
        val isRefreshing: Boolean = false,
        val refreshMessage: String? = null,
        /** Non-null while the Paystack checkout sheet (ReelzBrowserSheet) is open. */
        val checkoutUrl: String? = null,
        /** True while waiting for /payments/init to return the checkout URL. */
        val isInitiatingPayment: Boolean = false,
        /** True if backend_url is set in config — enables server-side payment init. */
        val backendConfigured: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        val tiers = remoteConfig.tiersConfig()
        _ui.update {
            it.copy(
                freeTier          = tiers.free,
                premiumTier       = tiers.premium,
                premiumConfig     = remoteConfig.premiumConfig(),
                backendConfigured = remoteConfig.backendConfig().backendUrl.isNotBlank(),
            )
        }
        viewModelScope.launch {
            premiumGate.state.collect { state ->
                _ui.update { it.copy(userState = state, daysUntilExpiry = premiumGate.daysUntilExpiry()) }
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
            _ui.update { it.copy(isInitiatingPayment = true, refreshMessage = null) }

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
                        "yearly"  -> remoteConfig.premiumConfig().paystackYearlyUrl
                        else      -> remoteConfig.premiumConfig().paystackMonthlyUrl
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
                                refreshMessage      = "Payment unavailable right now. Please try again later.",
                            )
                        }
                    }
                }
                is PaymentRepository.InitResult.Error -> {
                    _ui.update {
                        it.copy(
                            isInitiatingPayment = false,
                            refreshMessage      = result.message,
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
            userSessionRepository.refreshCurrentSession()
            val became = premiumGate.isPremium()
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
    fun openCheckout(url: String) { _ui.update { it.copy(checkoutUrl = url) } }
    fun dismissCheckout() { _ui.update { it.copy(checkoutUrl = null) } }
}

@Composable
fun PremiumScreen(nav: NavController, vm: PremiumViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()

    val shimmer = rememberInfiniteTransition(label = "crownGlow")
    val glow by shimmer.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "g")

    Box(Modifier.fillMaxSize().background(Bg)) {
        val d = LocalDimensions.current
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
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
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
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
                                        isLoading  = false,
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
                                    color = White60, fontSize = d.textSm, textAlign = TextAlign.Center, lineHeight = 17.sp,
                                )
                            }
                            Spacer(Modifier.height(d.spaceXl - d.spaceXs))
                        }
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
        val d = LocalDimensions.current
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
    Row(
        Modifier.fillMaxWidth().padding(vertical = d.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val d = LocalDimensions.current
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
    Box(
        Modifier.size(d.iconMd).clip(CircleShape)
            .background(if (value) Brand.copy(.18f) else GlassMd),
        Alignment.Center,
    ) {
        val d = LocalDimensions.current
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
    Column(
        modifier
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(if (best) Brush.linearGradient(listOf(BrandDeep, BgCard)) else Brush.linearGradient(listOf(BgCard, BgCard)))
            .border(1.dp, if (best) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .padding(d.spaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val d = LocalDimensions.current
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

package com.reelz.ui.screens.premium

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
import com.reelz.ads.ReelzBrowserSheet
import com.reelz.data.repository.UserSessionRepository
import com.reelz.remoteconfig.PremiumConfig
import com.reelz.remoteconfig.PremiumGate
import com.reelz.remoteconfig.RemoteConfigRepository
import com.reelz.remoteconfig.TierConfig
import com.reelz.remoteconfig.UserState
import com.reelz.ui.components.BrandButton
import com.reelz.ui.theme.*
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
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        val tiers = remoteConfig.tiersConfig()
        _ui.update {
            it.copy(
                freeTier      = tiers.free,
                premiumTier   = tiers.premium,
                premiumConfig = remoteConfig.premiumConfig(),
            )
        }
        viewModelScope.launch {
            premiumGate.state.collect { state ->
                _ui.update { it.copy(userState = state, daysUntilExpiry = premiumGate.daysUntilExpiry()) }
            }
        }
    }

    /**
     * "I've paid — refresh my status." Re-checks manual_grants for the signed-in
     * email. Works the moment you add their row to reelz_config.json on GitHub —
     * no app update needed, since config refreshes independently of app version.
     */
    fun refreshStatus() {
        viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true, refreshMessage = null) }
            userSessionRepository.refreshCurrentSession()
            val became = premiumGate.isPremium()
            _ui.update {
                it.copy(
                    isRefreshing    = false,
                    refreshMessage  = if (became) "You're premium! Enjoy 🎬" else "Not active yet — give it a few minutes after paying, then try again.",
                )
            }
        }
    }

    fun dismissMessage() { _ui.update { it.copy(refreshMessage = null) } }

    /** Opens the Paystack payment link for the tapped plan in the in-app browser sheet. */
    fun openCheckout(url: String) { _ui.update { it.copy(checkoutUrl = url) } }
    fun dismissCheckout() { _ui.update { it.copy(checkoutUrl = null) } }
}

@Composable
fun PremiumScreen(nav: NavController, vm: PremiumViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsState()

    val shimmer = rememberInfiniteTransition(label = "crownGlow")
    val glow by shimmer.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "g")

    Box(Modifier.fillMaxSize().background(Bg)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ── Header ───────────────────────────────────────────────────
            item {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
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
                        Modifier.size(84.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(Brand.copy(glow * .35f), Color.Transparent))),
                        Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(60.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(BrandDeep, Brand)))
                                .border(1.dp, Brand2.copy(.6f), CircleShape),
                            Alignment.Center,
                        ) {
                            Text("👑", fontSize = 26.sp)
                        }
                    }
                    Spacer(Modifier.height(18.dp))

                    val (headline, sub) = when (ui.userState) {
                        UserState.PREMIUM_ACTIVE -> "You're Premium" to "Renews in ${ui.daysUntilExpiry} day${if (ui.daysUntilExpiry == 1) "" else "s"}"
                        UserState.PREMIUM_GRACE  -> "Renewal due" to "Your access continues for a short grace period — renew now"
                        UserState.PREMIUM_EXPIRED-> "Premium expired" to "Renew to get unlimited downloads and 4K back"
                        else                      -> "Watch without limits" to "4K streaming, unlimited downloads, zero ads"
                    }
                    Text(headline, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Text(sub, color = White60, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }

            // ── Comparison — built from the real tier config, never hardcoded ──
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
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
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PriceCard("Monthly", "₦${formatNgn(ui.premiumConfig.monthlyPriceNgn)}", "/month", Modifier.weight(1f))
                        PriceCard("Yearly", "₦${formatNgn(ui.premiumConfig.yearlyPriceNgn)}", "/year", Modifier.weight(1f), best = true)
                    }
                }
            }

            // ── Subscribe / manage ────────────────────────────────────────
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (ui.userState) {
                        UserState.GUEST -> {
                            Text(
                                "Sign in from your Profile tab first, then come back here.",
                                color = White60, fontSize = 12.sp, textAlign = TextAlign.Center,
                            )
                        }
                        UserState.PREMIUM_ACTIVE -> {
                            Text("Thanks for being a Premium member.", color = White60, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                        else -> {
                            val monthlyUrl = ui.premiumConfig.paystackMonthlyUrl
                            val yearlyUrl  = ui.premiumConfig.paystackYearlyUrl
                            val anyConfigured = monthlyUrl.isNotBlank() || yearlyUrl.isNotBlank()

                            if (anyConfigured) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    PaystackSubscribeButton(
                                        label    = "Monthly",
                                        url      = monthlyUrl,
                                        modifier = Modifier.weight(1f),
                                        onCheckout = { checkoutUrl -> vm.openCheckout(checkoutUrl) },
                                    )
                                    PaystackSubscribeButton(
                                        label    = "Yearly",
                                        url      = yearlyUrl,
                                        modifier = Modifier.weight(1f),
                                        onCheckout = { checkoutUrl -> vm.openCheckout(checkoutUrl) },
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Secured by Paystack — card, bank transfer, or USSD.",
                                    color = White40, fontSize = 11.sp, textAlign = TextAlign.Center,
                                )
                            } else {
                                // No payment link configured yet — never show a dead/broken button.
                                BrandButton(
                                    text     = "Subscriptions opening soon",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick  = {},
                                    enabled  = false,
                                )
                            }

                            if (ui.premiumConfig.paymentNote.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    ui.premiumConfig.paymentNote,
                                    color = White60, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp,
                                )
                            }
                            Spacer(Modifier.height(20.dp))

                            // Manual-grant flow: after Paystack confirms the payment (dashboard
                            // or email receipt) and you add their email to manual_grants, this
                            // is the button that picks it up. Independent of which processor is
                            // used — this is the entitlement check, not the checkout itself.
                            OutlinedButton(
                                onClick = { vm.refreshStatus() },
                                enabled = !ui.isRefreshing,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = White),
                            ) {
                                if (ui.isRefreshing) {
                                    CircularProgressIndicator(Modifier.size(16.dp), color = White, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("I've already paid — refresh status")
                            }
                            ui.refreshMessage?.let { msg ->
                                Spacer(Modifier.height(10.dp))
                                Text(msg, color = Brand2, fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
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
 * One plan's subscribe button. Disabled (not hidden) when its Paystack URL is
 * blank, so a half-configured `premium` config never silently drops a plan —
 * the person can still see Monthly/Yearly exist, just not purchasable yet.
 */
@Composable
private fun PaystackSubscribeButton(
    label: String,
    url: String,
    modifier: Modifier = Modifier,
    onCheckout: (String) -> Unit,
) {
    OutlinedButton(
        onClick  = { if (url.isNotBlank()) onCheckout(url) },
        enabled  = url.isNotBlank(),
        modifier = modifier.height(48.dp),
        shape    = RoundedCornerShape(100.dp),
        border   = BorderStroke(1.dp, if (url.isNotBlank()) Brand.copy(.5f) else GlassBorderMd),
        colors   = ButtonDefaults.outlinedButtonColors(
            contentColor         = if (url.isNotBlank()) Brand2 else White40,
            disabledContentColor = White40,
        ),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = White80, fontSize = 13.sp, modifier = Modifier.weight(1.3f))
        Box(Modifier.weight(1f), Alignment.Center) {
            if (boolFree != null) BoolPip(boolFree) else Text(freeValue, color = White60, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Box(Modifier.weight(1f), Alignment.Center) {
            if (boolPremium != null) BoolPip(boolPremium) else Text(premiumValue, color = Brand2, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BoolPip(value: Boolean) {
    Box(
        Modifier.size(20.dp).clip(CircleShape)
            .background(if (value) Brand.copy(.18f) else GlassMd),
        Alignment.Center,
    ) {
        Icon(
            if (value) IconCheck else IconX,
            null,
            tint = if (value) Brand2 else White40,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun PriceCard(label: String, price: String, period: String, modifier: Modifier = Modifier, best: Boolean = false) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (best) Brush.linearGradient(listOf(BrandDeep, BgCard)) else Brush.linearGradient(listOf(BgCard, BgCard)))
            .border(1.dp, if (best) Brand.copy(.5f) else GlassBorderMd, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (best) {
            Text("BEST VALUE", color = Brand2, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(4.dp))
        }
        Text(label, color = White60, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(price, color = White, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Text(period, color = White40, fontSize = 11.sp)
    }
}

private fun formatNgn(amount: Long): String =
    amount.toString().reversed().chunked(3).joinToString(",").reversed()

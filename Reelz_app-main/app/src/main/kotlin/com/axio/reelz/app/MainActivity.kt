package com.axio.reelz.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axio.reelz.BuildConfig
import com.axio.reelz.R
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.data.dto.AppConfigDto
import com.axio.reelz.data.repository.ConfigRepository
import com.axio.reelz.data.repository.ConfigState
import com.axio.reelz.ui.screens.update.MaintenanceScreen
import com.axio.reelz.ui.screens.update.UpdateScreen
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions
import com.axio.reelz.core.workers.ConfigSyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var configRepo: ConfigRepository
    @Inject lateinit var adEngine: AdEngine

    private var isColdStart = true

    override fun onResume() {
        super.onResume()
        // Background config refresh every resume — instant flag updates
        ConfigSyncWorker.schedule(this)
        if (isColdStart) {
            isColdStart = false
            adEngine.showAppOpenIfReady(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val openPremiumOnStart = intent?.getBooleanExtra(EXTRA_OPEN_PREMIUM, false) ?: false

        setContent {
            ReelzTheme {
                val configState by configRepo.state.collectAsStateWithLifecycle()
                val config: AppConfigDto? by configRepo.config.collectAsStateWithLifecycle()

                when (configState) {

                    // Splash still up — Room is loading
                    ConfigState.LOADING -> {
                        Box(Modifier.fillMaxSize().background(Bg))
                    }

                    // First install, no cache — must reach backend
                    ConfigState.ERROR -> {
                        var retryKey by remember { mutableStateOf(0) }
                        LaunchedEffect(retryKey) { configRepo.refresh() }
                        NoConfigScreen(
                            isSyncing = retryKey > 0,
                            errorMsg  = "Could not reach server. Check your connection.",
                            onRetry   = { retryKey++ },
                        )
                    }

                    ConfigState.READY -> {
                        // Maintenance gate
                        if (configRepo.isMaintenanceMode()) {
                            MaintenanceScreen(
                                message = config?.maintenanceMessage ?: "Down for maintenance.",
                                onRetry = { ConfigSyncWorker.schedule(this@MainActivity) },
                            )
                            return@ReelzTheme
                        }

                        // Force / optional update gate
                        val current  = BuildConfig.VERSION_CODE
                        val minVer   = configRepo.minAppVersion()
                        val latest   = configRepo.latestAppVersion()
                        val apkUrl   = configRepo.latestApkUrl()
                        val force    = current < minVer && apkUrl.isNotBlank()
                        var skipOpt  by remember { mutableStateOf(false) }
                        val optional = !force && current < latest && apkUrl.isNotBlank() && !skipOpt

                        if (force) {
                            UpdateScreen(
                                latestVersion = "v$latest",
                                downloadUrl   = apkUrl,
                                changelog     = "",
                                forceUpdate   = true,
                            )
                            return@ReelzTheme
                        }
                        if (optional) {
                            UpdateScreen(
                                latestVersion = "v$latest",
                                downloadUrl   = apkUrl,
                                changelog     = "",
                                forceUpdate   = false,
                                onSkip        = { skipOpt = true },
                            )
                            return@ReelzTheme
                        }

                        // Normal app flow
                        var showPoweredBy by remember { mutableStateOf(true) }
                        if (showPoweredBy) {
                            PoweredByScreen(onFinished = { showPoweredBy = false })
                        } else {
                            AppNavigation(adEngine = adEngine, openPremiumOnStart = openPremiumOnStart)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PREMIUM = "com.axio.reelz.EXTRA_OPEN_PREMIUM"
    }
}

// ── No-config screen ──────────────────────────────────────────────────────────

@Composable
fun NoConfigScreen(isSyncing: Boolean, errorMsg: String?, onRetry: () -> Unit) {
    val d = LocalDimensions.current
    Box(
        Modifier.fillMaxSize().background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = d.spaceXxl + d.spaceLg).fillMaxWidth(),
        ) {
            Text("📡", fontSize = (d.textHero.value + 30).sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(d.spaceXl))
            Text(
                "Connect to get started", color = Color.White,
                fontSize = d.textXxl, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(d.spaceMd - d.spaceXxs))
            Text(
                "Reelz needs a one-time internet connection to set up.",
                color = Color.White.copy(alpha = 0.6f), fontSize = d.textMd,
                textAlign = TextAlign.Center, lineHeight = (d.textMd.value * 1.6f).sp,
            )
            if (errorMsg != null) {
                Spacer(Modifier.height(d.spaceLg))
                Text(errorMsg, color = Color(0xFFFF6B6B), fontSize = d.textMd, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(d.buttonHeightSm - d.spaceXxs))
            Button(
                onClick = { if (!isSyncing) onRetry() },
                shape   = RoundedCornerShape(d.radiusMd - d.spaceXxs),
                colors  = ButtonDefaults.buttonColors(containerColor = Brand),
                modifier = Modifier.fillMaxWidth().height(d.avatarMd + d.spaceSm),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(d.iconMd))
                } else {
                    Text("Try again", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = d.textLg)
                }
            }
        }
    }
}

// ── Powered-by splash ─────────────────────────────────────────────────────────

@Composable
fun PoweredByScreen(onFinished: () -> Unit) {
    val d = LocalDimensions.current
    val logoAlpha   = remember { Animatable(0f) }
    val logoScale   = remember { Animatable(0.72f) }
    val textAlpha   = remember { Animatable(0f) }
    val screenAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }
        launch { logoScale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f)) }
        delay(300)
        textAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        delay(950)
        screenAlpha.animateTo(0f, tween(450, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        Modifier.fillMaxSize().alpha(screenAlpha.value).background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(
                painter = painterResource(R.drawable.ic_company_logo),
                contentDescription = "Company logo",
                modifier = Modifier.size(d.avatarLg + d.spaceMd + d.spaceXxs).alpha(logoAlpha.value).scale(logoScale.value),
            )
            Spacer(Modifier.height(d.spaceXl - d.spaceXs))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(textAlpha.value)) {
                Text("from", color = Color.White.copy(alpha = 0.45f), fontSize = d.textXs, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(d.spaceXs))
                val inf   = rememberInfiniteTransition(label = "shimmer")
                val shimX by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), "sx")
                Text(
                    "AXIO STUDIO",
                    style = androidx.compose.ui.text.TextStyle(
                        brush = Brush.linearGradient(colorStops = arrayOf(0f to Brand2, shimX to Color(0xFFB3D9FF), 1f to Brand)),
                        fontSize = d.textLg, fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp, textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

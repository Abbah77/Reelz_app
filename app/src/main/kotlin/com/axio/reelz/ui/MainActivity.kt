package com.axio.reelz.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
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
import com.axio.reelz.remoteconfig.ConfigReadiness
import com.axio.reelz.ads.AdEngine
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import com.axio.reelz.remoteconfig.ConfigSyncWorker
import com.axio.reelz.remoteconfig.SyncState
import com.axio.reelz.ui.screens.update.MaintenanceScreen
import com.axio.reelz.ui.screens.update.UpdateScreen
import com.axio.reelz.update.ApkUpdateManager
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var remoteConfig: RemoteConfigRepository
    @Inject lateinit var adEngine: AdEngine
    @Inject lateinit var apkUpdateManager: ApkUpdateManager

    // Track cold start — App Open ad fires ONCE on cold start, not every resume
    private var isColdStart = true

    override fun onPause() {
        super.onPause()
        apkUpdateManager.detachActivity()
    }

    override fun onResume() {
        super.onResume()
        apkUpdateManager.attachActivity(this)
        // Sync config every time user opens/returns to app so updates are instant.
        remoteConfig.syncInBackground()
        // Show App Open ad on cold start only (fires during the existing splash gap)
        if (isColdStart) {
            isColdStart = false
            adEngine.showAppOpenIfReady(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Set by PlayerActivity when a free user taps "Upgrade to Premium" from
        // the subtitle drawer — see PLAYER_OPEN_PREMIUM_EXTRA. Read once; not
        // re-checked across recreation so it never re-fires on rotation etc.
        val openPremiumOnStart = intent?.getBooleanExtra(EXTRA_OPEN_PREMIUM, false) ?: false

        setContent {
            ReelzTheme {
                val readiness by remoteConfig.readiness.collectAsStateWithLifecycle()
                val config    by remoteConfig.config.collectAsStateWithLifecycle()

                when (readiness) {

                    // ── Still reading DataStore — show nothing (splash is still up) ──
                    ConfigReadiness.LOADING -> {
                        Box(Modifier.fillMaxSize().background(Bg))
                    }

                    // ── No local config — first install, needs internet ──────────────
                    ConfigReadiness.NO_CONFIG -> {
                        var isSyncing by remember { mutableStateOf(true) }
                        var errorMsg  by remember { mutableStateOf<String?>(null) }
                        var retryKey  by remember { mutableStateOf(0) }
                        val syncState by remoteConfig.syncState.collectAsStateWithLifecycle()

                        // Auto-trigger sync on first show, and re-trigger on each retry.
                        LaunchedEffect(retryKey) {
                            isSyncing = true
                            errorMsg  = null
                            remoteConfig.syncInBackground()
                        }

                        // React to sync result.
                        LaunchedEffect(syncState) {
                            when (syncState) {
                                is SyncState.Syncing -> { isSyncing = true }
                                is SyncState.Success -> { isSyncing = false }
                                is SyncState.Error   -> {
                                    errorMsg  = (syncState as SyncState.Error).message
                                    isSyncing = false
                                }
                                else -> {}
                            }
                        }

                        NoConfigScreen(
                            isSyncing = isSyncing,
                            errorMsg  = errorMsg,
                            onRetry   = { retryKey++ },
                        )
                    }

                    // ── Have config — run the normal app gates ──────────────────────
                    ConfigReadiness.READY -> {
                        val flags = config?.featureFlags
                        val meta  = config?.meta

                        // Maintenance gate
                        if (flags?.forceMaintenance == true) {
                            MaintenanceScreen(
                                message = flags.maintenanceMessage,
                                onRetry = { ConfigSyncWorker.syncNow(this@MainActivity) },
                            )
                            return@ReelzTheme
                        }

                        // Force / optional update gate
                        val currentVersionCode = BuildConfig.VERSION_CODE
                        val minRequired        = meta?.minAppVersion ?: 0
                        val latestVersion      = meta?.latestAppVersion ?: 0
                        val downloadUrl        = meta?.latestApkUrl ?: ""
                        val changelog          = meta?.changelog ?: ""

                        val forceUpdate  = currentVersionCode < minRequired && downloadUrl.isNotBlank()
                        var skipOptional by remember { mutableStateOf(false) }
                        val optionalUpdate = !forceUpdate &&
                            currentVersionCode < latestVersion &&
                            downloadUrl.isNotBlank() &&
                            !skipOptional

                        if (forceUpdate) {
                            UpdateScreen(
                                latestVersion = "v$latestVersion",
                                downloadUrl   = downloadUrl,
                                changelog     = changelog,
                                forceUpdate   = true,
                                updateManager = apkUpdateManager,
                            )
                            return@ReelzTheme
                        }

                        if (optionalUpdate) {
                            UpdateScreen(
                                latestVersion = "v$latestVersion",
                                downloadUrl   = downloadUrl,
                                changelog     = changelog,
                                forceUpdate   = false,
                                onSkip        = { skipOptional = true },
                                updateManager = apkUpdateManager,
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
        /** Set true by PlayerActivity to land directly on the Premium screen after relaunch. */
        const val EXTRA_OPEN_PREMIUM = "com.axio.reelz.EXTRA_OPEN_PREMIUM"
    }
}

// ── No-config screen (first install, offline) ─────────────────────────────────

@Composable
fun NoConfigScreen(
    isSyncing: Boolean,
    errorMsg: String?,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = 40.dp)
                .fillMaxWidth(),
        ) {
            Text(text = "📡", fontSize = 56.sp, textAlign = TextAlign.Center)

            Spacer(Modifier.height(24.dp))

            Text(
                text       = "Connect to get started",
                color      = Color.White,
                fontSize   = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text       = "Reelz needs a one-time internet connection to set up. After that, it works great even offline.",
                color      = Color.White.copy(alpha = 0.6f),
                fontSize   = 14.sp,
                textAlign  = TextAlign.Center,
                lineHeight = 21.sp,
            )

            // Error message
            if (errorMsg != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text      = errorMsg,
                    color     = Color(0xFFFF6B6B),
                    fontSize  = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(36.dp))

            Button(
                onClick  = { if (!isSyncing) onRetry() },
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Brand),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        color            = Color.White,
                        strokeWidth      = 2.dp,
                        modifier         = Modifier.size(20.dp),
                    )
                } else {
                    Text(
                        "Try again",
                        color      = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 15.sp,
                    )
                }
            }
        }
    }
}

// ── "Powered by" splash ───────────────────────────────────────────────────────

@Composable
fun PoweredByScreen(onFinished: () -> Unit) {
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
        Modifier
            .fillMaxSize()
            .alpha(screenAlpha.value)
            .background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter            = painterResource(R.drawable.ic_company_logo),
                contentDescription = "Company logo",
                modifier           = Modifier
                    .size(72.dp)
                    .alpha(logoAlpha.value)
                    .scale(logoScale.value),
            )
            Spacer(Modifier.height(20.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.alpha(textAlpha.value),
            ) {
                Text(
                    "from",
                    color         = Color.White.copy(alpha = 0.45f),
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.Normal,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                val inf   = rememberInfiniteTransition(label = "shimmer")
                val shimX by inf.animateFloat(
                    0f, 1f,
                    infiniteRepeatable(tween(2200, easing = LinearEasing)),
                    "sx",
                )
                Text(
                    "AXIO STUDIO",
                    style = androidx.compose.ui.text.TextStyle(
                        brush = Brush.linearGradient(
                            colorStops = arrayOf(
                                0f    to Brand2,
                                shimX to Color(0xFFB3D9FF),
                                1f    to Brand,
                            )
                        ),
                        fontSize      = 15.sp,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 3.sp,
                        textAlign     = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

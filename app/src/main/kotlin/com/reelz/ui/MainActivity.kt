package com.reelz.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.reelz.BuildConfig
import com.reelz.R
import com.reelz.remoteconfig.RemoteConfigRepository
import com.reelz.remoteconfig.ConfigSyncWorker
import com.reelz.ui.screens.update.MaintenanceScreen
import com.reelz.ui.screens.update.UpdateScreen
import com.reelz.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var remoteConfig: RemoteConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ReelzTheme {
                val config by remoteConfig.config.collectAsStateWithLifecycle()
                val flags   = config?.featureFlags
                val meta    = config?.meta

                // ── Maintenance gate (highest priority) ──────────────────────
                if (flags?.forceMaintenance == true) {
                    MaintenanceScreen(
                        message = flags.maintenanceMessage,
                        onRetry = {
                            // Trigger a fresh sync — will update flags if maintenance lifted
                            ConfigSyncWorker.syncNow(this@MainActivity)
                        },
                    )
                    return@ReelzTheme
                }

                // ── Force update gate ────────────────────────────────────────
                val currentVersionCode = BuildConfig.VERSION_CODE
                val minRequired        = meta?.minAppVersion ?: 0
                val latestVersion      = meta?.latestAppVersion ?: 0
                val downloadUrl        = meta?.latestApkUrl ?: ""
                val changelog          = meta?.changelog ?: ""

                val forceUpdate   = currentVersionCode < minRequired && downloadUrl.isNotBlank()
                var skipOptional  by remember { mutableStateOf(false) }
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
                    )
                    return@ReelzTheme
                }

                // ── Normal app flow ───────────────────────────────────────────
                var showPoweredBy by remember { mutableStateOf(true) }
                if (showPoweredBy) {
                    PoweredByScreen(onFinished = { showPoweredBy = false })
                } else {
                    AppNavigation()
                }
            }
        }
    }
}

// ── "Powered by" splash screen ────────────────────────────────────────────────
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
                painter           = painterResource(R.drawable.ic_company_logo),
                contentDescription = "Company logo",
                modifier          = Modifier
                    .size(72.dp)
                    .alpha(logoAlpha.value)
                    .scale(logoScale.value),
            )

            Spacer(Modifier.height(20.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.alpha(textAlpha.value),
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

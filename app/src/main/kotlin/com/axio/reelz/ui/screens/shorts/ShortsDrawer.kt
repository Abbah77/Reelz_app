package com.axio.reelz.ui.screens.shorts

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.axio.reelz.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
// ShortsDrawer — Left-side quick-settings drawer for the Shorts screen.
//
// Design:
//   • Slides in from the left (offsetX animation).
//   • Width: min(screenWidth * 0.60, 280.dp) — half of a medium phone, capped
//     so it never fills a tablet. Respects Dimensions.kt breakpoints.
//   • Height: full screen (fillMaxHeight) — like the player drawers.
//   • Scrim behind it is translucent + tappable to dismiss.
//   • Toggle handle at top-left (visible at all times) opens / closes drawer.
//
// Contents:
//   • Auto-scroll toggle (default OFF — per spec)
//   • Video quality preference chips
//   • Show captions toggle
//   • Mute by default toggle
//   • Loop short toggle
//   • Feedback / Report entry (navigates to FeedbackScreen.SHORTS)
//
// Usage in ShortsScreen:
//   var showDrawer by remember { mutableStateOf(false) }
//   Box(Modifier.fillMaxSize()) {
//       ShortsContent(…)
//       ShortsDrawer(
//           visible     = showDrawer,
//           onDismiss   = { showDrawer = false },
//           onFeedback  = { requestId -> nav.navigate("feedback/shorts?request_id=$requestId") },
//           currentRequestId = currentRequestId,
//       )
//       // Drawer toggle button (top-left)
//       ShortsDrawerToggle(onClick = { showDrawer = !showDrawer })
//   }
// ─────────────────────────────────────────────────────────────────────────────

// ── Icon vectors ──────────────────────────────────────────────────────────────

private val IconSettings: ImageVector get() = ImageVector.Builder("Settings", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 15f); arcTo(3f, 3f, 0f, false, true, 12f, 9f); arcTo(3f, 3f, 0f, false, true, 12f, 15f); close()
        moveTo(19.4f, 15f); arcTo(1.65f, 1.65f, 0f, false, false, 0.33f, 1.82f); lineTo(19.73f, 14.5f)
        arcTo(1.65f, 1.65f, 0f, false, false, 21.38f, 12f); arcTo(1.65f, 1.65f, 0f, false, false, 19.73f, 9.5f)
        lineTo(19.4f, 9f); arcTo(1.65f, 1.65f, 0f, false, false, 18.9f, 6.82f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.6f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

private val IconFlag: ImageVector get() = ImageVector.Builder("Flag", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(4f, 15f)
        curveTo(4f, 15f, 5f, 14f, 8f, 14f); curveTo(11f, 14f, 13f, 16f, 16f, 16f)
        curveTo(19f, 16f, 20f, 15f, 20f, 15f); lineTo(20f, 5f)
        curveTo(20f, 5f, 19f, 6f, 16f, 6f); curveTo(13f, 6f, 11f, 4f, 8f, 4f)
        curveTo(5f, 4f, 4f, 5f, 4f, 5f); lineTo(4f, 21f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

private val IconChevronRight: ImageVector get() = ImageVector.Builder("ChevRight", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(9f, 18f); lineTo(15f, 12f); lineTo(9f, 6f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

private val IconAutoScroll: ImageVector get() = ImageVector.Builder("AutoScroll", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(8f, 15f); lineTo(12f, 19f); lineTo(16f, 15f)
        moveTo(8f, 9f); lineTo(12f, 5f); lineTo(16f, 9f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

// ── ShortsDrawer ──────────────────────────────────────────────────────────────

data class ShortsSettings(
    val autoScroll:     Boolean = false,     // Auto-advance to next when finished — default OFF
    val muteByDefault:  Boolean = false,
    val showCaptions:   Boolean = false,
    val loopShort:      Boolean = false,
    val qualityPref:    String  = "auto",    // "auto" | "360p" | "720p"
)

@Composable
fun ShortsDrawer(
    visible:          Boolean,
    settings:         ShortsSettings,
    onDismiss:        () -> Unit,
    onSettingsChange: (ShortsSettings) -> Unit,
    onFeedback:       (requestId: String?) -> Unit,
    currentRequestId: String? = null,
) {
    val d           = LocalDimensions.current
    val config      = LocalConfiguration.current
    val screenW     = config.screenWidthDp.dp
    val isTablet    = d.isTablet

    // Drawer width: 60% of screen on phone, max 280dp; never more than half on tablet
    val drawerWidth = minOf(
        if (isTablet) screenW * 0.38f else screenW * 0.62f,
        if (isTablet) 320.dp else 280.dp,
    )

    val offsetX by animateDpAsState(
        targetValue = if (visible) 0.dp else -drawerWidth,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness    = Spring.StiffnessMediumLow,
        ),
        label = "drawerOffset",
    )

    val scrimAlpha by animateFloatAsState(
        targetValue = if (visible) 0.5f else 0f,
        animationSpec = tween(250),
        label = "scrimAlpha",
    )

    Box(Modifier.fillMaxSize()) {
        // Scrim
        if (scrimAlpha > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )
        }

        // Drawer panel
        Box(
            Modifier
                .fillMaxHeight()
                .width(drawerWidth)
                .offset(x = offsetX)
                .background(Color(0xFF0D0D16))
                .border(width = 1.dp, color = Color.White.copy(alpha = 0.06f), shape = RectangleShape)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 20.dp),
            ) {

                // ── Header ────────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        IconSettings,
                        contentDescription = null,
                        tint     = Color(0xFF7C6EFF),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Quick Settings",
                        fontSize   = d.textLg,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White,
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ── Auto-scroll ───────────────────────────────────────────────
                DrawerToggleRow(
                    label    = "Auto-scroll",
                    subtitle = "Advance to next short automatically",
                    checked  = settings.autoScroll,
                    tint     = Color(0xFF7C6EFF),
                    onToggle = { onSettingsChange(settings.copy(autoScroll = it)) },
                )

                DrawerDivider()

                // ── Mute by default ───────────────────────────────────────────
                DrawerToggleRow(
                    label    = "Mute by default",
                    subtitle = "Start each short muted",
                    checked  = settings.muteByDefault,
                    onToggle = { onSettingsChange(settings.copy(muteByDefault = it)) },
                )

                DrawerDivider()

                // ── Show captions ─────────────────────────────────────────────
                DrawerToggleRow(
                    label    = "Show captions",
                    subtitle = "Auto-generated subtitles when available",
                    checked  = settings.showCaptions,
                    onToggle = { onSettingsChange(settings.copy(showCaptions = it)) },
                )

                DrawerDivider()

                // ── Loop short ────────────────────────────────────────────────
                DrawerToggleRow(
                    label    = "Loop short",
                    subtitle = "Repeat the current short",
                    checked  = settings.loopShort,
                    onToggle = { onSettingsChange(settings.copy(loopShort = it)) },
                )

                DrawerDivider()

                // ── Quality preference ────────────────────────────────────────
                Text(
                    "Quality preference",
                    fontSize   = d.textSm,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White.copy(alpha = 0.85f),
                    modifier   = Modifier.padding(vertical = 4.dp),
                )
                Text(
                    "Applied to new shorts — not the current one",
                    fontSize = d.textXs,
                    color    = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Auto", "360p" to "360p", "720p" to "720p").forEach { (key, label) ->
                        val selected = settings.qualityPref == key
                        Box(
                            Modifier
                                .weight(1f)
                                .background(
                                    if (selected) Color(0xFF7C6EFF) else Color(0xFF1A1A28),
                                    RoundedCornerShape(8.dp),
                                )
                                .border(1.dp,
                                    if (selected) Color(0xFF7C6EFF) else Color(0xFF2A2A3C),
                                    RoundedCornerShape(8.dp))
                                .clickable { onSettingsChange(settings.copy(qualityPref = key)) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                fontSize   = d.textXs,
                                color      = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                DrawerDivider()

                // ── Feedback / Report ─────────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onFeedback(currentRequestId) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(Color(0xFFE74C3C).copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(IconFlag, contentDescription = null, tint = Color(0xFFE74C3C), modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Report / Feedback", fontSize = d.textSm, fontWeight = FontWeight.SemiBold, color = Color.White)
                        Text("Inappropriate content, issues, etc.", fontSize = d.textXs, color = Color.White.copy(alpha = 0.35f))
                    }
                    Icon(IconChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun DrawerToggleRow(
    label:    String,
    subtitle: String,
    checked:  Boolean,
    tint:     Color = Color(0xFF7C6EFF),
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(subtitle, fontSize = 11.sp, color = Color.White.copy(alpha = 0.35f))
        }
        Spacer(Modifier.width(12.dp))
        // Minimal toggle
        val thumbPos by animateFloatAsState(if (checked) 1f else 0f, label = "thumb")
        Box(
            Modifier
                .width(38.dp)
                .height(20.dp)
                .background(
                    if (checked) tint else Color(0xFF2A2A3C),
                    RoundedCornerShape(100.dp),
                )
                .clickable { onToggle(!checked) }
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .size(16.dp)
                    .offset(x = (thumbPos * 18).dp)
                    .background(Color.White, CircleShape)
            )
        }
    }
}

@Composable
private fun DrawerDivider() {
    HorizontalDivider(
        color     = Color.White.copy(alpha = 0.05f),
        thickness = 1.dp,
        modifier  = Modifier.padding(vertical = 2.dp),
    )
}

// ── Toggle button (shown at top-left of ShortsScreen always) ─────────────────

@Composable
fun ShortsDrawerToggle(
    onClick:  () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .padding(start = 12.dp, top = 12.dp)
            .size(38.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            IconSettings,
            contentDescription = "Quick settings",
            tint     = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

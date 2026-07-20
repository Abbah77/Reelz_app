package com.axio.reelz.ui.screens.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.navigation.NavController
import com.axio.reelz.ui.theme.*

// ── Icon vectors ──────────────────────────────────────────────────────────────

private val IconBack: ImageVector get() = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

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

private val IconTrash: ImageVector get() = ImageVector.Builder("Trash", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f, 6f); lineTo(21f, 6f)
        moveTo(8f, 6f); lineTo(8f, 4f); lineTo(16f, 4f); lineTo(16f, 6f)
        moveTo(19f, 6f); lineTo(18f, 20f); arcTo(1f, 1f, 0f, false, true, 17f, 21f)
        lineTo(7f, 21f); arcTo(1f, 1f, 0f, false, true, 6f, 20f); lineTo(5f, 6f)
        moveTo(10f, 11f); lineTo(10f, 17f); moveTo(14f, 11f); lineTo(14f, 17f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconEye: ImageVector get() = ImageVector.Builder("Eye", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(1f, 12f); curveTo(1f, 12f, 5f, 4f, 12f, 4f); curveTo(19f, 4f, 23f, 12f, 23f, 12f)
        curveTo(23f, 12f, 19f, 20f, 12f, 20f); curveTo(5f, 20f, 1f, 12f, 1f, 12f); close()
        moveTo(12f, 9f); arcTo(3f, 3f, 0f, false, false, 12f, 15f)
        arcTo(3f, 3f, 0f, false, false, 12f, 9f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconMoon: ImageVector get() = ImageVector.Builder("Moon", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(21f, 12.79f)
        arcTo(9f, 9f, 0f, false, true, 11.21f, 3f)
        arcTo(7f, 7f, 0f, false, false, 12f, 21f)
        arcTo(7f, 7f, 0f, false, false, 21f, 12.79f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val White30 = Color(0x4DF8F4EE)

// ── Settings Screen ───────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(nav: NavController) {
    val d = LocalDimensions.current
    var showStorageDialog       by remember { mutableStateOf(false) }
    var showPrivacyDialog       by remember { mutableStateOf(false) }
    var showNotificationsDialog by remember { mutableStateOf(false) }
    var showAboutDialog         by remember { mutableStateOf(false) }
    var showWatchlistInfoDialog by remember { mutableStateOf(false) }
    var showHistoryInfoDialog   by remember { mutableStateOf(false) }

    // Dialogs
    if (showStorageDialog) {
        ReelzDialog(
            title = "Storage Usage",
            onDismiss = { showStorageDialog = false },
        ) {
            Text(
                "Reelz is designed to be ultra-lightweight:\n\n" +
                "• Watchlist, Saved & History — only store a tiny ID (tmdbId) + title. No images saved. 1,000 entries ≈ <1 MB.\n\n" +
                "• Thumbnails & posters are fetched on demand and cached briefly by the system. They auto-clear when storage is low.\n\n" +
                "• Downloaded videos — stored in your app's private folder. Delete from the Downloads tab.\n\n" +
                "History is capped at 500 entries. Oldest are trimmed automatically.",
                color = White60, fontSize = d.textMd, lineHeight = (d.textMd.value * 1.5f).sp,
            )
        }
    }

    if (showPrivacyDialog) {
        ReelzDialog(
            title = "Privacy & Security",
            onDismiss = { showPrivacyDialog = false },
        ) {
            Text(
                "Your privacy matters:\n\n" +
                "• Sign-in is handled securely via Google.\n\n" +
                "• Watchlist, Saved & History sync to local.\n\n" +
                "• Premium status is verified.\n\n" +
                "• We don't collect or process any kind of information.",
                color = White60, fontSize = d.textMd, lineHeight = (d.textMd.value * 1.5f).sp,
            )
        }
    }

    if (showNotificationsDialog) {
        ReelzDialog(
            title = "Notifications",
            onDismiss = { showNotificationsDialog = false },
        ) {
            Text(
                "Reelz sends notifications for:\n\n" +
                "• Download completions — when your video is ready to watch offline.\n\n" +
                "• Premium renewal reminders — a few days before your plan renews.\n\n" +
                "Manage permissions in: Settings → Apps → Reelz → Notifications.",
                color = White60, fontSize = d.textMd, lineHeight = (d.textMd.value * 1.5f).sp,
            )
        }
    }

    if (showAboutDialog) {
        ReelzDialog(
            title = "About Reelz",
            onDismiss = { showAboutDialog = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs)) {
                Text("Reelz", color = Brand, fontWeight = FontWeight.Black, fontSize = d.textXl)
                Text(
                    "Your personal cinema — stream movies and TV shows, download for offline viewing, and discover what to watch next.\n\nBuilt with ❤️ using Kotlin & Jetpack Compose.",
                    color = White60, fontSize = d.textMd, lineHeight = (d.textMd.value * 1.5f).sp,
                )
            }
        }
    }

    if (showWatchlistInfoDialog) {
        ReelzDialog(
            title = "How Watchlist Works",
            onDismiss = { showWatchlistInfoDialog = false },
        ) {
            Text(
                "Think of Watchlist like a shopping cart — add what you plan to watch, then it clears itself once you've seen it.\n\n" +
                "• Add a movie or show you want to watch later.\n\n" +
                "• When you've watched 90% or more, it automatically disappears from the list. The last 10% is often just credits or trailers anyway.\n\n" +
                "• For TV shows, each episode is tracked — finish an episode and it removes that title from your watchlist.\n\n" +
                "Your watch history always keeps a record separately.",
                color = White60, fontSize = d.textMd, lineHeight = (d.textMd.value * 1.5f).sp,
            )
        }
    }

    if (showHistoryInfoDialog) {
        ReelzDialog(
            title = "How History Works",
            onDismiss = { showHistoryInfoDialog = false },
        ) {
            Text(
                "History records every title you've watched — even just a few minutes.\n\n" +
                "• Capped at 500 entries. Oldest are removed automatically, so it never fills your storage.\n\n" +
                "• Only tiny IDs are stored — not images or metadata. 500 history entries take less than 1 MB.\n\n" +
                "• Thumbnails and details are fetched fresh when you scroll, with smooth skeleton loading.\n\n" +
                "• You can clear your entire history at any time from the History tab.",
                color = White60, fontSize = d.textMd, lineHeight = (d.textMd.value * 1.5f).sp,
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        // ── Top bar ────────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(IconBack, "Back", tint = White, modifier = Modifier.size(d.iconMd))
            }
            Spacer(Modifier.width(d.spaceXs))
            Column {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
                    )
                )
                Text("App preferences", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.screenHorizPad)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {

            // ── How your lists work ────────────────────────────────────────
            SettingsSectionLabel("How Your Lists Work")

            SettingsCard(
                icon = IconEye,
                iconTint = Brand,
                title = "Watchlist Behaviour",
                subtitle = "Auto-removes after you finish watching",
                onClick = { showWatchlistInfoDialog = true },
            )
            SettingsCard(
                icon = IconTrash,
                iconTint = Brand,
                title = "History & Storage",
                subtitle = "Capped at 500 entries, <1 MB total",
                onClick = { showHistoryInfoDialog = true },
            )

            Spacer(Modifier.height(d.spaceXs))

            // ── App ────────────────────────────────────────────────────────
            SettingsSectionLabel("App")

            SettingsCard(
                icon = IconStorage,
                title = "Storage Usage",
                subtitle = "How Reelz uses your device storage",
                onClick = { showStorageDialog = true },
            )
            SettingsCard(
                icon = IconShield,
                title = "Privacy & Security",
                subtitle = "How your data is handled",
                onClick = { showPrivacyDialog = true },
            )
            SettingsCard(
                icon = IconBell,
                title = "Notifications",
                subtitle = "Downloads and renewal reminders",
                onClick = { showNotificationsDialog = true },
            )

            Spacer(Modifier.height(d.spaceXs))

            // ── About ──────────────────────────────────────────────────────
            SettingsSectionLabel("About")

            SettingsCard(
                icon = IconInfoOutline,
                title = "About Reelz",
                subtitle = "Version info & credits",
                onClick = { showAboutDialog = true },
            )
        }
    }
}

// ── Reusable composables ──────────────────────────────────────────────────────

@Composable
private fun SettingsSectionLabel(text: String) {
    val d = LocalDimensions.current
    Text(
        text,
        color = White40,
        fontSize = d.textXs,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = White60,
    onClick: () -> Unit,
) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .clickable(onClick = onClick)
            .padding(d.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceLg - d.spaceXxs),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                .background(GlassMd)
                .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs)),
            Alignment.Center,
        ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(d.iconMd - 2.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = White, fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = White40, fontSize = d.textXs)
        }
        Icon(IconChevronRight, null, tint = White30, modifier = Modifier.size(d.iconMd - 4.dp))
    }
}

@Composable
private fun ReelzDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val d = LocalDimensions.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgCard,
        shape = RoundedCornerShape(d.radiusLg),
        title = { Text(title, color = White, fontWeight = FontWeight.Bold) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(d.spaceMd)) { content() } },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it", color = Brand) }
        },
    )
}

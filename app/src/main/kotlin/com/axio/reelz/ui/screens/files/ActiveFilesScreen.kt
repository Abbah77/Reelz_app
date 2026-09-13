package com.axio.reelz.ui.screens.files

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.axio.reelz.data.model.*
import com.axio.reelz.ui.components.*
import com.axio.reelz.ui.theme.*
import com.axio.reelz.ui.theme.LocalDimensions

// ─────────────────────────────────────────────────────────────────────────────
// Active Downloads Full Page
// Shows: Downloading / Paused (resume) / Failed (retry or delete)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ActiveFilesScreen(
    nav: NavController,
    vm: DownloadsViewModel = hiltViewModel(),
) {
    val d               = LocalDimensions.current
    val ctx             = LocalContext.current
    val activeDownloads by vm.activeDownloads.collectAsState()

    val downloading = activeDownloads.filter { it.status == DownloadStatus.DOWNLOADING }
    val remuxing    = activeDownloads.filter { it.status == DownloadStatus.REMUXING }
    val paused      = activeDownloads.filter { it.status == DownloadStatus.PAUSED }
    val failed      = activeDownloads.filter { it.status == DownloadStatus.ERROR }
    val queued      = activeDownloads.filter { it.status == DownloadStatus.QUEUED }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = d.screenHorizPad, vertical = d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(d.iconLg + d.spaceSm)
                    .clip(CircleShape)
                    .background(GlassMd)
                    .clickable { nav.popBackStack() },
                Alignment.Center,
            ) {
                Text("←", color = White, fontSize = d.textLg, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(d.spaceMd))
            Column {
                Text(
                    "Active Downloads",
                    color = White,
                    fontSize = (d.textXxl.value + 1f).sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                )
                if (activeDownloads.isNotEmpty()) {
                    Text(
                        "${activeDownloads.size} item${if (activeDownloads.size > 1) "s" else ""}",
                        color = White40,
                        fontSize = d.textSm,
                    )
                }
            }
        }

        if (activeDownloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(d.spaceMd),
                ) {
                    Icon(
                        IconDownloadCloud, null,
                        tint = White40,
                        modifier = Modifier.size(d.avatarSm + d.spaceMd),
                    )
                    Text("No active downloads", color = White60, fontSize = d.textLg, fontWeight = FontWeight.Bold)
                    Text("All downloads are complete.", color = White40, fontSize = d.textSm)
                }
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start  = d.screenHorizPad,
                end    = d.screenHorizPad,
                top    = 0.dp,
                bottom = d.spaceXxl * 3,
            ),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            // ── Downloading ──────────────────────────────────────────────────
            if (downloading.isNotEmpty() || queued.isNotEmpty()) {
                item {
                    ActiveSectionHeader(
                        label    = "Downloading",
                        count    = downloading.size + queued.size,
                        dotColor = Brand,
                        pulsing  = downloading.isNotEmpty(),
                    )
                }
                items(downloading + queued, key = { "dl-${it.id}" }) { item ->
                    ActiveDownloadCard(item = item, ctx = ctx, vm = vm)
                }
            }

            // ── Remuxing (finalizing) ─────────────────────────────────────────
            if (remuxing.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(d.spaceXs))
                    ActiveSectionHeader(
                        label    = "Finalizing",
                        count    = remuxing.size,
                        dotColor = Brand,
                        pulsing  = true,
                    )
                }
                items(remuxing, key = { "rx-${it.id}" }) { item ->
                    ActiveDownloadCard(item = item, ctx = ctx, vm = vm)
                }
            }

            // ── Paused ───────────────────────────────────────────────────────
            if (paused.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(d.spaceXs))
                    ActiveSectionHeader(label = "Paused", count = paused.size, dotColor = White40, pulsing = false)
                }
                items(paused, key = { "pa-${it.id}" }) { item ->
                    ActiveDownloadCard(item = item, ctx = ctx, vm = vm)
                }
            }

            // ── Failed ───────────────────────────────────────────────────────
            if (failed.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(d.spaceXs))
                    ActiveSectionHeader(label = "Failed", count = failed.size, dotColor = Error, pulsing = false)
                }
                items(failed, key = { "er-${it.id}" }) { item ->
                    ActiveDownloadCard(item = item, ctx = ctx, vm = vm)
                }
            }
        }
    }
}

@Composable
private fun ActiveSectionHeader(
    label: String,
    count: Int,
    dotColor: Color,
    pulsing: Boolean,
) {
    val d = LocalDimensions.current
    val alpha by if (pulsing) {
        rememberInfiniteTransition(label = "pulse-$label").animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "dot",
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = d.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
        ) {
            Box(
                Modifier
                    .size(d.spaceXs + 2.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = alpha))
            )
            Text(
                label,
                color = White60,
                fontSize = (d.textXxs.value + 1f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
        Box(
            Modifier
                .clip(CircleShape)
                .background(GlassMd)
                .padding(horizontal = d.spaceSm, vertical = d.spaceXxs),
        ) {
            Text("$count", color = White40, fontSize = (d.textXxs.value + 1f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Individual active download card (full-width, in the list)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ActiveDownloadCard(
    item: DownloadItem,
    ctx: Context,
    vm: DownloadsViewModel,
) {
    val d = LocalDimensions.current
    val isDownloading = item.status == DownloadStatus.DOWNLOADING
    val isRemuxing    = item.status == DownloadStatus.REMUXING
    val isPaused      = item.status == DownloadStatus.PAUSED
    val isQueued      = item.status == DownloadStatus.QUEUED
    val isError       = item.status == DownloadStatus.ERROR

    var showDeleteDialog by remember { mutableStateOf(false) }

    val pct    = downloadProgress(item)
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "active-pct-${item.id}")
    val pctInt = (pct * 100).toInt()

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(
                1.dp,
                if (isDownloading || isRemuxing) Brand.copy(.22f) else GlassBorderMd,
                RoundedCornerShape(d.radiusMd),
            )
            .padding(d.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceSm),
    ) {
        // Poster with percentage badge overlay — exactly as original
        Box(
            Modifier
                .size(width = d.avatarSm + d.spaceXxs, height = d.avatarSm + d.spaceMd)
                .clip(RoundedCornerShape(d.radiusSm))
                .background(BgRaised),
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Percentage badge on poster — shown whenever progress is known
            if (!isQueued && !isError && pctInt > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.Black.copy(.72f))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                ) {
                    Text(
                        "$pctInt%",
                        color      = Color.White,
                        fontSize   = (d.textXxs.value + 0.5f).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color      = White,
                fontSize   = d.textXs,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            if (item.mediaType == "TV" && item.season > 0) {
                Text("S${item.season}E${item.episode}", color = White40, fontSize = (d.textXxs.value + 0.5f).sp)
            }
            if (item.quality.isNotBlank()) {
                Text(item.quality, color = Brand.copy(.8f), fontSize = (d.textXxs.value + 0.5f).sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(d.spaceXxs + 2.dp))

            // Progress bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(GlassMd)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(animPct)
                        .fillMaxHeight()
                        .background(
                            brush = when {
                                isError    -> SolidColor(Error)
                                isPaused   -> SolidColor(White40)
                                isQueued   -> SolidColor(White20)
                                isRemuxing -> Brush.horizontalGradient(listOf(Brand2, Brand))
                                else       -> Brush.horizontalGradient(listOf(Brand, Brand2))
                            }
                        )
                )
            }

            Spacer(Modifier.height(d.spaceXxs))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status text — percentage + state label
                Text(
                    when {
                        isQueued   -> "Waiting…"
                        isError    -> "Failed"
                        isRemuxing -> "Finalizing…"
                        isPaused   -> "$pctInt% · Paused"
                        else       -> "$pctInt%"
                    },
                    color    = when {
                        isDownloading -> Success.copy(.85f)
                        isRemuxing    -> Brand.copy(.85f)
                        isError       -> Error
                        else          -> White40
                    },
                    fontSize = (d.textXxs.value + 0.5f).sp,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                    // Pause / Resume / Retry — hidden during remux
                    if (!isRemuxing) {
                        Box(
                            Modifier
                                .size(d.iconMd + d.spaceXxs)
                                .clip(CircleShape)
                                .background(GlassMd)
                                .clickable(onClick = if (isDownloading) {
                                    { vm.pause(ctx, item) }
                                } else {
                                    { vm.resume(ctx, item) }
                                }),
                            Alignment.Center,
                        ) {
                            Icon(
                                imageVector      = if (isDownloading) IconPause else IconPlay,
                                contentDescription = null,
                                tint             = if (isPaused || isError) Brand else White60,
                                modifier         = Modifier.size(d.iconSm - 4.dp),
                            )
                        }
                    }
                    // Cancel / remove
                    Box(
                        Modifier
                            .size(d.iconMd + d.spaceXxs)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .clickable { showDeleteDialog = true },
                        Alignment.Center,
                    ) {
                        Text("✕", color = White40, fontSize = (d.textXxs.value + 1f).sp)
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        ReelzConfirmDeleteActiveDialog(
            title    = if (isError) "Remove Failed Download" else "Cancel Download",
            message  = "Remove \"${item.title}\"${
                if (item.quality.isNotBlank()) " (${item.quality})" else ""
            } from downloads?",
            onDelete  = { vm.delete(item, ctx); showDeleteDialog = false },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun ReelzConfirmDeleteActiveDialog(
    title: String,
    message: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val d = LocalDimensions.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = BgCard,
        shape            = RoundedCornerShape(d.radiusLg),
        title  = { Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = d.textLg) },
        text   = { Text(message, color = White60, fontSize = d.textMd) },
        confirmButton = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(Error.copy(.15f))
                    .border(1.dp, Error.copy(.35f), RoundedCornerShape(d.radiusPill))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Remove", color = Error, fontWeight = FontWeight.Bold, fontSize = d.textSm) }
        },
        dismissButton = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(d.radiusPill))
                    .background(GlassMd)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = d.spaceLg, vertical = d.spaceSm + d.spaceXxs),
            ) { Text("Cancel", color = White60, fontSize = d.textSm) }
        },
    )
}

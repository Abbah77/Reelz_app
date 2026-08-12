package com.axio.reelz.ui.screens.downloads

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
fun ActiveDownloadsScreen(
    nav: NavController,
    vm: DownloadsViewModel = hiltViewModel(),
) {
    val d               = LocalDimensions.current
    val ctx             = LocalContext.current
    val activeDownloads by vm.activeDownloads.collectAsState()

    val downloading = activeDownloads.filter { it.status == DownloadStatus.DOWNLOADING }
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
                        label      = "Downloading",
                        count      = downloading.size + queued.size,
                        dotColor   = Brand,
                        pulsing    = downloading.isNotEmpty(),
                    )
                }
                items(downloading + queued, key = { "dl-${it.id}" }) { item ->
                    ActiveDownloadCard(
                        item     = item,
                        ctx      = ctx,
                        vm       = vm,
                    )
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
    val isPaused      = item.status == DownloadStatus.PAUSED
    val isQueued      = item.status == DownloadStatus.QUEUED
    val isError       = item.status == DownloadStatus.ERROR

    var showDeleteDialog by remember { mutableStateOf(false) }

    val pct = if (item.totalSegments > 0) item.segmentsDone.toFloat() / item.totalSegments
              else if (item.sizeBytes > 0) item.downloadedBytes.toFloat() / item.sizeBytes
              else 0f
    val animPct by animateFloatAsState(pct.coerceIn(0f, 1f), label = "active-pct-${item.id}")
    val pctInt = (pct * 100).toInt()

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusLg - d.spaceXxs))
            .background(BgCard)
            .border(
                1.dp,
                when {
                    isDownloading -> Brand.copy(.2f)
                    isError       -> Error.copy(.2f)
                    else          -> GlassBorderMd
                },
                RoundedCornerShape(d.radiusLg - d.spaceXxs),
            )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(d.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Poster
            Box(
                Modifier
                    .size(width = d.avatarMd + d.spaceXxs + 2.dp, height = d.avatarLg + d.spaceXxs)
                    .clip(RoundedCornerShape(d.radiusSm + 2.dp))
                    .background(BgRaised),
            ) {
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Progress overlay on poster corner
                if (!isError) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(d.spaceXxs)
                            .clip(RoundedCornerShape(d.radiusSm))
                            .background(Color.Black.copy(.7f))
                            .padding(horizontal = d.spaceXxs + 1.dp, vertical = 1.dp),
                    ) {
                        Text("$pctInt%", color = White, fontSize = (d.textXxs.value + 0.5f).sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.width(d.spaceMd))

            Column(Modifier.weight(1f)) {
                // Title + quality + X button
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            color = White,
                            fontSize = d.textMd,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.mediaType == "TV" && item.season > 0) {
                            Text(
                                "S${item.season}E${item.episode}",
                                color = White40,
                                fontSize = d.textXs,
                            )
                        }
                        if (item.quality.isNotBlank()) {
                            Text(
                                item.quality,
                                color = Brand.copy(.85f),
                                fontSize = (d.textXxs.value + 1f).sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    // Cancel button
                    Box(
                        Modifier
                            .size(d.iconLg)
                            .clip(CircleShape)
                            .background(GlassMd)
                            .clickable { showDeleteDialog = true },
                        Alignment.Center,
                    ) { Text("✕", color = White40, fontSize = (d.textSm.value - 1f).sp) }
                }

                Spacer(Modifier.height(d.spaceSm))

                // Progress bar
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GlassMd)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animPct)
                            .fillMaxHeight()
                            .background(
                                brush = when {
                                    isError   -> SolidColor(Error)
                                    isPaused  -> SolidColor(White40)
                                    isQueued  -> SolidColor(White20)
                                    else      -> Brush.horizontalGradient(listOf(Brand, Brand2))
                                }
                            )
                    )
                }

                Spacer(Modifier.height(d.spaceSm - 1.dp))

                // Stats row + action button
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        if (isDownloading && item.networkSpeedBps > 0) {
                            Text(
                                "↓ ${formatSpeed(item.networkSpeedBps)}",
                                color = Success.copy(.85f),
                                fontSize = (d.textXxs.value + 1f).sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (item.sizeBytes > 0) {
                            Text(
                                when {
                                    isQueued -> "Queued"
                                    isError  -> "Download failed"
                                    isPaused -> "${formatSize(item.downloadedBytes)} / ${formatSize(item.sizeBytes)} · Paused"
                                    else     -> "${formatSize(item.downloadedBytes)} / ${formatSize(item.sizeBytes)}"
                                },
                                color = White40,
                                fontSize = (d.textXxs.value + 1f).sp,
                            )
                        }
                    }

                    // Pause / Resume / Retry action pill
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(d.radiusPill))
                            .background(
                                if (isPaused || isError) Brand.copy(.15f) else GlassMd
                            )
                            .border(
                                1.dp,
                                if (isPaused || isError) Brand.copy(.35f) else GlassBorderMd,
                                RoundedCornerShape(d.radiusPill),
                            )
                            .clickable(
                                onClick = when {
                                    isDownloading -> { { vm.pause(ctx, item) } }
                                    else          -> { { vm.resume(ctx, item) } }
                                }
                            )
                            .padding(horizontal = d.spaceMd - d.spaceXxs, vertical = d.spaceXxs + 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(d.spaceXxs + 1.dp),
                    ) {
                        Icon(
                            imageVector = if (isDownloading) IconPause else IconPlay,
                            contentDescription = null,
                            tint = if (isPaused || isError) Brand else White60,
                            modifier = Modifier.size(d.iconSm - 2.dp),
                        )
                        Text(
                            when {
                                isDownloading -> "Pause"
                                isPaused      -> "Resume"
                                isError       -> "Retry"
                                isQueued      -> "Queued"
                                else          -> "Resume"
                            },
                            color = if (isPaused || isError) Brand else White60,
                            fontSize = d.textXs,
                            fontWeight = FontWeight.SemiBold,
                        )
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

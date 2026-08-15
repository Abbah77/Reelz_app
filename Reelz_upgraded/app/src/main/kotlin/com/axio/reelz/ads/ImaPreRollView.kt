package com.axio.reelz.ads

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * ImaPreRollView — Composable wrapper for Google IMA SDK pre-roll ads.
 *
 * Displays a VAST pre-roll ad before playback begins.
 * Wire in your IMA AdDisplayContainer and AdsLoader here.
 *
 * TODO: Replace stub with full IMA integration once ad unit IDs are configured.
 */
@Composable
fun ImaPreRollView(
    vastUrl: String,
    onAdCompleted: () -> Unit,
    onAdError: (Exception?) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx: Context ->
            FrameLayout(ctx).also { container ->
                // TODO: wire IMA AdDisplayContainer + AdsLoader here.
                // For now emit "completed" so playback is never blocked.
                container.post { onAdCompleted() }
            }
        },
        update = {},
        modifier = modifier,
    )
}

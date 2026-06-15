package com.reelz.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.reelz.AppContainer
import com.reelz.data.SeasonInfo
import com.reelz.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── ViewModel ─────────────────────────────────────────────────────────────────

class PlayerViewModel : ViewModel() {

    private val _streamUrl = MutableStateFlow<String?>(null)
    private val _isHls     = MutableStateFlow(false)
    private val _headers   = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _loading   = MutableStateFlow(true)
    private val _error     = MutableStateFlow<String?>(null)
    private val _seasons   = MutableStateFlow<List<SeasonInfo>>(emptyList())
    private val _season    = MutableStateFlow(1)
    private val _episode   = MutableStateFlow(1)
    private val _isTV      = MutableStateFlow(false)
    private val _mediaId   = MutableStateFlow("")

    val streamUrl = _streamUrl.asStateFlow()
    val isHls     = _isHls.asStateFlow()
    val headers   = _headers.asStateFlow()
    val loading   = _loading.asStateFlow()
    val error     = _error.asStateFlow()
    val seasons   = _seasons.asStateFlow()
    val season    = _season.asStateFlow()
    val episode   = _episode.asStateFlow()
    val isTV      = _isTV.asStateFlow()

    fun loadMovie(id: String) {
        _isTV.value    = false
        _mediaId.value = id
        fetchMovie(id)
    }

    fun loadTv(id: String) {
        _isTV.value    = true
        _mediaId.value = id
        viewModelScope.launch {
            runCatching {
                val detail = AppContainer.api.getTvDetail(id)
                _seasons.value = detail.seasons
            }
            fetchEpisode()
        }
    }

    fun selectEpisode(season: Int, episode: Int) {
        _season.value  = season
        _episode.value = episode
        fetchEpisode()
    }

    private fun fetchMovie(id: String) {
        viewModelScope.launch {
            _loading.value = true
            _error.value   = null
            _streamUrl.value = null
            runCatching { AppContainer.api.getMovieStream(id) }
                .onSuccess {
                    _streamUrl.value = it.url
                    _isHls.value     = it.type == "hls"
                    val h = it.headers.toMutableMap()
                    if (it.referer.isNotBlank()) h["Referer"] = it.referer
                    _headers.value   = h
                }
                .onFailure { _error.value = it.message ?: "Stream error" }
            _loading.value = false
        }
    }

    private fun fetchEpisode() {
        viewModelScope.launch {
            _loading.value = true
            _error.value   = null
            _streamUrl.value = null
            runCatching {
                AppContainer.api.getTvStream(_mediaId.value, _season.value, _episode.value)
            }
                .onSuccess {
                    _streamUrl.value = it.url
                    _isHls.value     = it.type == "hls"
                    val h = it.headers.toMutableMap()
                    if (it.referer.isNotBlank()) h["Referer"] = it.referer
                    _headers.value   = h
                }
                .onFailure { _error.value = it.message ?: "Stream error" }
            _loading.value = false
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    vm: PlayerViewModel,
    title: String,
    onBack: () -> Unit,
) {
    val streamUrl by vm.streamUrl.collectAsState()
    val isHls     by vm.isHls.collectAsState()
    val headers   by vm.headers.collectAsState()
    val loading   by vm.loading.collectAsState()
    val error     by vm.error.collectAsState()
    val isTV      by vm.isTV.collectAsState()
    val seasons   by vm.seasons.collectAsState()
    val season    by vm.season.collectAsState()
    val episode   by vm.episode.collectAsState()

    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.weight(1f))
        }

        // Video player
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                loading -> CircularProgressIndicator(color = Brand)
                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Stream unavailable", color = Color.White)
                    Text(error ?: "", color = White60, style = MaterialTheme.typography.bodySmall)
                }
                streamUrl != null -> VideoPlayer(
                    url      = streamUrl!!,
                    isHls    = isHls,
                    headers  = headers,
                    context  = context,
                )
            }
        }

        // Episode picker for TV
        if (isTV && seasons.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            EpisodePicker(
                seasons = seasons,
                currentSeason  = season,
                currentEpisode = episode,
                onSelect = { s, e -> vm.selectEpisode(s, e) },
            )
        }
    }
}

@UnstableApi
@Composable
private fun VideoPlayer(
    url: String,
    isHls: Boolean,
    headers: Map<String, String>,
    context: Context,
) {
    val player = remember(url) {
        val dsFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val item      = MediaItem.fromUri(url)
        val source    = if (isHls)
            HlsMediaSource.Factory(dsFactory).createMediaSource(item)
        else
            ProgressiveMediaSource.Factory(dsFactory).createMediaSource(item)

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(source)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) { onDispose { player.release() } }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                keepScreenOn = true
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun EpisodePicker(
    seasons: List<SeasonInfo>,
    currentSeason: Int,
    currentEpisode: Int,
    onSelect: (Int, Int) -> Unit,
) {
    var pickedSeason by remember { mutableStateOf(currentSeason) }
    val episodes = seasons.firstOrNull { it.season == pickedSeason }?.episodeCount ?: 1

    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Season", style = MaterialTheme.typography.titleSmall, color = White60)
        Spacer(Modifier.height(6.dp))

        // Season row
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            seasons.forEach { s ->
                val sel = s.season == pickedSeason
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) Brand else BgCard)
                        .clickable { pickedSeason = s.season }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("S${s.season}", style = MaterialTheme.typography.labelLarge,
                        color = if (sel) Color.White else White60)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Episode", style = MaterialTheme.typography.titleSmall, color = White60)
        Spacer(Modifier.height(6.dp))

        // Episode row (up to 20 shown)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..minOf(episodes, 20)).forEach { e ->
                val sel = pickedSeason == currentSeason && e == currentEpisode
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) Brand else BgCard)
                        .clickable { onSelect(pickedSeason, e) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("$e", style = MaterialTheme.typography.labelLarge,
                        color = if (sel) Color.White else White60)
                }
            }
        }
    }
}

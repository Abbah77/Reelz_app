package com.axio.reelz.ui.screens.feedback

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.axio.reelz.core.network.ApiCallHandler
import com.axio.reelz.data.dto.*
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// FeedbackScreen
//
// One screen, five different entry points. The `source` param controls which
// shortcut list is shown. Context args carry the traceability IDs the backend
// needs to identify the issue.
//
// Navigation example:
//   nav.navigate(
//       "feedback/player?request_id=abc-123"
//   )
//   nav.navigate(
//       "feedback/detail?tmdb_id=550"
//   )
//
// Route: "feedback/{source}?request_id={rid}&tmdb_id={tid}"
// ─────────────────────────────────────────────────────────────────────────────

// ── Icon vectors ──────────────────────────────────────────────────────────────

private val IconBack: ImageVector get() = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

private val IconFlag: ImageVector get() = ImageVector.Builder("Flag", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(4f, 15f); curveTo(4f, 15f, 5f, 14f, 8f, 14f)
        curveTo(11f, 14f, 13f, 16f, 16f, 16f); curveTo(19f, 16f, 20f, 15f, 20f, 15f)
        lineTo(20f, 5f); curveTo(20f, 5f, 19f, 6f, 16f, 6f)
        curveTo(13f, 6f, 11f, 4f, 8f, 4f); curveTo(5f, 4f, 4f, 5f, 4f, 5f)
        lineTo(4f, 21f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

private val IconCheck: ImageVector get() = ImageVector.Builder("Check", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(20f, 6f); lineTo(9f, 17f); lineTo(4f, 12f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

// ── ViewModel ─────────────────────────────────────────────────────────────────

sealed interface FeedbackUiState {
    object Idle     : FeedbackUiState
    object Loading  : FeedbackUiState
    object Success  : FeedbackUiState
    data class Error(val message: String) : FeedbackUiState
}

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val api:     ReelzApi,
    private val handler: ApiCallHandler,
) : ViewModel() {

    private val _state = MutableStateFlow<FeedbackUiState>(FeedbackUiState.Idle)
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    fun submit(
        source:      FeedbackSource,
        category:    String,
        description: String?,
        requestId:   String?,
        tmdbId:      String?,
    ) {
        if (_state.value is FeedbackUiState.Loading) return
        _state.value = FeedbackUiState.Loading
        viewModelScope.launch {
            val body = FeedbackBody(
                sourceScreen = source.key,
                category     = category,
                description  = description?.trim()?.takeIf { it.isNotEmpty() },
                requestId    = requestId,
                tmdbId       = tmdbId,
            )
            val result = handler.safeCall { api.submitFeedback(body) }
            _state.value = if (result.isSuccess && result.getOrNull()?.ok == true) {
                FeedbackUiState.Success
            } else {
                FeedbackUiState.Error(result.getOrNull()?.error ?: "Something went wrong")
            }
        }
    }

    fun resetState() { _state.value = FeedbackUiState.Idle }
}

// ── Composable ────────────────────────────────────────────────────────────────

@Composable
fun FeedbackScreen(
    nav:         NavController,
    source:      FeedbackSource,
    requestId:   String? = null,
    tmdbId:      String? = null,
    vm:          FeedbackViewModel = hiltViewModel(),
) {
    val d           = LocalDimensions.current
    val uiState     by vm.state.collectAsState()
    val shortcuts   = FEEDBACK_SHORTCUTS[source] ?: emptyList()

    var selectedKey by remember { mutableStateOf<String?>(null) }
    var description by remember { mutableStateOf("") }

    // Success — auto-pop after a beat
    LaunchedEffect(uiState) {
        if (uiState is FeedbackUiState.Success) {
            kotlinx.coroutines.delay(2_000)
            nav.popBackStack()
        }
    }

    val screenTitle = when (source) {
        FeedbackSource.PLAYER   -> "Player feedback"
        FeedbackSource.DETAIL   -> "Report an issue"
        FeedbackSource.DOWNLOAD -> "Download feedback"
        FeedbackSource.SHORTS   -> "Report content"
        FeedbackSource.SETTINGS -> "Send feedback"
    }
    val screenSubtitle = when (source) {
        FeedbackSource.PLAYER   -> "Tell us what went wrong with this stream"
        FeedbackSource.DETAIL   -> "Help us improve this title's info"
        FeedbackSource.DOWNLOAD -> "Tell us about your download experience"
        FeedbackSource.SHORTS   -> "Help us keep Shorts a great place"
        FeedbackSource.SETTINGS -> "Any feedback is welcome — we read everything"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0A0F), Color(0xFF0F0F1A))))
            .systemBarsPadding()
    ) {

        // ── Success overlay ───────────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState is FeedbackUiState.Success,
            enter   = fadeIn() + scaleIn(initialScale = 0.85f),
            exit    = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF0A0A0F)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .background(Color(0xFF7C6EFF).copy(alpha = 0.15f), CircleShape)
                            .border(1.5.dp, Color(0xFF7C6EFF).copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(IconCheck, contentDescription = null, tint = Color(0xFF7C6EFF), modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Thank you!", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("We'll look into it.", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f))
                }
            }
        }

        // ── Main content ──────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = uiState !is FeedbackUiState.Success,
            enter   = fadeIn(),
            exit    = fadeOut(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {

                // ── Top bar ───────────────────────────────────────────────────
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = d.screenHorizPad, vertical = d.spaceLg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(IconBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(d.iconMd))
                        }
                        Spacer(Modifier.width(d.spaceSm))
                        Column {
                            Text(screenTitle, fontSize = d.textXl, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(screenSubtitle, fontSize = d.textSm, color = Color.White.copy(alpha = 0.45f))
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(IconFlag, contentDescription = null, tint = Color(0xFF7C6EFF).copy(alpha = 0.6f), modifier = Modifier.size(d.iconMd))
                    }
                }

                // ── Context badge — shows what's attached ─────────────────────
                if (requestId != null || tmdbId != null) {
                    item {
                        val label = when {
                            requestId != null -> "Request: ${requestId.take(12)}…"
                            tmdbId != null    -> "TMDB ID: $tmdbId"
                            else              -> ""
                        }
                        Row(
                            Modifier
                                .padding(horizontal = d.screenHorizPad)
                                .padding(bottom = d.spaceMd)
                                .background(Color(0xFF7C6EFF).copy(alpha = 0.08f), RoundedCornerShape(d.radiusSm))
                                .border(1.dp, Color(0xFF7C6EFF).copy(alpha = 0.2f), RoundedCornerShape(d.radiusSm))
                                .padding(horizontal = d.spaceLg, vertical = d.spaceSm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("🔗", fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(label, fontSize = d.textXs, color = Color(0xFF7C6EFF), fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // ── Shortcut chips ────────────────────────────────────────────
                item {
                    Text(
                        "What's the issue?",
                        fontSize = d.textMd,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = d.screenHorizPad).padding(bottom = d.spaceMd),
                    )
                }

                item {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = d.screenHorizPad)
                            .padding(bottom = d.spaceLg),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement   = Arrangement.spacedBy(8.dp),
                    ) {
                        shortcuts.forEach { (label, key) ->
                            val isSelected = selectedKey == key
                            val bgColor    by animateColorAsState(
                                if (isSelected) Color(0xFF7C6EFF) else Color(0xFF1A1A24),
                                label = "chipBg",
                            )
                            val borderColor by animateColorAsState(
                                if (isSelected) Color(0xFF7C6EFF) else Color(0xFF2A2A38),
                                label = "chipBorder",
                            )
                            Box(
                                Modifier
                                    .background(bgColor, RoundedCornerShape(100.dp))
                                    .border(1.dp, borderColor, RoundedCornerShape(100.dp))
                                    .clickable { selectedKey = if (isSelected) null else key }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    label,
                                    fontSize   = d.textSm,
                                    color      = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                // ── Optional description ──────────────────────────────────────
                item {
                    Text(
                        "Add more details (optional)",
                        fontSize = d.textMd,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.padding(horizontal = d.screenHorizPad).padding(bottom = d.spaceSm),
                    )
                    OutlinedTextField(
                        value         = description,
                        onValueChange = { if (it.length <= 2000) description = it },
                        placeholder   = { Text("Describe what happened…", color = Color.White.copy(alpha = 0.25f), fontSize = d.textSm) },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = d.screenHorizPad)
                            .height(120.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color(0xFF7C6EFF),
                            unfocusedBorderColor = Color(0xFF2A2A38),
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White,
                            cursorColor          = Color(0xFF7C6EFF),
                            focusedContainerColor   = Color(0xFF13131C),
                            unfocusedContainerColor = Color(0xFF13131C),
                        ),
                        shape       = RoundedCornerShape(d.radiusMd),
                        maxLines    = 6,
                        textStyle   = LocalTextStyle.current.copy(fontSize = d.textSm),
                    )
                    Text(
                        "${description.length}/2000",
                        fontSize = d.textXs,
                        color    = Color.White.copy(alpha = 0.25f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = d.screenHorizPad, vertical = 4.dp),
                        textAlign = TextAlign.End,
                    )
                }

                // ── Error message ─────────────────────────────────────────────
                if (uiState is FeedbackUiState.Error) {
                    item {
                        Text(
                            (uiState as FeedbackUiState.Error).message,
                            fontSize = d.textSm,
                            color    = Color(0xFFE74C3C),
                            modifier = Modifier
                                .padding(horizontal = d.screenHorizPad, vertical = d.spaceSm)
                                .background(Color(0xFFE74C3C).copy(alpha = 0.08f), RoundedCornerShape(d.radiusSm))
                                .padding(horizontal = d.spaceLg, vertical = d.spaceSm),
                        )
                    }
                }

                // ── Submit button ─────────────────────────────────────────────
                item {
                    val canSubmit = selectedKey != null && uiState !is FeedbackUiState.Loading
                    Button(
                        onClick = {
                            val key = selectedKey ?: return@Button
                            vm.submit(
                                source      = source,
                                category    = key,
                                description = description.trim().takeIf { it.isNotEmpty() },
                                requestId   = requestId,
                                tmdbId      = tmdbId,
                            )
                        },
                        enabled  = canSubmit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = d.screenHorizPad)
                            .padding(top = d.spaceLg)
                            .height(d.buttonHeightMd),
                        shape    = RoundedCornerShape(d.radiusMd),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor  = Color(0xFF7C6EFF),
                            disabledContainerColor = Color(0xFF2A2A38),
                        ),
                    ) {
                        if (uiState is FeedbackUiState.Loading) {
                            CircularProgressIndicator(
                                color     = Color.White,
                                modifier  = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Send feedback", fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Text(
                        "Your report is anonymous unless you're signed in.",
                        fontSize  = d.textXs,
                        color     = Color.White.copy(alpha = 0.3f),
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = d.screenHorizPad, vertical = d.spaceSm),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

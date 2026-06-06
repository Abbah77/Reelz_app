package com.reelz.ui.screens.search

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.reelz.BuildConfig
import com.reelz.data.model.*
import com.reelz.ui.theme.*

@Composable
fun SearchScreen(
    onMediaClick: (Int, MediaType) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()

    Column(Modifier.fillMaxSize().background(Surface900)) {

        Spacer(Modifier.height(52.dp))
        Text(
            "Search",
            color = White,
            fontWeight = FontWeight.Black,
            fontSize = 24.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Search bar
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Movies, TV shows…", color = White40) },
            leadingIcon  = { Icon(Icons.Default.Search, null, tint = White60) },
            trailingIcon = {
                if (ui.query.isNotEmpty())
                    IconButton(onClick = vm::clearQuery) {
                        Icon(Icons.Default.Close, null, tint = White60)
                    }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = Primary,
                unfocusedBorderColor = Stroke,
                cursorColor          = Primary,
                focusedTextColor     = White,
                unfocusedTextColor   = White,
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        when {
            ui.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary, modifier = Modifier.size(36.dp))
            }
            ui.query.length >= 2 && ui.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SearchOff, null, tint = White40, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No results for \"${ui.query}\"", color = White60)
                }
            }
            ui.results.isNotEmpty() -> LazyColumn(
                contentPadding = PaddingValues(bottom = 80.dp, top = 4.dp),
            ) {
                items(ui.results) { media ->
                    SearchResultRow(media, onMediaClick)
                }
            }
            else -> SearchHint()
        }
    }
}

@Composable
private fun SearchResultRow(media: Media, onClick: (Int, MediaType) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(media.tmdbId, media.mediaType) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = BuildConfig.TMDB_IMG_W500 + media.posterPath,
            contentDescription = media.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(56.dp).height(80.dp)
                .background(Surface700, RoundedCornerShape(8.dp))
                .run { this },
        )
        Column(Modifier.weight(1f)) {
            Text(media.title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(color = if (media.mediaType == MediaType.TV) Surface700 else Primary.copy(.15f), shape = RoundedCornerShape(4.dp)) {
                    Text(
                        if (media.mediaType == MediaType.TV) "TV" else "Movie",
                        color = if (media.mediaType == MediaType.TV) White60 else Primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Text(media.releaseDate?.take(4) ?: "", color = White40, fontSize = 12.sp)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, tint = Gold, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text("${"%.1f".format(media.voteAverage)}", color = Gold, fontSize = 12.sp)
            }
            Text(media.overview, color = White40, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = White40)
    }
    Divider(color = Stroke.copy(.25f), modifier = Modifier.padding(start = 84.dp, end = 16.dp))
}

@Composable
private fun SearchHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Search, null, tint = White20, modifier = Modifier.size(64.dp))
            Text("Search for movies & TV shows", color = White40, fontSize = 14.sp)
        }
    }
}

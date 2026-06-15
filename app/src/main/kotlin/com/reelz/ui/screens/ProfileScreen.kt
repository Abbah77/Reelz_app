package com.reelz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.reelz.ui.theme.*

@Composable
fun ProfileScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        // Avatar
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(BgCard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Person, null, tint = Brand, modifier = Modifier.size(48.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text("Guest User", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text("Free & public domain content", style = MaterialTheme.typography.bodyMedium, color = White60)

        Spacer(Modifier.height(32.dp))

        // Info card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BgCard)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoRow("Content source", "Archive.org")
            InfoRow("Backend", "tt-b577.onrender.com")
            InfoRow("License", "Public Domain / CC")
            InfoRow("Version", "1.0.0")
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "All content is free, legal, and copyright-free — sourced from the Internet Archive.",
            style = MaterialTheme.typography.bodySmall,
            color = White40,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = White60)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = White)
    }
}

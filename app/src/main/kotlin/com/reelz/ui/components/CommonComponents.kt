package com.reelz.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.reelz.ui.theme.*

@Composable
fun FullScreenLoader() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(color = Primary, strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
            Text("Loading…", color = White60, fontSize = 14.sp)
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.WifiOff, null, tint = White40, modifier = Modifier.size(56.dp))
            Text("Something went wrong", color = White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(message, color = White60, fontSize = 13.sp)
            Button(
                onClick = onRetry,
                colors  = ButtonDefaults.buttonColors(containerColor = Primary),
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Try Again")
            }
        }
    }
}

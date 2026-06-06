package com.reelz.ui.screens.player

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.reelz.data.model.MediaType
import com.reelz.ui.theme.ReelzTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val tmdbId    = intent.getIntExtra("tmdbId", -1)
        val typeStr   = intent.getStringExtra("mediaType") ?: "MOVIE"
        val season    = intent.getIntExtra("season", 0)
        val episode   = intent.getIntExtra("episode", 0)
        val title     = intent.getStringExtra("title") ?: ""
        val poster    = intent.getStringExtra("posterPath")
        val mediaType = if (typeStr == "TV") MediaType.TV else MediaType.MOVIE

        vm.init(
            context    = this,
            tmdbId     = tmdbId,
            mediaType  = mediaType,
            season     = season,
            episode    = episode,
            title      = title,
            posterPath = poster,
        )

        setContent {
            ReelzTheme {
                PlayerScreen(
                    vm         = vm,
                    onBack     = { finish() },
                )
            }
        }
    }

    override fun onPause()   { super.onPause();  vm.pause()  }
    override fun onResume()  { super.onResume(); vm.resume() }
    override fun onDestroy() { vm.release();     super.onDestroy() }
}

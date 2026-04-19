package com.example.autosrtplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.autosrtplayer.data.intent.LaunchTarget
import com.example.autosrtplayer.data.intent.SharedUrlExtractor
import com.example.autosrtplayer.ui.PlayerScreen
import com.example.autosrtplayer.ui.theme.AutoSrtPlayerTheme

class MainActivity : ComponentActivity() {
    private var sharedM3uUrl by mutableStateOf<String?>(null)
    private var sharedSourceId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateLaunchState(intent)
        setContent {
            AutoSrtPlayerTheme {
                Surface {
                    PlayerScreen(
                        sharedM3uUrl = sharedM3uUrl,
                        sharedSourceId = sharedSourceId,
                        onSharedM3uUrlConsumed = { sharedM3uUrl = null },
                        onSharedSourceIdConsumed = { sharedSourceId = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateLaunchState(intent)
    }

    private fun updateLaunchState(intent: Intent?) {
        sharedM3uUrl = null
        sharedSourceId = null
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                sharedM3uUrl = SharedUrlExtractor.extractM3uUrl(sharedText)
            }

            Intent.ACTION_VIEW -> {
                when (val target = SharedUrlExtractor.extractLaunchTarget(intent.dataString)) {
                    is LaunchTarget.Url -> sharedM3uUrl = target.value
                    is LaunchTarget.SourceId -> sharedSourceId = target.value
                    null -> Unit
                }
            }
        }
    }
}

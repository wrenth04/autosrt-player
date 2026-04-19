package com.example.autosrtplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.autosrtplayer.data.intent.SharedUrlExtractor
import com.example.autosrtplayer.ui.PlayerScreen
import com.example.autosrtplayer.ui.theme.AutoSrtPlayerTheme

class MainActivity : ComponentActivity() {
    private var sharedM3uUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedM3uUrl = extractSharedM3uUrl(intent)
        setContent {
            AutoSrtPlayerTheme {
                Surface {
                    PlayerScreen(
                        sharedM3uUrl = sharedM3uUrl,
                        onSharedM3uUrlConsumed = { sharedM3uUrl = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedM3uUrl = extractSharedM3uUrl(intent)
    }

    private fun extractSharedM3uUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        return SharedUrlExtractor.extractM3uUrl(sharedText)
    }
}

package com.example.autosrtplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.autosrtplayer.ui.PlayerScreen
import com.example.autosrtplayer.ui.theme.AutoSrtPlayerTheme

class MainActivity : ComponentActivity() {
    companion object {
        private val UrlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    }

    private var incomingPlaylistUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingPlaylistUrl = extractPlaylistUrl(intent)
        setContent {
            AutoSrtPlayerTheme {
                Surface {
                    PlayerScreen(initialPlaylistUrl = incomingPlaylistUrl)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingPlaylistUrl = extractPlaylistUrl(intent)
    }

    private fun extractPlaylistUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (sharedText.isBlank()) return null

        val url = UrlRegex.find(sharedText)?.value?.trim().orEmpty()
        if (url.isBlank()) return null

        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        return url
    }
}

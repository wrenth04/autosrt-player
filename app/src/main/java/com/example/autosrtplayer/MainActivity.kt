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
        val data = intent?.dataString?.trim().orEmpty()
        if (data.isBlank()) return null
        val uri = Uri.parse(data)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        val path = uri.path.orEmpty().lowercase()
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return data

        val host = uri.host?.lowercase().orEmpty()
        if (host == "github.com" && path.startsWith("/wrenth04/autosrt/releases")) return data

        return null
    }
}

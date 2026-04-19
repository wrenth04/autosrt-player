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
        return if (scheme == "http" || scheme == "https") data else null
    }
}

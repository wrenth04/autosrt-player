package com.example.autosrtplayer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.autosrtplayer.data.favorites.FavoriteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

@Composable
internal fun FavoritesScreen(
    items: List<FavoriteItem>,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit,
    onRemoveClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("我的最愛", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) {
                Text("返回")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = "尚未加入我的最愛",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items.forEach { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FavoriteCoverImage(
                                        imageUrl = favoriteCoverUrl(item.id),
                                        contentDescription = item.id,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Text(
                                        text = item.id,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { onItemClick(item.id) }) {
                                        Text("▶")
                                    }
                                    TextButton(onClick = { onRemoveClick(item.id) }) {
                                        Text("✕")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun favoriteCoverUrl(id: String): String {
    val cleanCode = id.trim().lowercase(Locale.ROOT)
    return "https://fourhoi.com/$cleanCode/cover-n.jpg"
}

@Composable
private fun FavoriteCoverImage(
    imageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val client = remember { OkHttpClient() }
    val bitmapState = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, imageUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(imageUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    response.body?.byteStream()?.use { input ->
                        BitmapFactory.decodeStream(input)?.asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }

    val shape = RoundedCornerShape(6.dp)
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

package com.example.autosrtplayer.ui.favorites

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autosrtplayer.data.favorites.FavoriteCodec
import com.example.autosrtplayer.data.favorites.FavoriteItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class FavoritesUiState(
    val favoriteItems: List<FavoriteItem> = emptyList(),
    val favoriteImportMessage: String? = null,
    val favoriteExportMessage: String? = null
)

class FavoritesViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    private var settingsPrefs: android.content.SharedPreferences? = null

    companion object {
        private const val PrefsName = "autosrt_player_settings"
        private const val KeyFavoriteItems = "favorite_items"
    }

    fun initialize(context: Context) {
        settingsPrefs = context.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        loadFavoriteItems()
    }

    private fun loadFavoriteItems() {
        val encoded = settingsPrefs?.getString(KeyFavoriteItems, null)
        val items = FavoriteCodec.decode(encoded)
        _uiState.update { it.copy(favoriteItems = items) }
    }

    fun toggleCurrentFavorite(currentSourceId: String?) {
        val id = currentSourceId?.trim().orEmpty()
        if (id.isBlank()) {
            return
        }

        val normalized = id.lowercase()
        val updated = _uiState.value.favoriteItems.toMutableList()
        val existingIndex = updated.indexOfFirst { it.id.lowercase() == normalized }
        if (existingIndex >= 0) {
            updated.removeAt(existingIndex)
        } else {
            updated.add(0, FavoriteItem(id = id))
        }
        persistFavoriteItems(updated)
        _uiState.update { it.copy(favoriteItems = updated) }
    }

    fun removeFavorite(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        val updated = _uiState.value.favoriteItems.filterNot { it.id.equals(normalized, ignoreCase = true) }
        persistFavoriteItems(updated)
        _uiState.update { it.copy(favoriteItems = updated) }
    }

    fun playFavorite(id: String) {
        val normalized = id.trim()
        if (normalized.isBlank()) return
        _uiState.update { it.copy(favoriteItems = _uiState.value.favoriteItems) }
    }

    private fun persistFavoriteItems(items: List<FavoriteItem>) {
        settingsPrefs?.edit()?.putString(KeyFavoriteItems, FavoriteCodec.encode(items))?.apply()
    }

    fun exportFavorites(context: Context) {
        val items = _uiState.value.favoriteItems
        if (items.isEmpty()) {
            _uiState.update { it.copy(favoriteExportMessage = "沒有可匯出的最愛項目") }
            return
        }
        val plainText = items.joinToString("\n") { it.id }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("favorites", plainText)
        clipboard.setPrimaryClip(clip)
        _uiState.update { it.copy(favoriteExportMessage = "已複製 ${items.size} 個項目到剪貼簿") }
    }

    fun importFavorites(text: String) {
        if (text.isBlank()) {
            _uiState.update { it.copy(favoriteImportMessage = "請先貼上包含 ID 的文字") }
            return
        }
        val existing = _uiState.value.favoriteItems
        val existingKeys = existing.map { it.id.lowercase(Locale.ROOT) }.toMutableSet()
        val newItems = mutableListOf<FavoriteItem>()
        text.lineSequence().forEach { line ->
            val id = line.trim()
            if (id.isBlank()) return@forEach
            val key = id.lowercase(Locale.ROOT)
            if (existingKeys.add(key)) {
                newItems.add(FavoriteItem(id = id))
            }
        }
        if (newItems.isEmpty()) {
            _uiState.update { it.copy(favoriteImportMessage = "沒有新的項目可加入（全部重複）") }
            return
        }
        val merged = newItems + existing
        persistFavoriteItems(merged)
        _uiState.update {
            it.copy(
                favoriteItems = merged,
                favoriteImportMessage = "已加入 ${newItems.size} 個項目"
            )
        }
    }

    fun clearFavoriteMessages() {
        _uiState.update { it.copy(favoriteImportMessage = null, favoriteExportMessage = null) }
    }
}

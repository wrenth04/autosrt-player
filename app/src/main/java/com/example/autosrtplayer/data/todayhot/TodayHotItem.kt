package com.example.autosrtplayer.data.todayhot

import java.util.Locale

data class TodayHotItem(
    val code: String,
    val title: String?,
    val coverUrl: String?,
    val updatedAt: String?,
    val url: String?
) {
    val cleanCode: String
        get() = code.trim().lowercase(Locale.ROOT)

    val fourHoiCoverUrl: String
        get() = "https://fourhoi.com/$cleanCode/cover-n.jpg"
}

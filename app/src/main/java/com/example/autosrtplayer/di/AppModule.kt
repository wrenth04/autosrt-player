package com.example.autosrtplayer.di

import android.content.Context
import com.example.autosrtplayer.data.playlist.PlaylistParser
import com.example.autosrtplayer.data.playlist.PlaylistRepository
import com.example.autosrtplayer.data.playlist.SubtitleRepository
import com.example.autosrtplayer.data.playlist.MissavHtmlExtractor
import com.example.autosrtplayer.data.playlist.MissavPlaylistBuilder
import com.example.autosrtplayer.data.playback.MediaItemBuilder
import com.example.autosrtplayer.data.playback.PlayerFactory
import com.example.autosrtplayer.data.todayhot.TodayHotRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePlaylistParser(): PlaylistParser = PlaylistParser()

    @Provides
    @Singleton
    fun providePlaylistRepository(): PlaylistRepository = PlaylistRepository()

    @Provides
    @Singleton
    fun provideSubtitleRepository(
        @ApplicationContext context: Context
    ): SubtitleRepository = SubtitleRepository()

    @Provides
    @Singleton
    fun provideMissavHtmlExtractor(): MissavHtmlExtractor = MissavHtmlExtractor()

    @Provides
    @Singleton
    fun provideMissavPlaylistBuilder(): MissavPlaylistBuilder = MissavPlaylistBuilder()

    @Provides
    @Singleton
    fun provideMediaItemBuilder(): MediaItemBuilder = MediaItemBuilder()

    @Provides
    @Singleton
    fun providePlayerFactory(
        @ApplicationContext context: Context
    ): PlayerFactory = PlayerFactory()

    @Provides
    @Singleton
    fun provideTodayHotRepository(): TodayHotRepository = TodayHotRepository()
}

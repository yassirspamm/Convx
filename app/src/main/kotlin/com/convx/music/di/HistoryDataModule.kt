package com.convx.music.di

import com.convx.music.history.HistoryLocalDataSource
import com.convx.music.history.RoomHistoryLocalDataSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HistoryDataModule {
    @Binds
    @Singleton
    abstract fun bindHistoryLocalDataSource(
        source: RoomHistoryLocalDataSource,
    ): HistoryLocalDataSource
}

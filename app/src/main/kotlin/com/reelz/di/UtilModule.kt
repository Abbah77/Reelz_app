package com.reelz.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.reelz.data.local.DownloadDao
import com.reelz.data.repository.DownloadRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UtilModule {

    @Provides @Singleton
    fun provideGson(): Gson = GsonBuilder().create()

    @Provides @Singleton
    fun provideDownloadRepository(dao: DownloadDao, gson: Gson): DownloadRepository =
        DownloadRepository(dao, gson)
}

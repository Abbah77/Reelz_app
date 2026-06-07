package com.reelz.di

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UtilModule {
    @Provides @Singleton fun provideGson() = Gson()

    @Provides @Singleton
    fun provideSharedPrefs(@ApplicationContext ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("reelz_prefs", Context.MODE_PRIVATE)
}

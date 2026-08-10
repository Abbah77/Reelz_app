package com.axio.reelz.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UtilModule {
    @Provides @Singleton fun provideGson(): Gson = GsonBuilder()
        .serializeNulls()
        .setLenient()
        .create()
    // SharedPreferences (reelz_prefs) removed — it was provided but never
    // injected anywhere in the app. All scalar preferences now live in
    // AppPreferencesStore (DataStore) or Room.
}

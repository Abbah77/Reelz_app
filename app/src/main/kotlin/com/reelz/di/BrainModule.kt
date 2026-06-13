package com.reelz.di

import android.content.Context
import com.reelz.brain.TasteAuthStore
import com.reelz.brain.TasteEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  BrainModule
 *
 *  Hilt module for the recommendation brain.
 *  Add this to your di/ package alongside AppModule.kt.
 *
 *  TasteEngine is a Singleton — one instance for the whole app lifetime.
 *  This ensures:
 *  - Profile loaded once from disk at startup
 *  - All screens share the same ranked state
 *  - No race conditions between screens writing to profile
 * ════════════════════════════════════════════════════════════════════════════
 */
@Module
@InstallIn(SingletonComponent::class)
object BrainModule {

    @Provides
    @Singleton
    fun provideTasteAuthStore(
        @ApplicationContext context: Context,
    ): TasteAuthStore = TasteAuthStore(context)

    @Provides
    @Singleton
    fun provideTasteEngine(
        @ApplicationContext context: Context,
    ): TasteEngine = TasteEngine(context)
}

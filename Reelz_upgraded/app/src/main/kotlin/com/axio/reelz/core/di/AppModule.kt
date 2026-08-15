package com.axio.reelz.core.di

import android.content.Context
import androidx.room.Room
import com.axio.reelz.core.database.ReelzDatabase
import com.axio.reelz.core.database.MIGRATION_2_3
import com.axio.reelz.core.network.PLACEHOLDER_BASE
import com.axio.reelz.core.network.buildOkHttpClient
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.data.repository.ConfigRepository
import com.axio.reelz.data.repository.UserRepository
import com.google.gson.Gson
import dagger.Lazy
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
//  AppModule — single Hilt module for the entire app.
//
//  Responsibilities:
//   • Gson        — shared JSON serializer
//   • OkHttpClient — built by NetworkClient.kt (extracted from here)
//   • Retrofit    — single instance; base URL replaced by OkHttp interceptor
//   • ReelzApi    — single Retrofit interface
//   • Room        — single DB instance; all DAOs provided individually
//
//  What was moved OUT of AppModule per the restructure plan:
//   • OkHttp interceptor logic → core/network/NetworkClient.kt
//   • Placeholder base URL constant → core/network/NetworkClient.kt
//
//  Naming per the plan:
//   • UserRepository (was UserRepository) → injected into NetworkClient
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── Gson ──────────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setLenient()
        .serializeNulls()
        .create()

    // ── OkHttp ────────────────────────────────────────────────────────────────
    //  Construction logic extracted to core/network/NetworkClient.kt.
    //  AppModule stays as the Hilt binding point only.

    @Provides @Singleton
    fun provideOkHttpClient(
        configRepo: Lazy<ConfigRepository>,
        userRepo: Lazy<UserRepository>,
    ) = buildOkHttpClient(configRepo::get, userRepo::get)

    // ── Retrofit ──────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideRetrofit(
        client: okhttp3.OkHttpClient,
        gson: Gson,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE)    // replaced by OkHttp interceptor at call time
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    @Provides @Singleton
    fun provideReelzApi(retrofit: Retrofit): ReelzApi =
        retrofit.create(ReelzApi::class.java)

    // ── Room ──────────────────────────────────────────────────────────────────
    //
    // v1 fresh schema — no legacy migrations.
    // WAL: non-blocking concurrent reads (scroll + prefetch).
    // New DB file name "reelz_v3.db" — old "reelz.db" left untouched.

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): ReelzDatabase =
        Room.databaseBuilder(ctx, ReelzDatabase::class.java, "reelz_v3.db")
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

    // ── DAO providers ─────────────────────────────────────────────────────────

    @Provides fun provideFeedCacheDao(db: ReelzDatabase)          = db.feedCacheDao()
    @Provides fun provideDetailCacheDao(db: ReelzDatabase)        = db.detailCacheDao()
    @Provides fun provideSearchCacheDao(db: ReelzDatabase)        = db.searchCacheDao()
    @Provides fun provideWatchProgressDao(db: ReelzDatabase)      = db.watchProgressDao()
    @Provides fun provideWatchlistDao(db: ReelzDatabase)          = db.watchlistDao()
    @Provides fun provideRecentSearchDao(db: ReelzDatabase)       = db.recentSearchDao()
    @Provides fun provideUserSessionDao(db: ReelzDatabase)        = db.userSessionDao()
    @Provides fun provideAppConfigCacheDao(db: ReelzDatabase)     = db.appConfigCacheDao()
    @Provides fun provideDownloadDao(db: ReelzDatabase)           = db.downloadDao()
    @Provides fun provideDownloadSubtitleDao(db: ReelzDatabase)   = db.downloadSubtitleDao()
    @Provides fun provideWatchHistoryDao(db: ReelzDatabase)       = db.watchHistoryDao()
    @Provides fun provideSavedVideoDao(db: ReelzDatabase)         = db.savedVideoDao()
    @Provides fun provideTransferDao(db: ReelzDatabase)           = db.transferDao()
}

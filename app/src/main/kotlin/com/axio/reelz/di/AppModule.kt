package com.axio.reelz.di

import android.content.Context
import androidx.room.Room
import com.axio.reelz.BuildConfig
import com.axio.reelz.data.local.*
import com.axio.reelz.data.remote.api.ReelzApi
import com.axio.reelz.data.repository.ConfigRepository
import com.axio.reelz.data.repository.UserSessionRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

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
    //
    // Interceptor stack (applied in order):
    //  1. DynamicBaseUrl   → rewrites base URL to live backendUrl() per-call
    //  2. Auth             → injects Bearer token from session repo
    //  3. HttpLogging      → BODY in debug, NONE in release

    @Provides @Singleton
    fun provideOkHttpClient(
        configRepo: ConfigRepository,
        sessionRepo: UserSessionRepository,
    ): OkHttpClient {

        // 1. Dynamic base URL — reads live URL from ConfigRepository on each call.
        //    On first launch before config loads: uses BuildConfig.BACKEND_URL.
        val dynamicBaseUrl = Interceptor { chain ->
            val original = chain.request()
            val liveBase = configRepo.backendUrl().trimEnd('/') + "/"

            // Replace the placeholder base with the live backend URL
            val rewrittenUrl = original.url.toString()
                .replaceFirst(PLACEHOLDER_BASE, liveBase)

            val newReq = try {
                original.newBuilder().url(rewrittenUrl).build()
            } catch (_: Exception) { original }

            chain.proceed(newReq)
        }

        // 2. Auth — Bearer token from live session (no rebuild needed on sign-in/out)
        val auth = Interceptor { chain ->
            val token = sessionRepo.accessToken
            val req = if (token.isNotBlank()) {
                chain.request().newBuilder().header("Authorization", token).build()
            } else chain.request()
            chain.proceed(req)
        }

        // 3. Logging
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        }

        return OkHttpClient.Builder()
            .addInterceptor(dynamicBaseUrl)
            .addInterceptor(auth)
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ── Retrofit ──────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideRetrofit(client: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE)   // replaced by OkHttp interceptor at call time
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
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideFeedCacheDao(db: ReelzDatabase)        = db.feedCacheDao()
    @Provides fun provideDetailCacheDao(db: ReelzDatabase)      = db.detailCacheDao()
    @Provides fun provideSearchCacheDao(db: ReelzDatabase)      = db.searchCacheDao()
    @Provides fun provideWatchProgressDao(db: ReelzDatabase)    = db.watchProgressDao()
    @Provides fun provideWatchlistDao(db: ReelzDatabase)        = db.watchlistDao()
    @Provides fun provideRecentSearchDao(db: ReelzDatabase)     = db.recentSearchDao()
    @Provides fun provideUserSessionDao(db: ReelzDatabase)      = db.userSessionDao()
    @Provides fun provideAppConfigCacheDao(db: ReelzDatabase)   = db.appConfigCacheDao()
    @Provides fun provideDownloadDao(db: ReelzDatabase)         = db.downloadDao()
    @Provides fun provideDownloadSubtitleDao(db: ReelzDatabase) = db.downloadSubtitleDao()

    companion object {
        const val PLACEHOLDER_BASE = "https://placeholder.reelz.app/"
    }
}

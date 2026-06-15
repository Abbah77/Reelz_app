package com.reelz.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.reelz.data.local.*
import com.reelz.data.remote.api.TmdbApi
import com.reelz.data.remote.api.OpenSubtitlesApi
import com.reelz.data.repository.MediaRepository
import com.reelz.data.repository.OpenSubtitlesRepository
import com.reelz.remoteconfig.RemoteConfigRepository
import com.reelz.scanner.DirectScanner
import com.reelz.scanner.SourceRegistry
import com.reelz.scanner.StreamEngine
import com.reelz.scanner.StreamResultCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── Remote Config ─────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideRemoteConfigRepository(
        @ApplicationContext ctx: Context,
        gson: Gson,
    ): RemoteConfigRepository = RemoteConfigRepository(ctx, gson)

    @Provides @Singleton
    fun provideSourceRegistry(remoteConfig: RemoteConfigRepository): SourceRegistry =
        SourceRegistry(remoteConfig)

    // ── OkHttp clients ────────────────────────────────────────────────────────

    /**
     * TMDB client — API key is now resolved dynamically from remote config.
     * The interceptor reads the live key on every request so key rotation is instant.
     */
    @Provides @Singleton @Named("tmdb")
    fun provideTmdbOkHttp(remoteConfig: RemoteConfigRepository): OkHttpClient {
        val tmdbAuthInterceptor = Interceptor { chain ->
            val original = chain.request()
            val url = original.url.newBuilder()
                .addQueryParameter("api_key", remoteConfig.activeTmdbKey().orEmpty())
                .build()
            chain.proceed(original.newBuilder().url(url).build())
        }
        return OkHttpClient.Builder()
            .addInterceptor(tmdbAuthInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides @Singleton @Named("download")
    fun provideDownloadOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .dispatcher(Dispatcher().also { it.maxRequestsPerHost = 6 })
        .retryOnConnectionFailure(true)
        .build()

    // ── Retrofit / TMDB API ───────────────────────────────────────────────────

    @Provides @Singleton
    fun provideRetrofit(@Named("tmdb") client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides @Singleton
    fun provideTmdbApi(retrofit: Retrofit): TmdbApi = retrofit.create(TmdbApi::class.java)

    // ── Database ──────────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): ReelzDatabase =
        Room.databaseBuilder(ctx, ReelzDatabase::class.java, "reelz.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides fun provideWatchlistDao(db: ReelzDatabase)        = db.watchlistDao()
    @Provides fun provideWatchHistoryDao(db: ReelzDatabase)     = db.watchHistoryDao()
    @Provides fun provideLikedDao(db: ReelzDatabase)            = db.likedDao()
    @Provides fun provideCachedMediaDao(db: ReelzDatabase)      = db.cachedMediaDao()
    @Provides fun provideDownloadDao(db: ReelzDatabase)         = db.downloadDao()
    @Provides fun provideDownloadSubtitleDao(db: ReelzDatabase) = db.downloadSubtitleDao()
    @Provides fun provideTransferDao(db: ReelzDatabase)         = db.transferDao()

    // ── Repositories ──────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideMediaRepository(
        api: TmdbApi,
        cachedMediaDao: CachedMediaDao,
        watchlistDao: WatchlistDao,
        watchHistoryDao: WatchHistoryDao,
        likedDao: LikedDao,
    ) = MediaRepository(api, cachedMediaDao, watchlistDao, watchHistoryDao, likedDao)

    // ── Stream engine ─────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideStreamResultCache() = StreamResultCache()

    @Provides @Singleton
    fun provideStreamEngine(
        @ApplicationContext ctx: Context,
        directScanner: DirectScanner,
        cache: StreamResultCache,
        sourceRegistry: SourceRegistry,
    ) = StreamEngine(ctx, directScanner, cache, sourceRegistry)

    // ── OpenSubtitles ─────────────────────────────────────────────────────────

    /**
     * API key resolved at injection time from remote config.
     * Falls back to the compile-time key if remote config hasn't loaded yet.
     */
    @Provides @Singleton @Named("osApiKey")
    fun provideOsApiKey(remoteConfig: RemoteConfigRepository): String =
        remoteConfig.activeOsKey().orEmpty()

    @Provides @Singleton @Named("osUserAgent")
    fun provideOsUserAgent(): String = "Reelz v2.0"

    @Provides @Singleton @Named("opensubtitles")
    fun provideOsOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun provideOpenSubtitlesApi(@Named("opensubtitles") client: OkHttpClient): OpenSubtitlesApi =
        Retrofit.Builder()
            .baseUrl("https://api.opensubtitles.com/api/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenSubtitlesApi::class.java)

    @Provides @Singleton
    fun provideOpenSubtitlesRepository(
        api: OpenSubtitlesApi,
        @Named("osApiKey") apiKey: String,
        @Named("osUserAgent") userAgent: String,
    ) = OpenSubtitlesRepository(api, apiKey, userAgent)
}

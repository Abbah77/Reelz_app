package com.axio.reelz.di

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import com.axio.reelz.data.local.*
import com.axio.reelz.data.remote.api.TmdbApi
import com.axio.reelz.data.repository.MediaRepository
import com.axio.reelz.data.repository.BackendAuthRepository
import com.axio.reelz.data.repository.BackendSessionSource
import com.axio.reelz.data.repository.PaymentRepository
import com.axio.reelz.data.repository.SessionSource
import com.axio.reelz.data.repository.UserSessionRepository
import com.axio.reelz.remoteconfig.PremiumGate
import com.axio.reelz.remoteconfig.RemoteConfigRepository
import com.axio.reelz.stream.BackendStreamRepository
import com.axio.reelz.stream.StreamUrlCache
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
    // RemoteConfigRepository uses RemoteConfigCacheDao (Room) — no DataStore.

    @Provides @Singleton
    fun provideRemoteConfigRepository(
        cacheDao: RemoteConfigCacheDao,
        gson: Gson,
    ): RemoteConfigRepository = RemoteConfigRepository(cacheDao, gson)

    @Provides @Singleton
    fun providePremiumGate(remoteConfig: RemoteConfigRepository): PremiumGate =
        PremiumGate(remoteConfig)

    // ── OkHttp clients ────────────────────────────────────────────────────────

    @Provides @Singleton @Named("tmdb")
    fun provideTmdbOkHttp(remoteConfig: RemoteConfigRepository): OkHttpClient {
        val tmdbAuthInterceptor = Interceptor { chain ->
            // activeTmdbKey() returns null when config hasn't loaded yet (first install,
            // fallback has no keys). Poll with a short sleep rather than firing with an
            // empty key — an empty api_key always returns HTTP 401, which surfaces as a
            // visible error on every screen. We wait up to 5s total (50 x 100ms) which
            // is more than enough for a CDN sync to complete (~300-800ms on mobile).
            val key = run {
                var k = remoteConfig.activeTmdbKey()
                var waited = 0
                while (k == null && waited < 5_000) {
                    Thread.sleep(100)
                    waited += 100
                    k = remoteConfig.activeTmdbKey()
                }
                k
            }
            val original = chain.request()
            val url = original.url.newBuilder()
                .addQueryParameter("api_key", key.orEmpty())
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
    //
    // WAL (Write-Ahead Logging) is enabled for production performance:
    //   • Readers never block writers — concurrent reads during a download
    //     update don't stall the UI thread.
    //   • Writers never block readers — smooth scrolling even with active
    //     history/watchlist writes in the background.
    //   • WAL is the SQLite default recommendation for apps with > 1 writer
    //     or mixed read/write workloads. Android Room supports it natively.

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): ReelzDatabase =
        Room.databaseBuilder(ctx, ReelzDatabase::class.java, "reelz.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                MIGRATION_10_11, MIGRATION_11_12,
            )
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun provideWatchlistDao(db: ReelzDatabase)          = db.watchlistDao()
    @Provides fun provideWatchHistoryDao(db: ReelzDatabase)       = db.watchHistoryDao()
    @Provides fun provideLikedDao(db: ReelzDatabase)              = db.likedDao()
    @Provides fun provideSavedVideoDao(db: ReelzDatabase)         = db.savedVideoDao()
    @Provides fun provideCachedMediaDao(db: ReelzDatabase)        = db.cachedMediaDao()
    @Provides fun provideDownloadDao(db: ReelzDatabase)           = db.downloadDao()
    @Provides fun provideDownloadSubtitleDao(db: ReelzDatabase)   = db.downloadSubtitleDao()
    @Provides fun provideTransferDao(db: ReelzDatabase)           = db.transferDao()
    @Provides fun provideUserSessionDao(db: ReelzDatabase)        = db.userSessionDao()
    @Provides fun provideRecentSearchDao(db: ReelzDatabase)       = db.recentSearchDao()
    @Provides fun provideRemoteConfigCacheDao(db: ReelzDatabase)  = db.remoteConfigCacheDao()
    @Provides fun provideSectionWeightDao(db: ReelzDatabase)      = db.sectionWeightDao()

    // ── Repositories ──────────────────────────────────────────────────────────

    @Provides @Singleton
    fun provideMediaRepository(
        api: TmdbApi,
        cachedMediaDao: CachedMediaDao,
        watchlistDao: WatchlistDao,
        watchHistoryDao: WatchHistoryDao,
        likedDao: LikedDao,
        sectionWeightDao: SectionWeightDao,
    ) = MediaRepository(api, cachedMediaDao, watchlistDao, watchHistoryDao, likedDao, sectionWeightDao)

    @Provides @Singleton
    fun provideStreamUrlCache(): StreamUrlCache = StreamUrlCache()

    @Provides @Singleton
    fun provideBackendStreamRepository(
        remoteConfig: RemoteConfigRepository,
        urlCache: StreamUrlCache,
    ): BackendStreamRepository = BackendStreamRepository(remoteConfig, urlCache)

    // ── Premium session ───────────────────────────────────────────────────────
    //
    // UserSessionStore (DataStore "reelz_user_session") has been removed.
    // Room (UserSessionDao / user_session table) is the single source of truth
    // for the signed-in session. This eliminates the dual-write pattern and
    // the DataStore file handle.
    //
    // BackendSessionSource and PaymentRepository now take UserSessionDao
    // directly — they previously injected UserSessionStore only to call
    // sessionStore.load(), which is now replaced by dao.get().

    @Provides @Singleton
    fun provideSessionSource(
        remoteConfig: RemoteConfigRepository,
        sessionDao: UserSessionDao,           // Room — DataStore removed
    ): SessionSource = BackendSessionSource(remoteConfig, sessionDao)

    @Provides @Singleton
    fun provideBackendAuthRepository(
        remoteConfig: RemoteConfigRepository,
    ): BackendAuthRepository = BackendAuthRepository(remoteConfig)

    @Provides @Singleton
    fun provideUserSessionRepository(
        dao: UserSessionDao,
        sessionSource: SessionSource,
        backendAuth: BackendAuthRepository,
        premiumGate: PremiumGate,
    ): UserSessionRepository = UserSessionRepository(dao, sessionSource, backendAuth, premiumGate)

    @Provides @Singleton
    fun providePaymentRepository(
        remoteConfig: RemoteConfigRepository,
        sessionDao: UserSessionDao,           // Room — DataStore removed
    ): PaymentRepository = PaymentRepository(remoteConfig, sessionDao)
}

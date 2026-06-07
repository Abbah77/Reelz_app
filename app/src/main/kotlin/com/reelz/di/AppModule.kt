package com.reelz.di

import android.content.Context
import androidx.room.Room
import com.reelz.BuildConfig
import com.reelz.data.local.*
import com.reelz.data.remote.api.TmdbApi
import com.reelz.data.repository.MediaRepository
import com.reelz.scanner.DirectScanner
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

    /**
     * Appends the TMDB API key to every request to api.themoviedb.org.
     */
    private val tmdbAuthInterceptor = Interceptor { chain ->
        val original = chain.request()
        val url = original.url.newBuilder()
            .addQueryParameter("api_key", BuildConfig.TMDB_KEY)
            .build()
        chain.proceed(original.newBuilder().url(url).build())
    }

    /**
     * Primary singleton OkHttpClient for TMDB API (Retrofit).
     * Named "tmdb" to distinguish from the download client.
     */
    @Provides @Singleton @Named("tmdb")
    fun provideTmdbOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(tmdbAuthInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Tuned OkHttpClient for HLS/MP4 downloads:
     *  - ConnectionPool(10) keeps sockets warm between segment downloads
     *  - HTTP/2 multiplexing for parallel segments over one TCP connection
     *  - maxRequestsPerHost(6) allows 6 concurrent segment fetches per CDN host
     *  - retryOnConnectionFailure for transient network errors
     */
    @Provides @Singleton @Named("download")
    fun provideDownloadOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .dispatcher(Dispatcher().also { it.maxRequestsPerHost = 6 })
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton
    fun provideRetrofit(@Named("tmdb") client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl("https://api.themoviedb.org/3/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @Provides @Singleton
    fun provideTmdbApi(retrofit: Retrofit): TmdbApi = retrofit.create(TmdbApi::class.java)

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): ReelzDatabase =
        Room.databaseBuilder(ctx, ReelzDatabase::class.java, "reelz.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    @Provides fun provideWatchlistDao(db: ReelzDatabase)    = db.watchlistDao()
    @Provides fun provideWatchHistoryDao(db: ReelzDatabase) = db.watchHistoryDao()
    @Provides fun provideLikedDao(db: ReelzDatabase)        = db.likedDao()
    @Provides fun provideCachedMediaDao(db: ReelzDatabase)  = db.cachedMediaDao()
    @Provides fun provideDownloadDao(db: ReelzDatabase)     = db.downloadDao()
    @Provides fun provideTransferDao(db: ReelzDatabase)     = db.transferDao()

    @Provides @Singleton
    fun provideMediaRepository(
        api: TmdbApi,
        cachedMediaDao: CachedMediaDao,
        watchlistDao: WatchlistDao,
        watchHistoryDao: WatchHistoryDao,
        likedDao: LikedDao,
    ) = MediaRepository(api, cachedMediaDao, watchlistDao, watchHistoryDao, likedDao)

    @Provides @Singleton
    fun provideStreamResultCache() = StreamResultCache()

    @Provides @Singleton
    fun provideStreamEngine(
        @ApplicationContext ctx: Context,
        directScanner: DirectScanner,
        cache: StreamResultCache,
    ) = StreamEngine(ctx, directScanner, cache)
}

package com.reelz.di

import android.content.Context
import androidx.room.Room
import com.reelz.BuildConfig
import com.reelz.data.local.*
import com.reelz.data.remote.api.TmdbApi
import com.reelz.scanner.DirectScanner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val TMDB_BASE = "https://api.themoviedb.org/3/"

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient {
        val auth = Interceptor { chain ->
            val req = chain.request()
            val url = req.url.newBuilder()
                .addQueryParameter("api_key", BuildConfig.TMDB_KEY)
                .build()
            chain.proceed(
                req.newBuilder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                    .build()
            )
        }
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(logging)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }

    @Provides @Singleton
    fun provideRetrofit(okHttp: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(TMDB_BASE)
            .client(okHttp)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides @Singleton
    fun provideTmdbApi(retrofit: Retrofit): TmdbApi = retrofit.create(TmdbApi::class.java)

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): ReelzDatabase =
        Room.databaseBuilder(ctx, ReelzDatabase::class.java, "reelz_v2.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides @Singleton fun provideWatchlistDao(db: ReelzDatabase) = db.watchlistDao()
    @Provides @Singleton fun provideHistoryDao(db: ReelzDatabase)    = db.watchHistoryDao()
    @Provides @Singleton fun provideLikedDao(db: ReelzDatabase)       = db.likedDao()
    @Provides @Singleton fun provideCachedMediaDao(db: ReelzDatabase) = db.cachedMediaDao()
    @Provides @Singleton fun provideDownloadDao(db: ReelzDatabase)    = db.downloadDao()
    @Provides @Singleton fun provideTransferDao(db: ReelzDatabase)    = db.transferDao()

    @Provides @Singleton fun provideDirectScanner() = DirectScanner()
}

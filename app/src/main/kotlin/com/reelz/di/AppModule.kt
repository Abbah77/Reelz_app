package com.reelz.di

import android.content.Context
import androidx.room.Room
import com.reelz.BuildConfig
import com.reelz.data.local.ReelzDatabase
import com.reelz.data.local.WatchHistoryDao
import com.reelz.data.local.WatchlistDao
import com.reelz.data.remote.api.TmdbApi
import com.reelz.scanner.DirectScanner
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

    private const val TMDB_BASE = "https://api.themoviedb.org/3/"

    @Provides @Singleton
    fun provideOkHttp(): OkHttpClient {
        val auth = Interceptor { chain ->
            val req = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${BuildConfig.TMDB_KEY}")
                .addHeader("Accept", "application/json")
                .build()
            chain.proceed(req)
        }
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(TMDB_BASE)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    @Provides @Singleton
    fun provideTmdbApi(retrofit: Retrofit): TmdbApi =
        retrofit.create(TmdbApi::class.java)

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): ReelzDatabase =
        Room.databaseBuilder(ctx, ReelzDatabase::class.java, "reelz.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides @Singleton
    fun provideWatchlistDao(db: ReelzDatabase): WatchlistDao = db.watchlistDao()

    @Provides @Singleton
    fun provideHistoryDao(db: ReelzDatabase): WatchHistoryDao = db.watchHistoryDao()

    @Provides @Singleton
    fun provideDirectScanner(): DirectScanner = DirectScanner()
}

package com.reelz.di

import com.reelz.ads.AdEngine
import com.reelz.remoteconfig.PremiumGate
import com.reelz.remoteconfig.RemoteConfigRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AdModule {

    @Provides
    @Singleton
    fun provideAdEngine(remoteConfig: RemoteConfigRepository, premiumGate: PremiumGate): AdEngine =
        AdEngine(remoteConfig, premiumGate)
}

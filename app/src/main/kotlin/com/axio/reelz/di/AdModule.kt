package com.axio.reelz.di

import com.axio.reelz.ads.AdEngine
import com.axio.reelz.remoteconfig.PremiumGate
import com.axio.reelz.remoteconfig.RemoteConfigRepository
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

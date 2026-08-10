package com.axio.reelz.di

// AdEngine uses @Inject constructor and all its dependencies (RemoteConfigRepository,
// PremiumGate, AppPreferencesStore) are already provided by AppModule / as @Singleton
// bindings. No manual @Provides is needed — Hilt builds it automatically.
//
// The previous manual AdEngine(remoteConfig, premiumGate) call was missing the
// AppPreferencesStore parameter added to the constructor, causing a compile error.
// Removing the @Provides entirely is the correct fix: @Inject constructor + Hilt
// means you never need to list constructor args manually.

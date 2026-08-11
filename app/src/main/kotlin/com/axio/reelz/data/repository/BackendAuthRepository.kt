package com.axio.reelz.data.repository

// BackendAuthRepository is now absorbed into UserSessionRepository.
// All auth goes through ReelzApi.authWithGoogle() via UserSessionRepository.signInWithGoogle().
// This file is kept as an empty placeholder so existing import references compile.
// Delete it once all call sites are updated to use UserSessionRepository directly.

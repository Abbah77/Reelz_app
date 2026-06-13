package com.reelz.brain

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  AppNavigation — Onboarding Integration
 *
 *  Add this to AppNavigation.kt to show the onboarding screen on first launch.
 *  The onboarding screen is shown only ONCE:
 *  - When profile.isOnboarded == false AND totalInteractions == 0
 *  - After completing, user goes straight to Browse
 *  - Guest mode still works — onboarding can be skipped
 * ════════════════════════════════════════════════════════════════════════════
 */

/**
 * ── Add "onboarding" to your Route object ────────────────────────────────────
 *
 * In Route (wherever your route strings are defined):
 *
 *   object Route {
 *       // ...existing routes...
 *       const val ONBOARDING = "onboarding"
 *   }
 */

/**
 * ── Add this at the START of your NavHost composable in AppNavigation.kt ──────
 *
 * BEFORE the main navHost composable:
 *
 *   val tasteEngine: TasteEngine = hiltViewModel<OnboardingViewModel>()
 *       // or inject at the top level
 *
 * INSIDE NavHost:
 *
 *   composable(Route.ONBOARDING) {
 *       OnboardingScreen(
 *           onDone = {
 *               navController.navigate(Route.BROWSE) {
 *                   popUpTo(Route.ONBOARDING) { inclusive = true }
 *               }
 *           }
 *       )
 *   }
 *
 * THEN in MainActivity.kt or your start destination logic:
 *
 *   // Determine start destination
 *   val tasteEngine: TasteEngine by lazy { ... } // inject
 *   val startDest = if (!tasteEngine.profile.value.isOnboarded) {
 *       Route.ONBOARDING
 *   } else {
 *       Route.BROWSE
 *   }
 *   NavHost(startDestination = startDest, ...) { ... }
 */

/**
 * ── In ReelzApp.kt (Application class), add to onCreate: ─────────────────────
 *
 *   // Schedule taste sync
 *   TasteSyncWorker.schedule(this)
 *
 *   // Also add to existing schedules:
 *   ConfigSyncWorker.schedule(this)   // already exists
 *   TasteSyncWorker.schedule(this)    // NEW
 */

/**
 * ── When user logs in (in ProfileViewModel or wherever you handle auth): ───────
 *
 *   // Save auth token
 *   tasteAuthStore.saveToken(firebaseToken)
 *
 *   // Trigger immediate profile download (restores profile after reinstall)
 *   TasteSyncWorker.downloadNow(context)
 *
 * When user logs out:
 *
 *   tasteAuthStore.clearToken()
 *   // Don't clear local profile — guest mode continues with local data
 */

/**
 * ── Optional: upload dirty profile when app goes to background ───────────────
 *
 * In MainActivity.kt:
 *
 *   override fun onStop() {
 *       super.onStop()
 *       if (tasteAuthStore.isLoggedIn() && tasteEngine.isDirty) {
 *           TasteSyncWorker.uploadNow(this)
 *       }
 *   }
 */

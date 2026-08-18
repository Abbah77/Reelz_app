package com.axio.reelz.data.dto

// ── User state enum ────────────────────────────────────────────────────────────
// Guest = identical access to free user (schema v3 principle).
// Login is opt-in: only needed for cross-device sync and premium.
enum class UserState { GUEST, SIGNED_IN, PREMIUM_ACTIVE, PREMIUM_EXPIRED }

// ── Premium UI config — built from AppConfigDto.premium for PremiumScreen ─────
data class PremiumConfig(
    val monthlyPriceNgn: Long = 0,
    val yearlyPriceNgn: Long = 0,
    val paystackMonthlyUrl: String = "",
    val paystackYearlyUrl: String = "",
    val paymentNote: String = "",
)

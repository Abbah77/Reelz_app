package com.axio.reelz.data.dto

// ── Premium UI types ──────────────────────────────────────────────────────────
// These are domain-level types produced from AppConfigDto.premium for the
// PremiumScreen. They are NOT database entities or wire DTOs.

enum class UserState { GUEST, SIGNED_IN, PREMIUM_ACTIVE, PREMIUM_GRACE, PREMIUM_EXPIRED }

data class TierConfig(
    val maxResolution: String = "720p",
    val maxDownloads: Int = 2,
    val adsEnabled: Boolean = true,
    val subtitlesManualSearch: Boolean = false,
    val backgroundPlay: Boolean = false,
)

data class TiersConfig(
    val free: TierConfig = TierConfig(),
    val premium: TierConfig = TierConfig(
        maxResolution = "4K",
        maxDownloads = -1,
        adsEnabled = false,
        subtitlesManualSearch = true,
        backgroundPlay = true,
    ),
)

data class PremiumConfig(
    val monthlyPriceNgn: Long = 0,
    val yearlyPriceNgn: Long = 0,
    val paystackMonthlyUrl: String = "",
    val paystackYearlyUrl: String = "",
    val paymentNote: String = "",
)

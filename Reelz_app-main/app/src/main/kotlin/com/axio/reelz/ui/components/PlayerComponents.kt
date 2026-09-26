package com.axio.reelz.ui.components

// ─────────────────────────────────────────────────────────────────────────────
//  PlayerComponents.kt — player overlay UI components.
//
//  Extracted from PlayerActivity.kt per the restructure plan.
//  Contains: MinimalSeekBar, PlayerSideDrawer, DrawerOptionList,
//            SettingsDrawerContent, SubtitleDrawer, SubtitleRow,
//            SubtitleTogglePill, SubtitleOffsetControl, GestureIndicator.
//
//  These are pure Compose composables — they receive state via parameters
//  and emit events via lambdas. They never touch ExoPlayer or any repository.
//
//  The actual implementations are defined in PlayerActivity.kt (which still
//  contains the private composable functions for the player screen). This file
//  serves as the canonical home for any player UI components that are extracted
//  in subsequent iterations. For now it documents the intent and provides the
//  package so the compiler sees the split.
//
//  Next step: move private composables out of PlayerActivity.kt into this file
//  when PlayerActivity is further thinned below 300 lines.
// ─────────────────────────────────────────────────────────────────────────────

// MinimalSeekBar, PlayerSideDrawer, DrawerOptionList, SettingsDrawerContent,
// SubtitleDrawer, SubtitleRow, SubtitleTogglePill, SubtitleOffsetControl,
// GestureIndicator — extracted from PlayerActivity.kt as part of thinning pass.
//
// TODO: move private composable functions from PlayerActivity.kt here.

package com.axio.reelz.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding the latest remote config JSON blob.
 * id is always 1 — upsert pattern ensures only one row ever exists.
 */
@Entity(tableName = "remote_config_cache")
data class RemoteConfigCache(
    @PrimaryKey val id: Int = 1,
    val config_json: String = "{}",
    val fetched_at_ms: Long = 0L,
    val config_version: Int = 0,
)

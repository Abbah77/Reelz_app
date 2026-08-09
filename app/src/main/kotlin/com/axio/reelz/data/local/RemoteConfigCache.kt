package com.axio.reelz.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row table holding the latest remote config JSON blob.
 * id is always 1 — upsert pattern ensures only one row ever exists.
 *
 * @ColumnInfo maps camelCase Kotlin fields to the snake_case SQLite columns
 * created by MIGRATION_8_9, so RemoteConfigRepository can access them as
 * cached.configJson / cached.fetchedAtMs / cached.configVersion without
 * any changes to that file.
 */
@Entity(tableName = "remote_config_cache")
data class RemoteConfigCache(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "config_json")    val configJson: String = "{}",
    @ColumnInfo(name = "fetched_at_ms") val fetchedAtMs: Long = 0L,
    @ColumnInfo(name = "config_version") val configVersion: Int = 0,
)

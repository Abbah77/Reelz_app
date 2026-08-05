package com.axio.reelz.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Room entity for caching remote config in SQLite.
 *
 * Replaces the DataStore-based config cache (reelz_remote_cfg).
 * Using Room here gives us:
 *   • DB migrations (v8 → v9+) — config schema can evolve without wiping cache
 *   • Atomic writes via Room transactions — no corrupt JSON on crash mid-write
 *   • Consistency — config lives in the same database as everything else
 *
 * SINGLETON ROW: always id = 1. Insert replaces on conflict.
 */
@Entity(tableName = "remote_config_cache")
data class RemoteConfigCache(
    @PrimaryKey
    val id: Int = 1,               // always 1 — singleton row

    @ColumnInfo(name = "config_json")
    val configJson: String,        // full JSON blob from backend

    @ColumnInfo(name = "fetched_at_ms")
    val fetchedAtMs: Long,         // epoch ms when fetched

    @ColumnInfo(name = "config_version")
    val configVersion: Int = 0,    // server-side version for future delta sync
)

@Dao
interface RemoteConfigCacheDao {

    @Query("SELECT * FROM remote_config_cache WHERE id = 1 LIMIT 1")
    suspend fun get(): RemoteConfigCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: RemoteConfigCache)

    @Query("DELETE FROM remote_config_cache")
    suspend fun clear()
}

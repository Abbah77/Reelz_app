package com.axio.reelz.transfer

import com.axio.reelz.core.database.TransferDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TransferRepository — transfer state and history backed by Room.
 *
 * Dependency direction: TransferRepository → Room TransferDao. Never touches UI.
 */
@Singleton
class TransferRepository @Inject constructor(
    private val dao: TransferDao,
) {
    fun observeHistory() = dao.observeAll()

    suspend fun recordTransfer(record: TransferRecord) = withContext(Dispatchers.IO) {
        dao.insert(record.toRow())
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clear()
    }
}

/** Domain model for a completed transfer */
data class TransferRecord(
    val id: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val direction: TransferDirection,
    val remoteDeviceName: String,
    val completedAtMs: Long = System.currentTimeMillis(),
)

enum class TransferDirection { SENT, RECEIVED }

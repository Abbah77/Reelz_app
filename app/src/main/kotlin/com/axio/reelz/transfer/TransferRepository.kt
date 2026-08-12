package com.axio.reelz.transfer

import com.axio.reelz.core.database.TransferDao
import com.axio.reelz.core.database.TransferRecord as DbTransferRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferRepository @Inject constructor(
    private val dao: TransferDao,
) {
    fun observeHistory() = dao.getAll()

    suspend fun recordTransfer(record: TransferRecord) = withContext(Dispatchers.IO) {
        dao.insert(
            DbTransferRecord(
                id        = record.id,
                fileName  = record.fileName,
                sizeBytes = record.fileSizeBytes,
                direction = record.direction.name,
                peerName  = record.remoteDeviceName,
                status    = "DONE",
                createdAt = record.completedAtMs,
            )
        )
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clear()
    }
}

data class TransferRecord(
    val id: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val direction: TransferDirection,
    val remoteDeviceName: String,
    val completedAtMs: Long = System.currentTimeMillis(),
)

enum class TransferDirection { SENT, RECEIVED }

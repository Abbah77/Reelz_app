package com.axio.reelz.transfer

import com.axio.reelz.core.database.TransferDao
import com.axio.reelz.core.database.TransferRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferRepository @Inject constructor(
    private val dao: TransferDao,
) {
    fun observeHistory(): Flow<List<TransferRecord>> = dao.getAll()

    suspend fun recordTransfer(record: TransferRecord) = withContext(Dispatchers.IO) {
        dao.insert(record)
    }

    suspend fun deleteRecord(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clear()
    }

    /**
     * Check if we already sent this exact content to a specific peer.
     * Returns the existing record if found, null otherwise.
     * Used by TransferManager.checkCanSend() to warn the user before re-sending.
     */
    suspend fun findSentRecord(
        mediaId: String,
        season:  Int,
        episode: Int,
        quality: String,
        peerId:  String,
    ): TransferRecord? = withContext(Dispatchers.IO) {
        dao.findSentRecord(mediaId, season, episode, quality, peerId)
    }

    /**
     * Check if we already received this exact content from anyone.
     * Used as a secondary guard in TransferManager.registerReceivedContent().
     */
    suspend fun findReceivedRecord(
        mediaId: String,
        season:  Int,
        episode: Int,
        quality: String,
    ): TransferRecord? = withContext(Dispatchers.IO) {
        dao.findReceivedRecord(mediaId, season, episode, quality)
    }
}

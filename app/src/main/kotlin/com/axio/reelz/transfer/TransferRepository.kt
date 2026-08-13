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
}

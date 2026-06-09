package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepository(private val chatDao: ChatDao) {
    val allSessions: Flow<List<ChatSessionEntity>> = chatDao.getAllSessions()

    fun getMessagesForSession(sessionId: Long): Flow<List<MessageEntity>> = chatDao.getMessagesForSession(sessionId)

    suspend fun createNewSession(title: String): Long {
        val session = ChatSessionEntity(title = title)
        return chatDao.insertSession(session)
    }

    suspend fun insertMessage(message: MessageEntity) = chatDao.insertMessage(message)

    suspend fun clearHistoryForSession(sessionId: Long) = chatDao.clearHistoryForSession(sessionId)
    suspend fun deleteSession(sessionId: Long) {
        chatDao.deleteSession(sessionId)
        chatDao.clearHistoryForSession(sessionId)
    }
}

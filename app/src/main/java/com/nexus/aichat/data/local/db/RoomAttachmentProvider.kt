package com.nexus.aichat.data.local.db

import com.nexus.aichat.data.local.db.dao.AttachmentDao
import com.nexus.aichat.core.ai.spi.AttachmentProvider
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.data.local.mapper.Mappers.toDomain

/**
 * Bridges the document tool to the attachment table.
 *
 * Extraction results are written back to the row, so `read_document` on a 200-page PDF costs one
 * parse for the lifetime of the conversation instead of one per tool call.
 */
class RoomAttachmentProvider @javax.inject.Inject constructor(
    private val attachmentDao: AttachmentDao,
) : AttachmentProvider {

    override suspend fun attachmentById(attachmentId: String): Attachment? =
        attachmentDao.byId(attachmentId)?.toDomain()

    override suspend fun attachmentsForConversation(conversationId: String): List<Attachment> =
        attachmentDao.byConversation(conversationId).map { it.toDomain() }

    override suspend fun cacheExtraction(attachmentId: String, text: String, pageCount: Int?, warning: String?) {
        attachmentDao.cacheExtraction(attachmentId, text, pageCount, warning)
    }
}

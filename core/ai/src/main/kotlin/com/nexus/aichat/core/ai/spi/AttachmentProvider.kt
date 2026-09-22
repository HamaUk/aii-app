package com.nexus.aichat.core.ai.spi

import com.nexus.aichat.core.model.Attachment

/**
 * Lets the document tool resolve attachments by id, name or conversation without the engine
 * depending on Room.
 */
interface AttachmentProvider {

    suspend fun attachmentById(attachmentId: String): Attachment?

    suspend fun attachmentsForConversation(conversationId: String): List<Attachment>

    /** Persists extraction results so a document is parsed exactly once. */
    suspend fun cacheExtraction(attachmentId: String, text: String, pageCount: Int?, warning: String?)
}

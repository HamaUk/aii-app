package com.nexus.aichat.data.local.mapper

import com.nexus.aichat.data.local.db.entity.AttachmentEntity
import com.nexus.aichat.data.local.db.entity.ConversationEntity
import com.nexus.aichat.data.local.db.entity.MessageEntity
import com.nexus.aichat.data.local.db.entity.ProviderEntity
import com.nexus.aichat.data.local.db.entity.SystemRuleEntity
import com.nexus.aichat.core.ai.util.NexusJson
import com.nexus.aichat.core.model.AgentOptions
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.AttachmentKind
import com.nexus.aichat.core.model.AuthConfig
import com.nexus.aichat.core.model.Conversation
import com.nexus.aichat.core.model.Message
import com.nexus.aichat.core.model.MessagePart
import com.nexus.aichat.core.model.MessageRole
import com.nexus.aichat.core.model.MessageStatus
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.core.model.SystemRule
import com.nexus.aichat.core.model.TokenUsage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Domain <-> entity mapping.
 *
 * Everything JSON-encoded goes through [NexusJson] with a defensive fallback: a row written by a
 * newer app version and then read by an older one must degrade to a sensible default rather than
 * crash the chat list. That is the whole reason these are `decode(raw) { fallback }` and not `!!`.
 */
object Mappers {

    private val json = NexusJson.instance
    private val stringList = ListSerializer(String.serializer())
    private val modelList = ListSerializer(ModelInfo.serializer())
    private val partList = ListSerializer(MessagePart.serializer())
    private val keyValueMap = kotlinx.serialization.builtins.MapSerializer(
        String.serializer(),
        String.serializer(),
    )

    private inline fun <reified T> decode(raw: String?, fallback: T): T =
        raw?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: fallback

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    // --- Conversation -------------------------------------------------------------------------

    fun ConversationEntity.toDomain() = Conversation(
        id = id,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        providerId = providerId,
        modelId = modelId,
        systemRuleIds = decode(systemRuleIdsJson, emptyList<String>()),
        systemPromptOverride = systemPromptOverride,
        temperature = temperature,
        topP = topP,
        maxOutputTokens = maxOutputTokens,
        agent = decode(agentOptionsJson, AgentOptions()),
        activeLeafMessageId = activeLeafMessageId,
        isPinned = isPinned,
        isArchived = isArchived,
        totalUsage = decode(totalUsageJson, TokenUsage()),
        lastError = lastError,
    )

    fun Conversation.toEntity() = ConversationEntity(
        id = id,
        title = title,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        providerId = providerId,
        modelId = modelId,
        systemPromptOverride = systemPromptOverride,
        systemRuleIdsJson = encode(systemRuleIds),
        temperature = temperature,
        topP = topP,
        maxOutputTokens = maxOutputTokens,
        agentOptionsJson = encode(agent),
        activeLeafMessageId = activeLeafMessageId,
        isPinned = isPinned,
        isArchived = isArchived,
        totalUsageJson = encode(totalUsage),
        lastError = lastError,
    )

    // --- Message ------------------------------------------------------------------------------

    fun MessageEntity.toDomain() = Message(
        id = id,
        conversationId = conversationId,
        role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.ASSISTANT),
        parts = decode(partsJson, emptyList<MessagePart>()),
        parentId = parentId,
        siblingIndex = siblingIndex,
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.COMPLETE),
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        providerId = providerId,
        modelId = modelId,
        usage = usageJson?.let { decode<TokenUsage?>(it, null) },
        error = error,
        agentRunId = agentRunId,
        isPinned = isPinned,
        finishReason = finishReason,
    )

    fun Message.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role.name,
        partsJson = encode(parts),
        parentId = parentId,
        siblingIndex = siblingIndex,
        status = status.name,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        providerId = providerId,
        modelId = modelId,
        usageJson = usage?.let { encode(it) },
        error = error,
        agentRunId = agentRunId,
        isPinned = isPinned,
        finishReason = finishReason,
    )

    // --- Attachment ---------------------------------------------------------------------------

    fun AttachmentEntity.toDomain() = Attachment(
        id = id,
        kind = runCatching { AttachmentKind.valueOf(kind) }.getOrDefault(AttachmentKind.OTHER),
        uri = uri,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        pageCount = pageCount,
        extractedText = extractedText,
        extractionError = extractionError,
        createdAtEpochMs = createdAtEpochMs,
    )

    fun Attachment.toEntity(conversationId: String? = null, messageId: String? = null) = AttachmentEntity(
        id = id,
        conversationId = conversationId,
        messageId = messageId,
        kind = kind.name,
        uri = uri,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        pageCount = pageCount,
        extractedText = extractedText,
        extractionError = extractionError,
        createdAtEpochMs = createdAtEpochMs,
    )

    // --- Provider -----------------------------------------------------------------------------

    fun ProviderEntity.toDomain() = ProviderConfig(
        id = id,
        displayName = displayName,
        protocol = runCatching { ProviderProtocol.valueOf(protocol) }.getOrDefault(ProviderProtocol.OPENAI_COMPATIBLE),
        baseUrl = baseUrl,
        auth = decode(authJson, AuthConfig(scheme = com.nexus.aichat.core.model.AuthScheme.NONE, vaultKey = null)),
        extraHeaders = decode(extraHeadersJson, emptyMap()),
        modelsPath = modelsPath,
        chatPathOverride = chatPathOverride,
        requestTimeoutMs = requestTimeoutMs,
        connectTimeoutMs = connectTimeoutMs,
        supportsNativeTools = supportsNativeTools,
        includeStreamUsage = includeStreamUsage,
        requestBodyTemplate = requestBodyTemplate,
        responseTextPath = responseTextPath,
        presetId = presetId,
        isEnabled = isEnabled,
        models = decode(modelsJson, emptyList<ModelInfo>()),
        selectedModelId = selectedModelId,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedEpochMs = lastUsedEpochMs,
        isBuiltInPreset = isBuiltInPreset,
        notes = notes,
    )

    fun ProviderConfig.toEntity() = ProviderEntity(
        id = id,
        displayName = displayName,
        protocol = protocol.name,
        baseUrl = baseUrl,
        authJson = encode(auth),
        extraHeadersJson = json.encodeToString(keyValueMap, extraHeaders),
        modelsPath = modelsPath,
        chatPathOverride = chatPathOverride,
        requestTimeoutMs = requestTimeoutMs,
        connectTimeoutMs = connectTimeoutMs,
        supportsNativeTools = supportsNativeTools,
        includeStreamUsage = includeStreamUsage,
        requestBodyTemplate = requestBodyTemplate,
        responseTextPath = responseTextPath,
        presetId = presetId,
        isEnabled = isEnabled,
        modelsJson = json.encodeToString(modelList, models),
        selectedModelId = selectedModelId,
        createdAtEpochMs = createdAtEpochMs,
        lastUsedEpochMs = lastUsedEpochMs,
        isBuiltInPreset = isBuiltInPreset,
        notes = notes,
    )

    // --- System rules -------------------------------------------------------------------------

    fun SystemRuleEntity.toDomain() = SystemRule(
        id = id,
        title = title,
        body = body,
        appliesGlobally = appliesGlobally,
        isEnabled = isEnabled,
        isBuiltIn = isBuiltIn,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    fun SystemRule.toEntity() = SystemRuleEntity(
        id = id,
        title = title,
        body = body,
        appliesGlobally = appliesGlobally,
        isEnabled = isEnabled,
        isBuiltIn = isBuiltIn,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    /** Seed data: the built-in personas ship as editable rows so users can fork them. */
    fun builtInRules(): List<SystemRuleEntity> =
        com.nexus.aichat.core.model.PersonaPresets.ALL.map { it.toEntity() }
}

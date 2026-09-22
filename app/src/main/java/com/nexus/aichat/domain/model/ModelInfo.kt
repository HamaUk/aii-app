package com.nexus.aichat.domain.model

import com.nexus.aichat.core.model.ModelCapability
import com.nexus.aichat.core.model.ModelInfo as CoreModelInfo
import com.nexus.aichat.core.model.ModelSource
import com.nexus.aichat.core.util.toContextLabel

typealias ModelInfo = CoreModelInfo
typealias ModelSourceX = ModelSource

/** Capability + latency presentation, used by the model switcher and the health list. */
object ModelPresentation {

    /** Short badge text: "vision", "tools", "thinking". */
    fun badges(model: ModelInfo): List<String> = buildList {
        if (model.supportsVision) add("vision")
        if (model.supportsTools) add("tools")
        if (model.supportsReasoning) add("thinking")
        if (ModelCapability.LONG_CONTEXT in model.capabilities) add("long context")
    }

    fun contextLabel(model: ModelInfo): String? = model.contextWindow?.toContextLabel()

    /** Latency chip: green under 1s, amber under 3s, red above or dead. */
    fun latencyLabel(model: ModelInfo): String? {
        val latency = model.latencyMs
        return when {
            !model.isAlive -> "unavailable"
            latency == null -> null
            else -> "$latency ms"
        }
    }

    fun isLatencyHealthy(model: ModelInfo): Boolean? {
        val latency = model.latencyMs
        return when {
            !model.isAlive -> false
            latency == null -> null
            else -> latency < 3_000
        }
    }

    /** The model the UI should preselect for a fresh conversation. */
    fun bestAvailable(models: List<ModelInfo>): ModelInfo? =
        models.filter { it.isAlive }.minByOrNull { it.latencyMs ?: Long.MAX_VALUE } ?: models.firstOrNull()
}

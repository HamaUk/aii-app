package com.nexus.aichat.core.model

import kotlinx.serialization.Serializable

/**
 * A reusable custom instruction block. Global rules apply to every conversation; a rule can also be
 * attached per-chat, and a per-chat free-text override always wins as the final word.
 */
@Serializable
data class SystemRule(
    val id: String,
    val title: String,
    val body: String,
    val appliesGlobally: Boolean = true,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
)

/**
 * How the final system prompt is assembled. Precedence (later wins):
 *
 *   1. built-in harness contract (tool protocol, stop conditions)
 *   2. enabled global rules, in order
 *   3. rules attached to the conversation
 *   4. conversation.systemPromptOverride
 *   5. runtime tool instructions for the tools actually enabled this run
 */
object SystemPromptComposer {

    data class Sources(
        val harnessContract: String?,
        val globalRules: List<SystemRule>,
        val conversationRules: List<SystemRule>,
        val conversationOverride: String?,
        val toolInstructions: String?,
    )

    fun compose(sources: Sources): String = buildString {
        sources.harnessContract?.takeIf { it.isNotBlank() }?.let {
            appendLine(it.trim())
            appendLine()
        }
        (sources.globalRules + sources.conversationRules)
            .filter { it.isEnabled && it.body.isNotBlank() }
            .forEach { rule ->
                appendLine("## ${rule.title.ifBlank { "Instruction" }}")
                appendLine(rule.body.trim())
                appendLine()
            }
        sources.conversationOverride?.takeIf { it.isNotBlank() }?.let {
            appendLine("## Chat-specific instructions (highest priority)")
            appendLine(it.trim())
            appendLine()
        }
        sources.toolInstructions?.takeIf { it.isNotBlank() }?.let {
            appendLine(it.trim())
        }
    }.trim()
}

object PersonaPresets {

    val ALL: List<SystemRule> = listOf(
        SystemRule(
            id = "persona-default",
            title = "Grounded assistant",
            body = "Answer directly and completely. Prefer concrete, verifiable statements over hedging. " +
                "If you are uncertain, say so plainly and state what would resolve the uncertainty. " +
                "Never invent file paths, API names, URLs or numbers.",
            isBuiltIn = true,
        ),
        SystemRule(
            id = "persona-engineer",
            title = "Senior engineer",
            body = "You are a principal-level software engineer. Give production-grade answers: idiomatic, " +
                "compiled-code-correct, with error paths handled. Lead with the code, then explain trade-offs " +
                "in at most three bullets. Call out anything that will break at scale.",
            isBuiltIn = true,
        ),
        SystemRule(
            id = "persona-researcher",
            title = "Research analyst",
            body = "You are a research analyst. Always fetch primary sources with the web tools before " +
                "asserting facts about the world. Cite every non-obvious claim inline as a markdown link. " +
                "Separate observation from inference explicitly.",
            isBuiltIn = true,
        ),
        SystemRule(
            id = "persona-editor",
            title = "Ruthless editor",
            body = "Cut filler. No preamble, no restating the question, no closing summary unless asked. " +
                "Maximum information density per sentence. Prefer short paragraphs and lists over walls of text.",
            isBuiltIn = true,
        ),
        SystemRule(
            id = "persona-tutor",
            title = "Socratic tutor",
            body = "Teach by building understanding step by step. Check for prerequisites before assuming " +
                "knowledge, use one concrete example per concept, and end with a single question that tests " +
                "understanding.",
            isBuiltIn = true,
        ),
    )
}

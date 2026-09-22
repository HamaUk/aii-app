package com.nexus.aichat

import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.AgentPhase
import com.nexus.aichat.core.model.AgentStopReason
import com.nexus.aichat.core.model.ToolCallRequest
import com.nexus.aichat.core.model.ToolCallStatus
import com.nexus.aichat.core.model.ToolResult
import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.domain.agent.ThoughtProcessor
import com.nexus.aichat.domain.model.AgentStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event-folding tests.
 *
 * The processor is the one place where a streaming run becomes a stable rendered structure, so these tests
 * pin the invariants that make the UI calm: deltas mutate one node instead of appending many, a revision
 * replaces rather than concatenates, and tool milestones are keyed by call id so a retry updates its card.
 */
class ThoughtProcessorTest {

    private val processor = ThoughtProcessor()
    private fun accumulator() = processor.newAccumulator("run-1")

    @Test
    fun `thought deltas accumulate into a single streaming node`() {
        val acc = accumulator()
        acc.onEvent(AgentEvent.RunStarted("run-1", "c1", "p1", "m1", com.nexus.aichat.core.model.AgentMode.REACT, 0))
        acc.onEvent(AgentEvent.ThoughtDelta("run-1", 0, "Let me "))
        acc.onEvent(AgentEvent.ThoughtDelta("run-1", 0, "check."))
        val trace = acc.snapshot()
        val thinking = trace.steps.filterIsInstance<AgentStep.Thinking>().single()
        assertEquals("Let me check.", thinking.text)
        assertTrue(thinking.isStreaming)
    }

    @Test
    fun `thought completion closes the node with timing`() {
        val acc = accumulator()
        acc.onEvent(AgentEvent.ThoughtDelta("run-1", 0, "hmm"))
        acc.onEvent(AgentEvent.ThoughtCompleted("run-1", 0, "hmm", durationMs = 900, tokens = 42))
        val thinking = acc.snapshot().steps.filterIsInstance<AgentStep.Thinking>().single()
        assertFalse(thinking.isStreaming)
        assertEquals(900L, thinking.durationMs)
        assertEquals(42, thinking.tokens)
    }

    @Test
    fun `answer revision replaces the streamed text instead of appending`() {
        val acc = accumulator()
        acc.onEvent(AgentEvent.AnswerDelta("run-1", "I will call "))
        acc.onEvent(AgentEvent.AnswerDelta("run-1", "web_fetch."))
        acc.onEvent(AgentEvent.AnswerRevised("run-1", fullText = "", reason = "tool call lifted out"))
        assertEquals("", acc.snapshot().finalAnswer)
    }

    @Test
    fun `tool lifecycle updates one milestone keyed by call id`() {
        val acc = accumulator()
        val request = ToolCallRequest(callId = "call_1", toolName = "web_fetch", argumentsJson = """{"url":"https://x"}""")
        acc.onEvent(AgentEvent.ToolCallProposed("run-1", 0, request, requiresApproval = true, approvalReason = "network"))
        assertTrue(acc.snapshot().awaitingApproval != null)

        acc.onEvent(AgentEvent.ToolCallStarted("run-1", request))
        assertNull(acc.snapshot().awaitingApproval)
        assertEquals(
            ToolCallStatus.RUNNING,
            acc.snapshot().steps.filterIsInstance<AgentStep.ToolInvocation>().single().status,
        )

        acc.onEvent(
            AgentEvent.ToolCallFinished(
                "run-1",
                ToolResult(callId = "call_1", toolName = "web_fetch", content = "page text", preview = "page text"),
            ),
        )
        val milestone = acc.snapshot().steps.filterIsInstance<AgentStep.ToolInvocation>().single()
        assertEquals(ToolCallStatus.SUCCEEDED, milestone.status)
        assertEquals(1, acc.snapshot().toolCallCount)
    }

    @Test
    fun `clarification parks the run and clears when finished`() {
        val acc = accumulator()
        acc.onEvent(AgentEvent.ClarificationRequested("run-1", question = "Which repo?", requestId = "req-1"))
        val awaiting = acc.snapshot().awaitingClarification
        assertEquals("Which repo?", awaiting?.question)
        assertEquals(AgentPhase.WAITING_FOR_USER, acc.status().phase)
    }

    @Test
    fun `run finished records the terminal reason and usage`() {
        val acc = accumulator()
        acc.onEvent(
            AgentEvent.RunFinished(
                runId = "run-1",
                reason = AgentStopReason.MAX_STEPS_REACHED,
                steps = 8,
                usage = TokenUsage(inputTokens = 100, outputTokens = 50, totalTokens = 150),
                finalMessageId = "m2",
                durationMs = 4_000,
            ),
        )
        assertEquals(AgentStopReason.MAX_STEPS_REACHED, acc.terminalReason)
        assertEquals(150, acc.status().usage.totalTokens)
        assertFalse(acc.status().isRunning)
    }
}

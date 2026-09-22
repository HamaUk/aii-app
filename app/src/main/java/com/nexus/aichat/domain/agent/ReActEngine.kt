package com.nexus.aichat.domain.agent

import com.nexus.aichat.core.ai.agent.AgentOrchestrator
import com.nexus.aichat.core.ai.agent.ToolApprovalDecision
import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.AgentRunRequest
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The reasoning engine, as the app sees it.
 *
 * `:core:ai` implements the loop (budgets, approvals, dedupe, inline tool recovery); this class is the
 * app's handle on it: one place that starts, stops and steers a run, so no ViewModel ever touches the
 * core orchestrator directly and swapping the harness later is a one-file change.
 */
@Singleton
class ReActEngine @Inject constructor(
    private val orchestrator: AgentOrchestrator,
) {

    /**
     * Runs one agent turn. Cold flow: collection starts the work, cancellation aborts the in-flight
     * HTTP stream and every parked tool await.
     */
    fun run(request: AgentRunRequest): Flow<AgentEvent> = orchestrator.run(request)

    /** Answers a paused `ask_user` question so the loop resumes where it stopped. */
    suspend fun answerClarification(runId: String, requestId: String, answer: String): Boolean =
        orchestrator.submitClarification(runId, requestId, answer)

    /** Resolves a pending tool approval (approve once, approve for the rest of the run, or reject). */
    suspend fun resolveApproval(
        runId: String,
        callId: String,
        decision: ToolApprovalDecision,
    ): Boolean = orchestrator.resolveApproval(runId, callId, decision)

    /** Immediate stop. Safe to call from the UI thread; it only signals cancellation. */
    fun cancel(runId: String) = orchestrator.cancel(runId)

    fun isRunning(runId: String): Boolean = orchestrator.isRunning(runId)

    /** Called from `onCleared`/process death paths: nothing may keep streaming in the background. */
    fun cancelAll() = orchestrator.cancelAll()
}

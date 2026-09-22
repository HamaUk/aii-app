package com.nexus.aichat.domain.agent

import com.nexus.aichat.core.ai.agent.AgentOrchestrator as CoreAgentOrchestrator
import com.nexus.aichat.core.ai.agent.ToolApprovalDecision

/**
 * The agent loop, under its app-side name.
 *
 * The implementation is `:core:ai`'s `AgentOrchestrator` - a pure-JVM class with no Android dependency, which
 * is what makes the whole ReAct loop unit-testable with a scripted fake client. The app refers to it through
 * this alias (or through [ReActEngine], which wraps it) so that a future harness swap - a planner, a
 * graph-based executor, a different tool protocol - is a change in one module rather than at every call site.
 *
 * Contract, restated here because it is the contract the UI is built against:
 *  - `run(request)` returns a cold `Flow<AgentEvent>`: collection starts the work, cancellation aborts the
 *    socket and every parked tool wait;
 *  - approvals and clarifications park the loop and resume from the same point - never restart;
 *  - tool failures become observations the model can react to; only auth and transport failures abort;
 *  - budgets (steps, tool calls, wall clock) end a run *coherently*, with a reason the UI can explain.
 */
typealias AgentOrchestrator = CoreAgentOrchestrator

/** Re-exported so callers need not import the core module to answer an approval prompt. */
typealias ToolApprovalChoice = ToolApprovalDecision

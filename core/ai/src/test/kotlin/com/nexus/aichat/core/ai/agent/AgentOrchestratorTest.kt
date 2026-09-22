package com.nexus.aichat.core.ai.agent

import com.nexus.aichat.core.ai.engine.ChatCall
import com.nexus.aichat.core.ai.engine.ChatCompletionClient
import com.nexus.aichat.core.ai.engine.StreamEvent
import com.nexus.aichat.core.ai.protocol.ProviderChatResponse
import com.nexus.aichat.core.ai.spi.BinaryResolver
import com.nexus.aichat.core.ai.spi.ImageScaler
import com.nexus.aichat.core.ai.spi.InMemorySecretProvider
import com.nexus.aichat.core.common.di.NexusDispatchers
import com.nexus.aichat.core.common.logging.NoOpLogger
import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.common.time.FakeTimeProvider
import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.AgentMode
import com.nexus.aichat.core.model.AgentOptions
import com.nexus.aichat.core.model.AgentRunRequest
import com.nexus.aichat.core.model.AgentStopReason
import com.nexus.aichat.core.model.ApprovalRequirement
import com.nexus.aichat.core.model.AuthConfig
import com.nexus.aichat.core.model.AuthScheme
import com.nexus.aichat.core.model.Message
import com.nexus.aichat.core.model.MessagePart
import com.nexus.aichat.core.model.MessageRole
import com.nexus.aichat.core.model.ModelCapability
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.core.model.ToolApprovalPolicy
import com.nexus.aichat.core.model.ToolCallRequest
import com.nexus.aichat.core.model.ToolCallStatus
import com.nexus.aichat.core.model.ToolCategory
import com.nexus.aichat.core.model.ToolResult
import com.nexus.aichat.core.model.ToolSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The harness is tested against a scripted client, which is the whole reason the orchestrator depends
 * on [ChatCompletionClient] rather than on the HTTP engine. These tests exercise the real loop:
 * reasoning capture, tool gating, approvals, clarification pauses, duplicate interception and budget.
 */
class AgentOrchestratorTest {

    // -----------------------------------------------------------------------------------------
    // Fakes
    // -----------------------------------------------------------------------------------------

    private class TestDispatchers(private val dispatcher: CoroutineDispatcher) : NexusDispatchers {
        override val default get() = dispatcher
        override val io get() = dispatcher
        override val main get() = dispatcher
        override val toolPool get() = dispatcher
    }

    private class ScriptedClient(private val script: MutableList<List<StreamEvent>>) : ChatCompletionClient {
        val calls = mutableListOf<ChatCall>()

        override fun stream(call: ChatCall): Flow<StreamEvent> {
            calls += call
            val events = if (script.isNotEmpty()) script.removeAt(0) else listOf(finished(""))
            return flow { events.forEach { emit(it) } }
        }

        override suspend fun complete(call: ChatCall): NexusResult<ProviderChatResponse> =
            NexusResult.Success(ProviderChatResponse(""))
    }

    private class RecordingTool(
        override val spec: ToolSpec,
        private val output: String = "tool output",
    ) : AgentTool {
        var executions = 0
            private set

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            executions++
            return ToolResult(callId = "c", toolName = spec.name, content = output, preview = output.take(40))
        }
    }

    private class NullBinaryResolver : BinaryResolver {
        override suspend fun bytesFor(uri: String): ByteArray? = null
        override fun mimeTypeOf(uri: String): String? = null
    }

    private class NullImageScaler : ImageScaler {
        override suspend fun scaleToJpeg(uri: String, maxEdgePx: Int, quality: Int): ByteArray? = null
    }

    // -----------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------

    private val provider = ProviderConfig(
        id = "p1",
        displayName = "Test Provider",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        baseUrl = "https://api.test/v1",
        auth = AuthConfig(AuthScheme.BEARER, vaultKey = "test-key"),
    )

    private val model = ModelInfo(
        id = "test-model",
        providerId = "p1",
        capabilities = setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING),
    )

    private fun runRequest(
        options: AgentOptions = AgentOptions(mode = AgentMode.REACT, maxSteps = 4),
    ) = AgentRunRequest(
        runId = "run-1",
        conversationId = "conv-1",
        provider = provider,
        model = model,
        history = emptyList(),
        userMessage = Message(
            id = "u1",
            conversationId = "conv-1",
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text("What does example.com say?")),
        ),
        personaSystemPrompt = null,
        options = options,
    )

    private fun orchestrator(
        client: ChatCompletionClient,
        tools: Set<AgentTool>,
        dispatcher: CoroutineDispatcher,
    ) = AgentOrchestrator(
        client = client,
        secrets = InMemorySecretProvider(mapOf("test-key" to "sk-test")),
        tools = ToolRegistry(tools),
        promptBuilder = AgentPromptBuilder(NullBinaryResolver(), NullImageScaler(), NoOpLogger),
        clock = FakeTimeProvider(),
        dispatchers = TestDispatchers(dispatcher),
        logger = NoOpLogger,
    )

    // -----------------------------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------------------------

    @Test
    fun `tool call then answer builds a complete thought tree`() = runTest {
        val tool = RecordingTool(webFetchSpec(), output = "<web_page>example.com says 42</web_page>")
        val client = ScriptedClient(
            mutableListOf(
                listOf(
                    StreamEvent.Started(0, "test-model", "p1"),
                    StreamEvent.ReasoningDelta("I should read the page first."),
                    StreamEvent.TextDelta("Let me check that page."),
                    finished("Let me check that page.", reasoning = "I should read the page first.", toolCalls = listOf(toolCall())),
                ),
                listOf(
                    StreamEvent.Started(0, "test-model", "p1"),
                    StreamEvent.TextDelta("example.com says 42."),
                    finished("example.com says 42."),
                ),
            ),
        )

        val events = orchestrator(client, setOf(tool), UnconfinedTestDispatcher(testScheduler))
            .run(runRequest())
            .toList()

        // Two model round-trips, and the second one carried the tool observation back in.
        assertEquals(2, client.calls.size)
        val secondTranscript = client.calls[1].request.messages
        assertTrue(secondTranscript.any { it.toolCalls.isNotEmpty() }, "assistant tool-call turn must be replayed")
        assertTrue(
            secondTranscript.any { message ->
                message.content.any { it is com.nexus.aichat.core.ai.protocol.ProviderContentPart.ToolResultBlock }
            },
            "tool result must be fed back to the model",
        )

        val finalized = events.filterIsInstance<AgentEvent.AssistantMessageFinalized>().single()
        val parts = finalized.message.parts
        // Order is meaningful: it is exactly what the thought tree renders top-to-bottom.
        // 0 reasoning -> 1 narration -> 2 tool milestone -> 3 final answer
        assertEquals(4, parts.size)
        val reasoningPart = parts[0] as MessagePart.Reasoning
        assertTrue(reasoningPart.text.contains("read the page"))
        assertEquals("Let me check that page.", (parts[1] as MessagePart.Text).value)
        val toolPart = parts[2] as MessagePart.ToolCall
        assertEquals(ToolCallStatus.SUCCEEDED, toolPart.status)
        assertTrue(toolPart.resultContent.orEmpty().contains("42"))
        // Everything after the last tool milestone is the answer body.
        assertEquals("example.com says 42.", (parts[3] as MessagePart.Text).value)
        assertEquals(1, tool.executions)

        val finishedEvent = events.filterIsInstance<AgentEvent.RunFinished>().single()
        assertEquals(AgentStopReason.COMPLETED, finishedEvent.reason)
        assertEquals(2, finishedEvent.steps)
        assertEquals(200, finishedEvent.usage.inputTokens)
        assertTrue(events.any { it is AgentEvent.ToolCallStarted })
        assertTrue(events.any { it is AgentEvent.ToolCallFinished })
        assertTrue(events.any { it is AgentEvent.ThoughtCompleted })
    }

    @Test
    fun `unknown tool becomes an observation instead of an exception`() = runTest {
        val client = ScriptedClient(
            mutableListOf(
                listOf(finished("", toolCalls = listOf(toolCall(name = "does_not_exist")))),
                listOf(finished("I could not use that tool, here is what I know: 42.")),
            ),
        )

        val events = orchestrator(client, emptySet(), UnconfinedTestDispatcher(testScheduler))
            .run(runRequest())
            .toList()

        val result = events.filterIsInstance<AgentEvent.ToolCallFinished>().single().result
        assertTrue(result.isError)
        val part = events.filterIsInstance<AgentEvent.AssistantMessageFinalized>().single()
            .message.parts.filterIsInstance<MessagePart.ToolCall>().single()
        assertEquals(ToolCallStatus.FAILED, part.status)
        assertEquals(AgentStopReason.COMPLETED, events.filterIsInstance<AgentEvent.RunFinished>().single().reason)
    }

    @Test
    fun `identical repeated calls are intercepted`() = runTest {
        val tool = RecordingTool(webFetchSpec())
        val client = ScriptedClient(
            mutableListOf(
                listOf(finished("", toolCalls = listOf(toolCall(id = "c1")))),
                listOf(finished("", toolCalls = listOf(toolCall(id = "c2")))),   // same name + args
                listOf(finished("Done: 42.")),
            ),
        )

        val events = orchestrator(client, setOf(tool), UnconfinedTestDispatcher(testScheduler))
            .run(runRequest())
            .toList()

        assertEquals(1, tool.executions, "the second identical call must not execute")
        val results = events.filterIsInstance<AgentEvent.ToolCallFinished>().map { it.result }
        assertEquals(2, results.size)
        assertTrue(results[1].isError)
        assertTrue(results[1].content.contains("already executed"))
    }

    @Test
    fun `ask_first_time approval pauses the loop and remembers the answer`() = runTest {
        val tool = RecordingTool(webFetchSpec())
        val client = ScriptedClient(
            mutableListOf(
                listOf(finished("", toolCalls = listOf(toolCall(id = "c1")))),
                listOf(finished("", toolCalls = listOf(toolCall(id = "c2", args = "{\"url\":\"https://other.com\"}")))),
                listOf(finished("Both pages read.")),
            ),
        )
        val subject = orchestrator(client, setOf(tool), UnconfinedTestDispatcher(testScheduler))
        val proposals = mutableListOf<AgentEvent.ToolCallProposed>()

        subject.run(
            runRequest(options = AgentOptions(mode = AgentMode.REACT, maxSteps = 6, approvalPolicy = ToolApprovalPolicy.ASK_FIRST_TIME)),
        ).collect { event ->
            if (event is AgentEvent.ToolCallProposed) {
                proposals += event
                subject.resolveApproval(event.runId, event.request.callId, ToolApprovalDecision.APPROVE_FOR_RUN)
            }
        }

        assertEquals(1, proposals.size, "approval must be requested once and then remembered for the run")
        assertEquals(2, tool.executions)
    }

    @Test
    fun `rejected tool call is reported and the run can still finish`() = runTest {
        val tool = RecordingTool(webFetchSpec())
        val client = ScriptedClient(
            mutableListOf(
                listOf(finished("", toolCalls = listOf(toolCall()))),
                listOf(finished("Understood - I will not fetch it.")),
            ),
        )
        val subject = orchestrator(client, setOf(tool), UnconfinedTestDispatcher(testScheduler))
        val events = mutableListOf<AgentEvent>()

        subject.run(
            runRequest(options = AgentOptions(approvalPolicy = ToolApprovalPolicy.ASK_EVERY_TIME)),
        ).collect { event ->
            events += event
            if (event is AgentEvent.ToolCallProposed) {
                subject.resolveApproval(event.runId, event.request.callId, ToolApprovalDecision.REJECT)
            }
        }

        assertEquals(0, tool.executions, "a rejected tool must never run")
        val rejected = events.filterIsInstance<AgentEvent.ToolCallFinished>().single().result
        assertTrue(rejected.isError)
        val part = events.filterIsInstance<AgentEvent.AssistantMessageFinalized>().single()
            .message.parts.filterIsInstance<MessagePart.ToolCall>().single()
        assertEquals(ToolCallStatus.REJECTED, part.status)
        assertEquals(AgentStopReason.COMPLETED, events.filterIsInstance<AgentEvent.RunFinished>().single().reason)
    }

    @Test
    fun `clarification parks the run and resumes with the user answer`() = runTest {
        val client = ScriptedClient(
            mutableListOf(
                listOf(
                    finished(
                        "",
                        toolCalls = listOf(
                            toolCall(
                                id = "ask1",
                                name = AgentPromptBuilder.ASK_USER_TOOL,
                                args = "{\"question\":\"Which region?\"}",
                            ),
                        ),
                    ),
                ),
                listOf(finished("Using eu-west-1 then.")),
            ),
        )
        val subject = orchestrator(client, emptySet(), UnconfinedTestDispatcher(testScheduler))
        val events = mutableListOf<AgentEvent>()

        subject.run(runRequest(options = AgentOptions(maxSteps = 4, allowClarificationPauses = true)))
            .collect { event ->
                events += event
                if (event is AgentEvent.ClarificationRequested) {
                    subject.submitClarification(event.runId, event.requestId, "eu-west-1")
                }
            }

        val clarification = events.filterIsInstance<AgentEvent.AssistantMessageFinalized>()
            .single().message.parts.filterIsInstance<MessagePart.ClarificationRequest>().single()
        assertEquals("Which region?", clarification.question)
        assertEquals("eu-west-1", clarification.answer)
        assertEquals(AgentStopReason.COMPLETED, events.filterIsInstance<AgentEvent.RunFinished>().single().reason)
    }

    @Test
    fun `step budget is enforced and reported`() = runTest {
        val tool = RecordingTool(webFetchSpec())
        // A model that keeps calling tools forever must not run forever.
        val client = ScriptedClient(
            MutableList(10) { index ->
                listOf(finished("", toolCalls = listOf(toolCall(id = "c$index", args = "{\"url\":\"https://e$index.com\"}"))))
            },
        )

        val events = orchestrator(client, setOf(tool), UnconfinedTestDispatcher(testScheduler))
            .run(runRequest(options = AgentOptions(maxSteps = 3)))
            .toList()

        val finishedEvent = events.filterIsInstance<AgentEvent.RunFinished>().single()
        assertEquals(3, finishedEvent.steps)
        assertEquals(AgentStopReason.MAX_STEPS_REACHED, finishedEvent.reason)
        assertEquals(3, tool.executions)
        assertTrue(events.any { it is AgentEvent.BudgetWarning })
    }

    @Test
    fun `missing api key fails fast with an actionable error`() = runTest {
        val client = ScriptedClient(mutableListOf())
        val subject = AgentOrchestrator(
            client = client,
            secrets = InMemorySecretProvider(emptyMap()),
            tools = ToolRegistry(emptySet()),
            promptBuilder = AgentPromptBuilder(NullBinaryResolver(), NullImageScaler(), NoOpLogger),
            clock = FakeTimeProvider(),
            dispatchers = TestDispatchers(UnconfinedTestDispatcher(testScheduler)),
            logger = NoOpLogger,
        )

        val events = subject.run(runRequest()).toList()
        val failure = events.filterIsInstance<AgentEvent.RunFailed>().single()
        assertTrue(failure.error is com.nexus.aichat.core.common.error.AppError.Auth)
        assertTrue(client.calls.isEmpty(), "no network call may happen without a key")
    }

    @Test
    fun `single shot mode never offers tools`() = runTest {
        val tool = RecordingTool(webFetchSpec())
        val client = ScriptedClient(mutableListOf(listOf(finished("Direct answer."))))

        orchestrator(client, setOf(tool), UnconfinedTestDispatcher(testScheduler))
            .run(runRequest(options = AgentOptions(mode = AgentMode.SINGLE_SHOT)))
            .toList()

        assertTrue(assertNotNull(client.calls.firstOrNull()).request.tools.isEmpty())
    }
}

// --- helpers shared by the test methods and the nested fakes -------------------------------------

private fun webFetchSpec() = ToolSpec(
    name = "web_fetch",
    displayName = "Read a web page",
    description = "fetch",
    parametersJsonSchema = """{"type":"object","properties":{"url":{"type":"string"}}}""",
    category = ToolCategory.WEB,
    requiresNetwork = true,
)

private fun toolCall(id: String = "call_1", name: String = "web_fetch", args: String = "{\"url\":\"https://example.com\"}") =
    ToolCallRequest(callId = id, toolName = name, argumentsJson = args)

private fun finished(
    text: String,
    reasoning: String = "",
    toolCalls: List<ToolCallRequest> = emptyList(),
    usage: TokenUsage? = TokenUsage(inputTokens = 100, outputTokens = 20, totalTokens = 120),
) = StreamEvent.Finished(
    finishReason = if (toolCalls.isEmpty()) "stop" else "tool_calls",
    text = text,
    reasoning = reasoning,
    toolCalls = toolCalls,
    usage = usage,
    durationMs = 10,
    timeToFirstTokenMs = 5,
)

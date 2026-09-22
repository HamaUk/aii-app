# Nexus — Architecture

> An on-device, agentic AI chat client for Android. Everything runs client-side: the app is the
> orchestrator, the user's own API keys are the credentials, and no Nexus server exists anywhere in
> the request path.

---

## 1. System overview

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                    :app  (Android)                                       │
│                                                                                          │
│  Compose UI ── ChatScreen · ThoughtTree · Composer · ProviderSetup · Settings            │
│       │            (stateless composables; state comes from ViewModels only)              │
│       ▼                                                                                  │
│  ViewModels ── MVI: StateFlow<UiState> in, sealed Action out                              │
│       │                                                                                  │
│       ▼                                                                                  │
│  Repositories ── Room DAOs · DataStore · AndroidSecretStore (Keystore)                    │
│       │                                                                                  │
│  PlatformModule ── binds every SPI the AI core needs (secrets, bytes, docs, search, logs)  │
└───────┬──────────────────────────────────────────────────────────────────────────────────┘
        │ depends on
        ▼
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                 :core:ai  (pure JVM)                                     │
│                                                                                          │
│   AgentOrchestrator  ─── ReAct loop: analyse → think → act → observe → verify → answer    │
│        │            ─── approvals · clarification pauses · dedupe · budgets               │
│        ▼                                                                                 │
│   AgentPromptBuilder ── system contract + tool rules + transcript replay (incl. tools)     │
│        │                                                                                 │
│        ▼                                                                                 │
│   ChatEngine ── retries before first token · usage synthesis · TTFT · inline tool recovery │
│        │                                                                                 │
│        ▼                                                                                 │
│   ProviderAdapter ── OpenAI-compatible · Anthropic · Gemini · Raw REST                    │
│        │                                                                                 │
│        ▼                                                                                 │
│   ChatTransport (Ktor/OkHttp) ── SSE streaming, cancellation = socket close               │
│                                                                                          │
│   ToolRegistry ── web_fetch · web_search · read_document · get_datetime · ask_user         │
└───────┬──────────────────────────────────────────────────────────────────────────────────┘
        ▼
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│  :core:model (pure JVM)  ── Conversation · Message tree · ProviderConfig · AgentEvent     │
│  :core:common (pure JVM) ── AppError · NexusResult · dispatchers · time · redaction          │
│  :core:designsystem (Android) ── 6 palettes · typography · shapes · motion · glass · icons │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

### Why the AI core is a pure-JVM module

`core:ai`, `core:model` and `core:common` compile with `org.jetbrains.kotlin.jvm` — no Android
plugin, no `android.util.Log`, no `Context`. Consequences that matter:

* the entire agent harness, all four protocol adapters and the SSE parser are unit-tested on a bare
  JVM (see `core/ai/src/test`), in milliseconds, with no emulator in the loop;
* a provider bug can be reproduced in a test file instead of on a device;
* the same engine can be lifted into a KMP target (desktop/iOS) later without untangling framework
  bleed.

Android is reached only through five interfaces in `core/ai/src/main/kotlin/com/nexus/aichat/core/ai/spi/`:

| SPI | Android implementation | Why it must live on the platform |
|---|---|---|
| `SecretProvider` | `AndroidSecretStore` | Keystore + `EncryptedSharedPreferences` |
| `BinaryResolver` | `ContentResolverBinaryResolver` | `content://` URIs, SAF, photo picker |
| `ImageScaler` | `AndroidImageScaler` | `ImageDecoder`/`Bitmap`, EXIF, memory limits |
| `DocumentTextExtractor` | `PdfBoxDocumentTextExtractor` | `ContentResolver` + PDFBox-Android assets |
| `AttachmentProvider` | `RoomAttachmentProvider` | the attachment table |
| `WebSearchProvider` | `SearchProviderRouter` → Brave/Tavily | user-held search keys |

---

## 2. Module graph and dependency rules

```
:app ─────────► :core:designsystem
  │  └────────► :core:ai ──► :core:model ──► :core:common
  │                    └───► :core:common
  └────────────► :core:model, :core:common
```

Rules enforced by the graph (not by convention):

1. dependencies point **inwards only** — a Compose screen can never reach the transport layer;
2. `:core:ai` knows nothing about Room, Compose or Android;
3. `:core:designsystem` depends only on `:core:model` (for `ThemePreset`, `CornerStyle`);
4. there is exactly one HTTP client (`HttpEngineFactory`), one JSON config (`NexusJson`), one logger
   and one clock in the whole process — all injected.

---

## 3. The agentic execution model

### 3.1 Loop

`AgentOrchestrator.run(request): Flow<AgentEvent>` is a cold flow; collecting it starts the run,
cancelling it aborts in-flight generation (the OkHttp socket is closed, so billing stops too).

```
        ┌─────────────────────────────────────────────────────────────┐
        │ budget check: steps · tool calls · wall clock               │
        └───────────────┬─────────────────────────────────────────────┘
                        ▼
   analyse ──► stream model turn (reasoning + prose + tool_calls)
                        │
        ┌───────────────┴────────────────┐
        │ tool calls?                    │
        ▼                                ▼
   approval gate                   no tools → answer
        │                                │
   execute tool(s)                       │
        │                                │
   observation appended to transcript ───┘   (loop, capped)
```

### 3.2 Guarantees the harness provides (each one is a bug avoided in production)

| Guarantee | Implementation | Failure it prevents |
|---|---|---|
| Identical calls never run twice | call signature `name:args` cache in the run | small models looping forever |
| A tool failure is an observation, not a crash | `SafeAgentTool` + typed `ToolResult.error` | one flaky fetch killing a whole answer |
| Retries only before the first token | `ChatEngine` gates on `emittedContent` | duplicated text in the bubble |
| Stop is immediate and real | flow cancellation → channel close | paying for tokens the user cancelled |
| Budget exhaustion still answers | `MAX_STEPS_REACHED` + partial message persisted | empty screen after 8 steps of work |
| Tools print-as-text are still executed | `InlineToolCallParser` + `TextRevised` event | a chat that "decides to print JSON" |
| Costs are always visible | provider usage, else `TokenEstimator` (`providerReported=false`) | silently wrong token counters |
| Secrets never persist in config | `AuthConfig.vaultKey` references only | API keys in Room, in backups, in logs |

### 3.3 Event contract (what the UI renders)

`RunStarted → PhaseChanged → ThoughtDelta* → ThoughtCompleted → ToolCallProposed? → ToolCallStarted →
ToolCallFinished → AnswerDelta* → UsageUpdated → AssistantMessageFinalized → RunFinished`

`AssistantMessageFinalized` carries a **fully assembled `Message`** whose `parts` are already in
render order: `[Reasoning] → [Text narration] → [ToolCall milestones] → [Final answer]`. The message
bubble rule is therefore trivial and reload-safe:

> *everything after the last `ToolCall` part is the answer body; everything before it is the thought tree.*

### 3.4 Reasoning / extended thinking

Normalised across vendors by the adapters:

| Provider | Wire signal | Mapped to |
|---|---|---|
| DeepSeek R-series, vLLM, OpenRouter | `delta.reasoning_content` | `ReasoningDelta` |
| Anthropic | `thinking_delta` (`thinking.budget_tokens`) | `ReasoningDelta` |
| Gemini | part with `thought: true` + `thoughtsTokenCount` | `ReasoningDelta` |
| OpenAI o-series / GPT-5 | `reasoning_effort` | `ReasoningDelta` when surfaced |

`AgentOptions.autoEnableReasoning` requests it only for models advertising `ModelCapability.REASONING`.

---

## 4. Multi-provider strategy

Two independent axes, which is what makes "any endpoint" work:

1. **Protocol** (`ProviderProtocol`) chooses the adapter — OpenAI-compatible, Anthropic Messages,
   Google Gemini, or Raw REST.
2. **Instance** (`ProviderConfig`) supplies base URL, path overrides, auth scheme, extra headers,
   timeouts, a Raw-REST body template and an optional response dot-path.

Adding a vendor family = 1 adapter (~250 lines). Adding a *provider* (vLLM, LiteLLM, a company
proxy, Ollama on a laptop) = 0 lines of code, done entirely in the Custom Provider builder.

| Capability | How it is achieved |
|---|---|
| Pre-configured templates | `ProviderPresets.ALL` — OpenAI, Anthropic, Gemini, DeepSeek, Groq, OpenRouter, Mistral, xAI, Ollama, LM Studio, plus a Custom template |
| Fetch models | `ModelDiscoveryService` → adapter-owned `/models` decoding, with a preset fallback ladder |
| Ping / latency | `ProviderHealthService` — a real 1-token completion, because that is the only test that proves key + model + body shape all work |
| Custom auth | `AuthScheme`: bearer, `x-api-key`, `x-goog-api-key`, arbitrary header, query param |
| Quirky servers | `includeStreamUsage=false` (self-hosted), body template, response dot-path |
| Capability gates | `ModelHeuristics` infers vision/tools/reasoning from model ids so the UI can show or hide affordances before the first ping |

---

## 5. Data model and persistence

```
conversations ──1:N──► messages ──N:M──► attachments
      │                   │
      │                   ├── partsJson      : List<MessagePart>  (sealed, polymorphic JSON)
      │                   └── usageJson      : TokenUsage
      ├── agentOptionsJson : AgentOptions
      └── totalUsageJson   : TokenUsage (denormalised rollup)

providers      : ProviderConfig (authJson holds vault key *names*, never secrets)
system_rules   : global + per-chat instructions
tool_invocations : audit trail  (who ran what, with which arguments, approved or not)
usage_metrics  : per-message tokens/cost/TTFT → the usage screen
document_cache : parsed PDFs/text, keyed by content hash
```

Message **tree**: `parentId` + `siblingIndex` (`MessageTree` helpers: `linearize`, `childrenOf`,
`activeLeafFrom`, `nextSiblingIndex`). Editing a prompt or regenerating an answer appends a sibling;
the branch switcher flips between them without destroying history.

### Secure key storage

* `AuthConfig.vaultKey` is a *name*; material lives in `EncryptedSharedPreferences` under a master
  key generated in the Android Keystore (AES-256-GCM master, AES-256-SIV keys, AES-256-GCM values).
* Excluded from cloud backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`).
* Keystore invalidation (post-OS-upgrade, biometric change) surfaces as `AppError.KeyStore` with a
  recoverable message rather than a crash — plus a one-tap self-test in Settings.
* `Redaction` scrubs `sk-…`, `Bearer …` and `*api-key` header patterns from every log line, in debug
  *and* release.

---

## 6. Networking

* One `HttpClient` (Ktor + OkHttp engine): connection pool 8/5 min, HTTP/2, transparent gzip.
* Streaming uses `preparePost(...).execute { }` so the body is never buffered; tokens reach the UI on
  arrival.
* `requestTimeoutMillis = INFINITE` for streams: a reasoning model may legitimately be silent for a
  minute before the first token. `socketTimeoutMillis = 120s` is the real watchdog.
* SSE parsing is hand-rolled (`SseParser`) to tolerate non-conformant providers (`data:` without a
  space, missing blank separators, `: ping` comments, mid-frame disconnects) and to allow an
  immediate abort on Stop.
* Provider errors are mapped to the typed `AppError` tree, so the UI says *"invalid_api_key"* or
  *"out of credits"* instead of "HTTP 401".
* Cleartext is denied globally except for `localhost` / `10.0.2.2` / private ranges, so local
  inference servers work while keys stay on TLS.

---

## 7. UI architecture (Compose)

* **Single activity**, edge-to-edge, `NexusTheme` owning colour/typography/shape/motion tokens.
* **MVI**: `StateFlow<UiState>` down, sealed `Action` up, no repository calls from composables.
* **Six palettes** (`ThemePreset`): System, AMOLED Pure Black, Gemini Deep Blue, Claude Warm Sepia,
  Cyberpunk, Material You (dynamic). Palettes define full M3 role sets, not just two accents.
* **Design tokens beyond M3** (`NexusTokens`): glass fill/border, user vs assistant bubble fills, code
  surface, reasoning gutter, tool chip, status colours.
* **Motion** (`NexusMotionSpec`): springs for user-driven change, tweens for opacity/colour, with a
  `reduced` path honouring accessibility settings and our own toggle.
* **Glass surfaces** are a deliberate approximation (gradient border + soft shadow + high-alpha fill)
  rather than per-frame backdrop blur, which costs more frames than it is worth over a live feed.
* **Thought tree**: reasoning parts stream into a collapsible, indented timeline with
  `[Thinking] → [Tool] → [Observation] → [Final answer]` milestones; tool rows expose arguments,
  duration, error state and a "view full output" affordance.

---

## 8. Testing strategy

| Layer | What is tested | Where |
|---|---|---|
| SSE framing | multi-line data, CRLF, no-space colon, comments, partial flush, retry | `SseParserTest` |
| Stream folding | fragmented tool-call reassembly, reasoning isolation, Gemini replace semantics, usage merge | `StreamAccumulatorTest` |
| Tool recovery | Hermes/Qwen envelopes, fenced JSON, false positives on ordinary code blocks | `InlineToolCallParserTest` |
| Agent loop | tool→answer tree, unknown tool, duplicate interception, approval + rejection, clarification pause, step budget, missing key, single-shot mode | `AgentOrchestratorTest` |
| Instruments (later) | composer, thought-tree expansion, streaming render | `app/src/androidTest` |

The orchestrator is tested against a scripted `ChatCompletionClient`, which is only possible because
it depends on that interface rather than on the HTTP engine.

---

## 9. Deliberate trade-offs

| Decision | Alternative | Why this way |
|---|---|---|
| Hand-rolled SSE parser | a library | need immediate abort + tolerance of malformed providers |
| JSON columns in Room | normalised part tables | parts are polymorphic and never queried internally; avoids a migration per part type |
| Ktor over Retrofit | Retrofit + OkHttp SSE | one client for JSON *and* streaming, same engine, no call adapters |
| Pure-JVM core modules | Android libraries | fast, device-free tests; forces clean boundaries |
| Approximated glass | true backdrop blur | frame cost over a live feed on mid-range hardware |
| Client-side only | thin relay backend | the entire point: user keys stay on the device |
| `ask_user` as a tool | a special protocol | works identically on every provider and needs no provider support |

---

## 10. Roadmap

* **Part 2 (UI):** chat list, streaming message bubble, GFM + syntax highlighting renderer, image
  viewer with pinch-zoom, file badges, floating action bar, composer with attachments, provider
  setup wizard, custom-rules screen, settings/usage screens.
* **Part 3:** conversation branching UI polish, share/export, per-tool approval UI, voices (TTS),
  widget/quick-settings tile, model presets per mode.
* **Later:** on-device OCR for scanned PDFs, prompt-cache-aware context compaction, MCP-style external
  tool endpoints, desktop target via the same pure-JVM core.

## 11. The app layer (hybrid layout)

Two structures coexist on purpose, and the split is deliberate rather than vestigial:

- **Gradle modules** (`:core:common`, `:core:model`, `:core:ai`, `:core:designsystem`, `:app`) enforce the
  dependency direction at *build* time: `:core:ai` cannot accidentally reach Android, because Android is
  not on its classpath.
- **Inside `:app`**, the tree is the screen-first layout used by the chat surface itself:
  `core/{di,security,theme,util}`, `data/{local/{db/{dao,entity},datastore,file},mapper,remote/{streaming,api,dto},repository}`,
  `domain/{model,repository,agent,tools,usecase}`, `ui/{navigation,chat/{components,state},conversations,providers,settings,components}`.

### Who owns what in `:app`

| Area | Files | Responsibility |
| --- | --- | --- |
| `core/di` | `AppModule`, `DatabaseModule`, `NetworkModule` | binds every `:core:ai` SPI to a platform impl; owns the single `HttpClient`, `ChatTransport` and `AdapterRegistry`; registers tool plugins |
| `core/security` | `SecureKeyStore` | EncryptedSharedPreferences + Keystore MasterKey; implements `SecretProvider`; stores vault key *names* only in Room |
| `core/theme` | `Theme`, `Color`, `Type`, `Shape` | app-level aliases over the design system: dark-first resolution, chat semantic colours, bubble shapes, content type scale |
| `data/local/db` | `NexusDatabase`, 7 DAOs, 8 entities | conversations, messages (branch tree), attachments, providers, rules, tool invocations, usage, document cache |
| `data/local/datastore` | `SettingsDataStore` | Preferences DataStore: appearance, agent defaults, tool switches; also exposes a synchronous snapshot for non-suspending call sites |
| `data/local/file` | `AttachmentFileSource`, `ImageProcessor`, `AttachmentTextExtractor` | `content://` reads, downscale-at-ingest, text/PDF extraction with caching |
| `data/remote/*` | `SSEClient`, `SseTrace`, `StreamParser`, `ProviderStreamAdapter`, four protocol `*Api` objects, wire DTOs | raw-frame inspection for custom endpoints; one-shot provider calls (titles, tests); protocol paths/headers/validation for the wizard |
| `data/repository` | `ChatRepositoryImpl`, `ProviderRepositoryImpl`, `SettingsRepositoryImpl` | the only classes that see Room + vault + engine together; persist every agent event; own branching, titles and telemetry |
| `domain/agent` | `ReActEngine`, `ThoughtProcessor`, `ToolRegistry` | thin handles on the core harness; event→render-tree folding; settings-aware tool gating |
| `domain/usecase` | `SendMessage`, `FetchModels`, `PingModel`, `ManageProvider`, `AttachFile` | policy that belongs to neither the UI nor the data layer (validation, ingest, pre-flight) |
| `ui/*` | navigation, chat (+components/state), conversations, providers, settings, shared components | MVI: one `ChatIntent` entry point, one immutable `ChatUiState`, one-shot `ChatEffect` channel |

### Two seams worth knowing before editing

1. **The live run is not a message.** While the agent works, `ChatUiState.trace` holds an `AgentTrace`
   folded by `ThoughtProcessor`; the feed renders it as a pinned bubble. Persistence happens on
   `AgentEvent.AssistantMessageFinalized`. This is why Stop is lossless and why the feed never rewrites
   rows mid-stream.
2. **The engine is not allowed to know about Android.** Everything platform-shaped arrives through six SPIs
   (`SecretProvider`, `BinaryResolver`, `ImageScaler`, `DocumentTextExtractor`, `AttachmentProvider`,
   `WebSearchProvider`) plus the tool set, all bound in `core/di/AppModule.kt`. If a change needs an
   `import android.*` inside `:core:ai`, the change belongs in `:app`.

## 12. Build, shrink and what "verified" means here

**The build is real.** Both variants compile and package on a 2 GB box with JDK 21 and Android SDK 37:

```
./gradlew :app:assembleDebug      # 34.0 MB, package com.nexus.aichat.debug
./gradlew :app:assembleRelease    # 8.6 MB after R8 full mode + resource shrinking, single dex
./gradlew :core:ai:test :app:testDebugUnitTest   # 68 tests, 0 failures
```

Three build-level decisions are load-bearing:

1. **AGP 9's built-in Kotlin is switched off on purpose** (`android.builtInKotlin=false` +
   `android.newDsl=false` in `gradle.properties`). The project is built with KGP 2.3.20 and the Compose
   compiler plugin, which is the configuration every JetBrains/Google doc still describes. Both flags
   disappear in AGP 10, so the migration to built-in Kotlin is a deliberate, scheduled piece of work -
   not something to discover during an unrelated upgrade.
2. **Pure-JVM modules compile with `sourceCompatibility`/`targetCompatibility = 17`, not a Gradle
   toolchain.** A toolchain block demands a downloadable JDK 17 and fails wherever the Foojay resolver
   is not configured (including this machine, and every CI image without it). Emitting Java 17 bytecode
   on whatever JDK 17+ runs the build is one less moving part and identical output.
3. **`:core:ai` depends on `hilt-core` (annotations) + KSP, never on the Hilt Gradle plugin.** The plugin
   exists to transform Android bytecode; applying it to a JVM module drags Android into the graph and
   breaks the "the engine cannot reach the platform" invariant in §11.

### Why `app/proguard-rules.pro` is not empty

R8 in full mode will happily delete the two things a typed, offline-first app cannot live without:

- **kotlinx.serialization's `$$serializer` classes.** Messages, provider configs, agent options and
  settings are stored as JSON blobs; without keeps, every read returns garbage or throws.
- **persisted enum names.** `ThemePreset`, `AgentMode`, `ToolApprovalPolicy` and friends are stored by
  `name`/`id`. Renaming the constants does not crash - it silently resets the user's preferences on
  update, which is the worse failure.

The file also carries the two `-dontwarn`s R8 asks for (`com.gemalto.jp2.**`: pdfbox's optional
JPEG-2000 decoder; `com.google.errorprone.annotations.**`: Tink's compileOnly annotations) and the Ktor
engine-discovery keep. Everything else - Room, Hilt, Compose - ships its own consumer rules, so there is
no blanket `-keep` anywhere.

### What is still unverified

Nothing has run on a device: this machine has no emulator and no KVM. Compilation, R8 shrinking, resource
packaging, manifest/badge inspection and 68 unit tests are green; the streaming UI, camera ingest, PDF
extraction and Keystore behaviour are reasoned about and unit-tested at the edges, but not exercised on
real hardware. Treat the first device run as the remaining verification step, not a formality.

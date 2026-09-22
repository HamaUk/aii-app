# Nexus — Agentic AI Chat for Android

A production-grade, **fully client-side** AI chat agent: Kotlin + Jetpack Compose, Material 3, multi-provider,
and an on-device agentic (ReAct) execution harness with real tool calling. No backend, no proxy — your
API keys never leave the device.

```
Component        │ Status in this drop
─────────────────┼──────────────────────────────────────────────────────────────────────
Architecture     │ ✅ module graph, dependency rules, ADRs                     (ARCHITECTURE.md)
Build system     │ ✅ Gradle 9.7.1 + AGP 9.4.1 + version catalog, debug + release APKs built
:core:common     │ ✅ AppError taxonomy, NexusResult, dispatchers, time, redaction
:core:model      │ ✅ conversation/message tree, providers + 11 presets, agent events, tools
:core:ai         │ ✅ transport + SSE, 4 protocol adapters, model discovery, health probe,
                 │    ReAct orchestrator, tool harness, 4 tool plugins, unit tests
:core:designsystem│ ✅ 6 palettes (incl. AMOLED + Material You), typography, shapes, motion, glass
:app             │ ✅ Keystore secret store, Room schema (8 tables), platform SPIs, DI,
                 │    themed shell UI, launcher icon, manifest, network security config
Chat surface     │ ✅ ships: bubbles, markdown renderer, thought tree, composer, model switcher,
                 │    conversation list, provider wizard, settings hub
Verified         │ ✅ compiles, 68/68 unit tests green, debug + R8 release APKs signed
```

---

## Build

### Android Studio (recommended)

1. **File → Open** → select this folder.
2. Let Gradle sync (Studio **Otter 3 Feature Drop or newer**, JDK 17 bundled is fine).
3. Run on a device/emulator with **Android 8.0 (API 26)** or newer.

### Command line

```bash
./gradlew :core:ai:test :app:testDebugUnitTest   # 68 unit tests, no device needed
./gradlew :app:assembleDebug                     # debug APK   -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease                   # R8 release  -> app/build/outputs/apk/release/
```

On Windows, `build-release.ps1` runs the tests, builds the R8 release APK and drops it on the Desktop
as `Nexus-1.0.0.apk`, resolving the JDK and Android SDK itself:

```powershell
.\build-release.ps1              # tests + release APK -> Desktop
.\build-release.ps1 -SkipTests   # skip the unit tests
.\build-release.ps1 -NoDesktop   # leave the APK in app/build/outputs only
```

`scripts/build-apk.sh` wraps the same commands with the environment and heap settings that a
low-memory machine needs, and copies the results into `artifacts/`.

### You need a JDK, not a JRE

Gradle compiles a generated version catalog and therefore needs `javac`. A `JAVA_HOME` pointing at a
JRE fails with a confusing error:

```
org.gradle.api.internal.catalog.GeneratedClassCompilationException:
No Java compiler found, please ensure you are running Gradle with a JDK
```

**Any JDK 17 or newer works** — 17, 21 and 25 are all fine, because the pure-JVM modules emit Java 17
bytecode via `sourceCompatibility`/`targetCompatibility` and the Android modules target
`JvmTarget.JVM_17`. There is no Gradle toolchain block, so nothing is downloaded behind your back.

Verify in one line:

```powershell
Test-Path "$env:JAVA_HOME\bin\javac.exe"   # must be True
```

If it is `False`, either install a JDK or point the build at one you already have:

```powershell
.\build-release.ps1 -Jdk "C:\path\to\jdk"   # or set $env:NEXUS_JDK
```

`build-release.ps1` checks `JAVA_HOME`, then falls back to the JDK Gradle has already downloaded
(`~/.gradle/jdks`) and the usual install locations, so it works even when `JAVA_HOME` is wrong.

### Verified build (2026-09-22)

Both variants were built and inspected on this machine:

| Artifact | Size | Notes |
| --- | --- | --- |
| `artifacts/nexus-1.0.0-debug.apk` | 34.0 MB | `com.nexus.aichat.debug`, all deps, V2-signed with the debug key |
| `artifacts/nexus-1.0.0-release.apk` | 8.6 MB | `com.nexus.aichat`, R8 full mode + resource shrinking, single dex, baseline profile |

Verified with `aapt2 dump badging` (label `Nexus` in every locale, launcher `MainActivity`, minSdk 26,
targetSdk 37, adaptive icon) and `apksigner verify` (V2 signature intact). The release pass also keeps
the reflective surface alive: `NexusDatabase_Impl` and every `$$serializer` survive R8 - see
`app/proguard-rules.pro`, which is deliberately narrow and commented rule by rule.

### CI / signing

`.github/workflows/android.yml` builds both variants on every push and uploads the APKs as artifacts.
For a signed release build, add these repository secrets — the workflow generates a keystore if they
are absent, so a fork always produces an installable artifact:

| Secret | Purpose |
|---|---|
| `NEXUS_KEYSTORE_PATH` | path to a `.jks` (CI writes it from `NEXUS_KEYSTORE_BASE64`) |
| `NEXUS_KEYSTORE_BASE64` | base64 of your keystore file |
| `NEXUS_KEYSTORE_PASSWORD` | store password |
| `NEXUS_KEY_ALIAS` / `NEXUS_KEY_PASSWORD` | key alias and password |

---

## Toolchain matrix (verified against Google Maven / Maven Central, Sept 2026)

| Component | Version | Note |
|---|---|---|
| Gradle | 9.6.0 | minimum for AGP 9.4 (wrapper committed) |
| AGP | 9.4.1 | supports compileSdk 37 |
| Kotlin | 2.3.20 | pinned deliberately — see below |
| KSP | 2.3.12 | built against Kotlin **2.3.20**; do not bump independently |
| Compose BOM | 2026.09.00 | → Compose 1.12.x, requires compileSdk 37 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 | Android 8.0+ |
| Hilt | 2.60.1 | with `androidx.hilt` 1.4.0 |
| Room | 2.8.5 | schema export on, destructive migration off |
| Ktor | 3.6.0 | OkHttp engine, SSE |
| Coil | 3.6.3 | `coil-network-okhttp` |

### Two toolchain decisions worth knowing before you upgrade

**1. `android.builtInKotlin=false` is intentional.** AGP 9 enables built-in Kotlin by default, and
applying `org.jetbrains.kotlin.android` while it is on is a hard failure. We run the explicit-KGP path
so that KSP, Hilt and the Compose compiler plugin all resolve against the *single* Kotlin version in
the catalog. When you move to AGP 10 (which removes the opt-out), drop the flag, remove
`alias(libs.plugins.kotlin.android)`, and let AGP's bundled KGP drive the build — then re-pin the
Compose compiler plugin to whatever KGP version AGP 9.4+/10 ships.

**2. Kotlin 2.3.20 + KSP 2.3.12 move together.** KSP moved to independent versioning; the 2.3.x line
is built against Kotlin 2.3.20. Bumping Kotlin ahead of KSP will break Room/Hilt code generation.

---

## Add a provider in 30 seconds

1. **Providers → Add** → pick a template (OpenAI, Anthropic, Gemini, DeepSeek, Groq, OpenRouter,
   Mistral, xAI, Ollama, LM Studio) or **Custom endpoint**.
2. Paste the API key. It is written to the Keystore-backed vault under a generated vault key; the
   database only ever stores that key's *name*.
3. **Fetch models** → hits `GET {base}/models` and validates what is actually served.
4. **Test** next to any model → a real 1-token completion, reported with latency, so you know the
   key, the model id and the request shape are all correct before you spend a prompt on it.

Custom endpoints (vLLM, llama.cpp, LiteLLM, a private proxy, a colleague's laptop) need no code:

| Field | Example |
|---|---|
| Base URL | `http://192.168.1.42:8000/v1` |
| Protocol | OpenAI-compatible / Anthropic-compatible / Google Gemini / Raw REST |
| Auth | bearer · `x-api-key` · `x-goog-api-key` · arbitrary header · query param · none |
| Extra headers | `X-Title: Nexus`, `HTTP-Referer: …`, gateway tokens |
| Body template (Raw REST) | `{"model":"{{model}}","prompt":"{{prompt}}","stream":{{stream}}}` |
| Response path (Raw REST) | `choices.0.delta.content` |

> Emulator note: use `10.0.2.2` where a preset says `localhost`.

---

## What the agent can actually do

| Tool | Behaviour |
|---|---|
| `web_fetch` | Fetches a URL, strips boilerplate, converts to Markdown, returns citations. Refuses private/loopback hosts by default so a prompt-injected page cannot probe your LAN. |
| `web_search` | Brave or Tavily with your own key; degrades to an actionable message when unconfigured. |
| `read_document` | `outline` → `search` → `read` over attached PDFs/txt/md/json/csv, with per-conversation parse caching. |
| `get_datetime` | Anchors the model's sense of "now"; supports timezones and day offsets. |
| `ask_user` | The agent pauses and asks a clarifying question (rendered inline in the thought tree), then resumes — works on every provider because it is just a tool. |

Anything destructive or network-reaching can require approval first, per the four-step
`ToolApprovalPolicy` (auto-approve safe → ask once → ask always → deny).

---

## Status

**Built and in place** (all paths exist in the repo, all packages `com.nexus.aichat.*`):

| Layer | State |
| --- | --- |
| Build system | AGP 9.4.1 / Kotlin 2.3.20 / Gradle 9.7.1 (wrapper pinned), version catalog, CI workflow, release signing via env vars |
| `:core:common` | result + error taxonomy, dispatchers, time provider, redacting logger |
| `:core:model` | provider/model/agent/message/theme domain model, presets for 11 providers, prompt composer, personas |
| `:core:ai` | protocol adapters (OpenAI-compatible, Anthropic Messages, Gemini, raw REST), streaming engine, SSE transport, ReAct orchestrator with approvals/clarifications/budgets, tool registry, web fetch + search + document reader + datetime tools, model discovery + health |
| `:core:designsystem` | six palettes (AMOLED, Gemini Deep Blue, Claude Sepia, Cyberpunk, Material You, System), typography, shapes, motion spec, glass surface, product icons |
| `:app` data | Room (8 entities / 7 DAOs, schema v1), DataStore settings, Keystore-backed vault, attachment ingest (camera/gallery/files, downscale, PDF text), repositories, wire DTOs and frame inspector |
| `:app` domain | `ThoughtProcessor` (event → thought tree), tool registry/dashboard, use cases (send, attach, fetch models, ping, manage provider) |
| `:app` UI | chat screen with thought tree, markdown renderer (headings/lists/tables/quotes/code/math), copyable code blocks, image viewer with pinch-zoom, floating action bar, model switcher sheet, conversation list with search/pin/delete, provider list + setup wizard (template and custom builder), settings hub (appearance/behaviour/tools/rules/keystore self-test) |
| Tests | `SseParserTest`, `StreamAccumulatorTest`, `InlineToolCallParserTest`, `AgentOrchestratorTest`, `RawRestAdapterTest`, `GeminiAdapterTest`, `WebFetchToolTest` (in `:core:ai`); `MarkdownParserTest`, `StreamParserTest`, `ThoughtProcessorTest` (in `:app`) |

**Verified.** The full source tree compiles and packages. Getting there took four rounds of real fixes
rather than the usual cosmetic friction, and every one of them was a bug worth having:

- `AppError` was a `@Serializable sealed class` with `message`/`cause` as base-constructor properties -
  duplicate serial names, and no serializer for `Throwable`. The base now declares `abstract val message`
  and a `@Transient cause`, and each variant owns exactly one field.
- `:core:ai` was applying the Hilt *Gradle* plugin while being a pure JVM module; it now takes
  `hilt-core` annotations + KSP only, so the engine cannot reach Android even by accident.
- The JSON path walker in the frame parser could not traverse arrays, so every OpenAI-shaped frame
  (`choices.0.delta.content`) decoded to `null`. It now walks `JsonElement` and treats numeric segments
  as indices.
- The ReAct harness under-reported `steps`, left the tool budget's skipped calls unanswered in the
  transcript (which strict providers reject outright), and dropped `ToolCallFinished` for unknown,
  duplicate and rejected calls - so the thought tree lost those milestones. All four are fixed and
  covered by the four previously-failing orchestrator tests.
- R8 needed `app/proguard-rules.pro` to exist at all: without keep rules for kotlinx.serialization's
  `$$serializer` classes and for persisted enum names, a release build would silently reset every user
  preference and fail to deserialise stored messages.
- `:app` did **not** actually compile: `LiveAssistantBubble.kt` declared a second `LiveAssistantBubble`
  composable against an `AgentTrace` shape that no longer existed, and `EnhancedChatComponents.kt`
  reached for a file-private `AttachmentChip` and a `GlobalScope` coroutine. See below.
- `responseTextPath` was persisted by the custom-provider wizard and then ignored by the decoder, so a
  bespoke Raw-REST endpoint returned an empty answer unless its body happened to match a known shape.
- Gemini tool results went back as `functionResponse.name = "tool"`, because the name was
  reverse-engineered from the call id. Gemini correlates by name, so the second turn of every Gemini
  tool conversation was rejected.
- Run usage was recorded twice per assistant message (`UsageUpdated` *and* `RunFinished` both report
  the run-cumulative totals), double-counting every conversation's tokens and cost.
- `web_fetch` validated only the URL it was given and then let jsoup follow redirects unchecked, so a
  public URL could still `302` the agent into the cloud metadata endpoint or the user's LAN - the exact
  attack the guard exists to stop.

**Next:** run it on a device - nothing has been exercised on real hardware yet, because this machine has
no emulator. Then: streaming cursor polish, the usage dashboard screen, PDF page preview cards, and
instrumented Compose tests for the chat feed.

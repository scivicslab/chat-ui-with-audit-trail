# Split chat-ui-with-audit-trail into a headless core and the screen-and-record module (nigsc-connector)

Design inputs: `AgentConnectorKernelConcept_261121_oo01` (`doc_SCIVICS003/docs/agent-connector-kernel/010_concepts`, section 「quarkus-chat-ui・ssh-connector・nigsc-connector との関係」), `ConcurrencyWithPojoActor_261121_oo01` and `LlmKeyInjectionProxy_261121_oo01` (both in `doc_SCIVICS003/docs/agent-connector-kernel/040_design`), `ProviderAndToolPlugins_260912_oo01`, `ToolCatalogAndChatUiMcpServer_261120_oo01`. The survey behind this plan went to chat-48 as chat-77's reply.

Branch: `nigsc-connector-split`, from `main` at `7c4b507`. The name is the one chat-48 gave. Before branching, `git fetch origin` showed `main` equal to `origin/main` (both `7c4b507`). `git log main..harness-tools-mcp` and `git log main..origin/harness-tools-mcp` are both empty, so `harness-tools-mcp` holds no unmerged commits and changes nothing for the split.

Goal of this rebuild: change the module structure, not the behaviour. The harness-side MCP transport, HTTP on localhost (Decisions 5), is built in stage 6. The LLM proxy, the confinement core and the Claude Code launcher are built in stage 8, the Podman launchers in stage 9 (Decisions 8), and `ssh-connector` with the ssh tools (Decisions 6) in stage 11. They are not part of these stages.

Background (for reference only; this work renames nothing):
- After the split, chat-ui-with-audit-trail returns to the quarkus-chat-ui brand, as a new major version in the old `scivicslab/quarkus-chat-ui` repository.
- nigsc-connector is a later tool that depends on the core and carries the settings specific to the NIG supercomputer.
- The docs for chat-ui and for the core go in `doc_SCIVICS003`.
- `ssh-connector` is a standalone tool, not a plugin of `agent-connector-kernel` (Decisions 6). It is `agent-connector-kernel`, plus the ssh tools, plus its own launcher executable with a `main` entry point.
  - Its purpose is to let any harness, not only Claude Code, use clusters over MCP.
  - Through `agent-connector-kernel` it launches and confines the harness, runs the MCP server (HTTP on localhost, Decisions 5) and the LLM proxy, and exposes the ssh tools.
  - The ssh tools live in a module of their own, so quarkus-chat-ui can reuse that module.
- `nigsc-connector` will be a separate thing that depends on `ssh-connector`, and through it on `agent-connector-kernel`, and adds the NIG supercomputer's settings.

Naming rule: in docs, comments and commit messages, always write `agent-connector-kernel` in full, never "kernel" alone. The short form is confused with a Jupyter kernel and with Claude Science's Operon kernel process. Code identifiers avoid a bare `kernel` too.

## Decisions (approved by the person, relayed by chat-48, 2026-11-21)

1. `CliProcess` stays in `plugin-harness`. It does not move to the `agent-connector-kernel` module.
2. The tools that operate ConversationSquad actors (`create_chat`, `set_agent_loop` and the others) stay on `/mcp`, reached through the app's `ToolExtension`.
3. Harnesses reach the core's tools over MCP on the nigsc-connector side. Inside chat-ui, the claude and codex conversations stay on `<invoke>` (the recommendation in the open question below, now decided).
4. The module is named `agent-connector-kernel`, both as the directory and as the artifactId (decided 2026-11-21).
5. **The harness reaches the MCP server over HTTP on localhost** (the person's decision, 2026-11-21, relayed by chat-01 and chat-48). There is no stdio option. This answers `AgentConnectorKernelConcept_261121_oo01` line 101. It is built in this rebuild, in stage 6, and not left to the nigsc-connector work:
   - every request carries the scope (conversation) name and a key;
   - a request without a valid key is refused, and keys are compared in constant time;
   - the server is the JDK's own `com.sun.net.httpserver` (module `jdk.httpserver`), so it adds no dependency and the enforcer rule stays unchanged;
   - it binds to the loopback address only;
   - its concurrency is written with POJO-actor, as a plain POJO first, with no class name ending in `Actor`.
   Inside chat-ui-with-audit-trail nothing changes: Decisions 3 stands, no MCP config is passed to the harness, and the E2E checks after each stage prove today's behaviour is kept.
6. **`ssh-connector` is a standalone tool, not a plugin of `agent-connector-kernel`** (the person's decision, 2026-11-21, relayed by chat-01 and chat-48).
   - The design documents were updated to match: `doc_SCIVICS003` commit `547f35a` (`AgentConnectorKernelConcept_261121_oo01`) and `doc_Infra001` commit `7f1dc54` (`NigscConnectorComposition_261121_oo01`).
   - It is built in stage 11.
7. **The LLM proxy and the harness confinement get a stage of their own, stage 8, inside `agent-connector-kernel`** (approved by chat-01, 2026-11-21, relayed by chat-48 in t107:r1). They follow `LlmKeyInjectionProxy_261121_oo01` and `HarnessConfinement_261121_oo01` (`doc_SCIVICS003/docs/agent-connector-kernel/040_design`). The native-image probe becomes stage 9, `ssh-connector` stage 10 and wrap-up stage 11.
8. **Harnesses to support and test, and their staging** (the person's decision, 2026-11-21, relayed by chat-01 and chat-48 in t109:r1; `HarnessConfinement_261121_oo01` at `doc_SCIVICS003` commit `69a8df5`).

   | Harness | Model to test with | Confinement | Stage |
   |---|---|---|---|
   | Claude Code | Claude | Claude Code sandbox and deny settings | 8 |
   | OpenAI Codex CLI | OpenAI | rootless Podman | 9 |
   | OpenCode | GLM-5.3 on the B200 vLLM | rootless Podman | 9 |
   | Hermes Agent | GLM-5.3 on the B200 vLLM | rootless Podman | 9 |
   | GitHub Copilot CLI | the model of the Copilot subscription | rootless Podman | 9 |

   This supersedes the stage numbers in item 7: stage 8 is the LLM proxy, the confinement core and the Claude Code launcher; stage 9 is the Podman launchers; the native-image probe is stage 10, `ssh-connector` stage 11 and wrap-up stage 12. Work runs through stage 10 and stops before stage 11.

   Rule for each harness launcher: before writing it, check that harness's official docs that (1) it can connect to agent-connector-kernel's MCP server over localhost HTTP and (2) its LLM endpoint can be pointed at the LLM proxy, and record the URL and what the page says. If either fails, that launcher is not built and there is no workaround; it is reported to chat-48.

   Tests use dummy keys only. A run against a real model (Claude, OpenAI, GLM-5.3 on the B200 vLLM, Copilot) is not done without chat-48 arranging the credential or endpoint.
9. **Stage-9 decisions** (2026-11-22; Copilot CLI, the network and OpenCode by the person, the image base by chat-01; relayed by chat-48 in t118:r1; `HarnessConfinement_261121_oo01` at `doc_SCIVICS003` commit `4a31a5d`):
   - **GitHub Copilot CLI is dropped.** No launcher is built. The supported harnesses are Claude Code, Codex CLI, OpenCode and Hermes Agent.
   - **Network:** each Podman container runs with `--network=none`. The MCP server and the LLM proxy are reached only through Unix sockets bind-mounted into the container. Inside it, a relay exposes those sockets on 127.0.0.1. No pasta and no slirp4netns. The relay is `socat` from the Ubuntu packages.
   - **Image:** based on `ubuntu:24.04`. Each harness is installed the official way, and the latest released version is pinned at the start of stage 9, with the version and its source written into this plan.
   - **OpenCode** is tested with both GLM-5.3 and Qwen3.8 on the B200 vLLM. Qwen3.8 needs tool calling enabled in vLLM; if it is not enabled, that is reported, not worked around. That check needs the real endpoint, so it belongs to the real-model runs, not to stage 9.
10. **Unix-domain-socket listeners inside agent-connector-kernel** (chat-48's decision, 2026-11-22, t119:r1). The MCP server and the LLM proxy each also listen on a Unix domain socket (`java.nio` `UnixDomainSocketAddress`, no new dependency), with the same request handling as their loopback-TCP forms. The socket files are created 0600 in a directory only the launcher's user can read (0700). Under `--network=none` the container gets only those two sockets, and no loopback TCP port has to exist for it.
11. **Where the Podman confinement checks run** (the person's decision, 2026-11-22, relayed by chat-01 and chat-48 in t121:r1). They run on 192.168.5.14 (stonefly514) as user devteam, where chat-01 set up rootless Podman. No privileged pod on W206; builds and the normal test suite stay in W206 Jobs. On 5.14: the branch or the built artifacts are rsynced before each run and checked against the commit; ml-marker (port 8001), the GPU, the VNC display `:3` and the docker daemon are not touched; dummy keys and stub peers only; everything runs at nice 19; every container and image made is removed afterwards.

## Later (after the split)

- chat-ui's `/mcp` takes no key during the split: `app` sets `agent-connector-kernel.mcp.require-key=false` (Decision 13). When quarkus-chat-ui confines harnesses, it turns the setting on (`true`), and its `McpScopes` bean must then give each conversation a key. This was the accepted deviation of stage 6 (`9c4b530`), recorded by chat-01 on 2026-11-21; chat-48 restated it on 2026-11-22 (t144:r1).

## Target modules

The core side is the new `agent-connector-kernel` module plus the plugin jars that depend only on `plugin-api`. Neither `agent-connector-kernel` nor those plugins may depend on `app`.

| Module | artifactId | Side | Contents |
|---|---|---|---|
| `plugin-api` | `chat-ui-with-audit-trail-plugin-api` (unchanged) | core | Unchanged: `LlmProvider`, `ProviderContext`, `ProviderCapabilities`, `ChatEvent`, `ToolSet`, `ChatUiPlugin`, `ConversationTool`, `LlmProviderFactory`, `ProviderCreationContext`, `ToolParameter`, `PersistedLog`, `OneShotReply` |
| `agent-connector-kernel` (new) | `agent-connector-kernel` | core | The tool registry, the tool runner, the framework-free MCP tool endpoint, the plain tools and `PluginRegistry`. No Quarkus, CDI, MicroProfile or Vert.x dependency, and a Maven enforcer rule makes the build fail if one appears |
| `plugin-harness` | unchanged | core | Launches and watches the claude and codex CLIs. Stays a plugin jar, so an instance started without it has no code that starts a CLI (`ProviderAndToolPlugins_260912_oo01`, 「なぜ「設定でオフ」ではなく「jar が無い」か」) |
| `plugin-shell`, `plugin-web-tools` | unchanged | core | The `bash`, `web_search`, `fetch` and `scholar_search` tools |
| `app` | `chat-ui-with-audit-trail` (unchanged, so the uber-jar name and the deploy convention stay the same) | screen and record | Web UI, REST (`audittrail`), the actor tree (`core.actor`), the I/O log, the agent loop, the openai-compat provider, the inter-instance gateway, the Quarkus adapter of the MCP server. Depends on `agent-connector-kernel` |

The module is named `agent-connector-kernel`, not `nigsc-connector`. It is a library that both chat-ui and nigsc-connector use; nigsc-connector is a separate later artifact (see Background). Package names do not change, as in `ProviderAndToolPlugins_260912_oo01`, so moving a class does not change its imports.

### What moves to `agent-connector-kernel`

| Package | Classes |
|---|---|
| `com.scivicslab.chatui.agent` | `FileAccessScope`, `FileReadTool`, `FileWriteTool`, `FileRemoveTool`, `CalcExpression`, `DocSearchTool`, `ReferenceLinkTool`, `HttpDocs`, `DocsSource` (the interface only; its static `choose(RemoteNodes, …)` moves to the app class `DocsSources`), `ToolCatalog` (the `Spec` record and the core tools' specs only, see the cut below) |
| `com.scivicslab.chatui.core.plugin` | `PluginRegistry` (`OpenAiCompatProviderFactory` stays in `app`, because it imports `ChatUiActorSystem`) |
| `com.scivicslab.chatui.tool` (new) | `ToolRunner` (the plain-tool half of today's `ConversationToolRunner`), `ToolScope` (record: scope name, `FileAccessScope`, `ToolSet`, plugin tools), `ToolExtension` (interface: extra tool names and their handler), `ToolCallRecorder` (interface: one record per tool call), `DocsLookup` (interface: choose a `DocsSource` for a `node` argument), `ToolScopes` (scope name to `ToolRunner`; replaces the `toolRunners` map behind `ChatUiActorSystem.toolRunnerOf`) |
| `com.scivicslab.chatui.mcp` | `McpToolEndpoint` (new POJO: `list(scopeName)` and `call(scopeName, tool, argumentsJson)`, used by the Quarkus adapter in `app` and by the localhost HTTP server of stage 6; neither passes HTTP details into it) |

### What stays in `app`

- Packages `audittrail`, `core.actor`, `core.iolog`, `core.remote`, `core.service`, `logging`, `openaicompat`, `translation` and `bench`.
- The classes of package `agent` marked 2 in the per-class table below.
- `ChatUiMcpServer` and `ConversationToolFilter`, as thin adapters of the Quarkiverse extension that call `McpToolEndpoint`.
- A new `ConversationToolCatalog`, which adds the remaining built-in specs.
- `ConversationToolRunner`, which becomes the app's `ToolExtension`, `ToolCallRecorder` and `DocsLookup`.

### Per-class split of package `agent`

Side 1 is `agent-connector-kernel`, side 2 is `app`. The basis is what each class imports (an import scan of `app/src/main/java/com/scivicslab/chatui/agent/`) and what it refers to in its own package (a scan for class names with `//` comments removed). Two references found that way are in Javadoc only and do not bind: `ContextBudget` in `FileReadTool.java:176` and in `DocSearchTool.java:47`.

| Class | Side | Why |
|---|---|---|
| `FileAccessScope` | 1 | No imports from chat-ui |
| `FileReadTool`, `FileWriteTool`, `FileRemoveTool` | 1 | Use only `FileAccessScope` and each other |
| `CalcExpression` | 1 | Pure check of a `calc` argument; the JShell evaluation is cut 5 |
| `DocSearchTool`, `ReferenceLinkTool`, `HttpDocs` | 1 | Use `DocsSource` and each other; jackson tree model |
| `DocsSource` | both | The interface goes to 1; the static `choose(RemoteNodes, …)` (`DocsSource.java:51`) goes to 2 as `DocsSources` (cut 4) |
| `ToolCatalog` | both | `Spec` and the core specs go to 1; the other built-in specs go to 2 as `ConversationToolCatalog` (cut 1) |
| `AskChatTool` | 2 | Imports `ChatSession`, `PromptQueue`, `OpenRequests`, `Timekeeper` and `RemoteNodes` |
| `CreateChatTool`, `DeleteChatTool`, `ChatNameTool`, `GetChatLogTool`, `SetChatModelTool`, `TimekeeperTool`, `ProviderTools`, `OrganizationTools` | 2 | Import `ChatUiActorSystem` or `ConversationControls` |
| `GetChatStatusTool`, `ListChatModelsTool`, `SetAgentLoopTool`, `RequestMessagesTools` | 2 | Call `ChatSessionIIAR` through `IIActorSystem` |
| `SetCollaboratorTool` | 2 | Imports `RoleTable` |
| `LoadSkillTool` | 2 | Imports `SkillRegistry` |
| `RunningToolsTool`, `NodeDocs` | 2 | Import `core.remote.RemoteNodes` |
| `NodeNameListing` | 2 | Pure, but only `list_running_tools` uses it, and that tool is 2 |
| `OtherInstances` | 2 | MicroProfile `ConfigProvider` (`OtherInstances.java:3,52-54`) |
| `ChatInspection` | 2 | Imports `logging.RecentEntriesAccumulator` |
| `TextToolCallParser`, `ToolCall` | 2 | Parse `<invoke>` in model text for `ChatSession`'s agent loop |
| `ContextBudget` | 2 | Used by `ChatSession`, `ChatUiActorSystem` and `OpenAiCompatProvider` only |
| `WebPageSummarizer` | 2 | Used by `ChatSession`'s page-summarising transition only |

`FetchTool`, `WebSearchTool` and `ScholarSearchTool` are already in `plugin-web-tools` under the same package name, and stay there.

## Cutting the shared classes

1. **`ToolCatalog`** (`agent/ToolCatalog.java`): `BUILT_IN` (line 72) holds 32 specs, the conversation tools among them.
   - `agent-connector-kernel` keeps `Spec` (line 35) and the specs of `read`, `calc`, `search_docs`, `list_references`, `write` and `remove`.
   - `app/ConversationToolCatalog` builds the full ordered map: `ask_person` first, then the core specs in today's places, then the rest. The `<invoke>` text keeps today's order and wording. `ToolCatalogTest` proves it.
   - `ChatSession` (lines 284, 291, 298, 3308) and `ChatUiMcpServer` (line 50) read `ConversationToolCatalog`.
2. **`ConversationToolRunner`** (`core/actor/ConversationToolRunner.java`): `run` (line 174) mixes plain tools (lines 176–196 and 241–249) with tools that use the actor tree (lines 197–240).
   - `ToolRunner` in `agent-connector-kernel` runs the plain tools and the plugin tools.
   - It hands any other name to a `ToolExtension`, and it records each MCP call through a `ToolCallRecorder`. That replaces `record` (lines 254–269), which writes `IoLogStore` and the `.log` actor.
   - `search_docs` and `list_references` get their `DocsSource` from a `DocsLookup`. The default is the HTTP API; the app's implementation uses `RemoteNodes` (today lines 189–190).
   - The app's `ConversationToolRunner` keeps the actor-tree cases, the `IoPosition` supplier and the I/O-log writing.
   - `offers` (line 137) moves to core and asks the extension about its names.
3. **`ChatUiMcpServer` and `ConversationToolFilter`** (`mcp/`): they inject `ChatUiActorSystem` to find a runner (`ChatUiMcpServer` line 69, `ConversationToolFilter` line 29).
   - They call `McpToolEndpoint` instead, which resolves the scope through `ToolScopes`.
   - The `X-Chat-Ui-Conversation` header (lines 79–83) stays in the adapter, because it is HTTP.
   - `ConversationControls.publishToolRunner` (line 167) keeps its name and puts the runner into `ToolScopes`.
4. **`DocsSource`** (`agent/DocsSource.java`): the interface (line 14) moves to `agent-connector-kernel`. The static `choose(RemoteNodes, …)` (line 51) moves to the app class `DocsSources`, because `RemoteNodes` is the inter-instance gateway.
5. **Calculator**: `ConversationToolRunner.Calculator` (lines 72–80) wraps the turing-workflow class `JShellCalculator`.
   - In `agent-connector-kernel` it becomes an interface, `ExpressionEvaluator`. The app passes the JShell-backed implementation, so behaviour is unchanged.
   - A later native build passes an arithmetic-only one (see stage 10).
6. **Harness launch** (`plugin-harness`): the command and environment are built in two places, `CliProcess.startProcess`/`buildCommand` (lines 113–125 and 453–491) and `CodexProvider.sendPrompt`/`buildCommand` (lines 110–117 and 190–205).
   - Extract a POJO, `HarnessLaunch`: binary, argument list, environment to remove, environment to set, working directory, then `ProcessBuilder`.
   - Both callers use it. This is the one place the later sandbox settings and the LLM-proxy variables (`ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY`, `LlmKeyInjectionProxy_261121_oo01`) will be added.
   - No conversation state is touched by the launcher today: the plugin imports only `plugin-api`.

## How does a harness reach the core's tools? (decided: see Decisions 3)

The two design documents disagree. The person took the recommendation below on 2026-11-21. The analysis stays here as the reason for the decision.

- **Position A, the concept doc.** In `AgentConnectorKernelConcept_261121_oo01`, the harness calls the tools of the agent-connector-kernel process's MCP server (line 37 and the diagram at line 52), and line 40 says that calling those tools is all the harness can do towards the supercomputer and outside services. Line 101 leaves the transport between the harness and the MCP server undecided: stdin and stdout, or HTTP on `localhost` (needed when the harness runs in a container). The person decided on HTTP on `localhost` on 2026-11-21 (Decisions 5).
- **Position B, the chat-ui design.** In `HarnessToolsThroughOneMcpServer_261120_oo01`, line 72 says no MCP server is registered with the claude and codex CLIs. Lines 115–128 explain why: the CLIs keep the `<invoke>` path that `ChatSession` parses, so there is only one agent loop. Four things depend on that one loop: `ask_person` ending the turn, the step limit, the pause for an urgent request, and the I/O log in step order. Code agrees: the word `mcp` does not occur in `CliProcess.java`, `ClaudeCodeProvider.java` or `CodexProvider.java`. Today `/mcp` is used only by the openai-compat provider (`ToolCatalogAndChatUiMcpServer_261120_oo01` line 105).

What each choice does to the split:

- **A everywhere** (claude and codex inside chat-ui also call core tools over MCP):
  - `plugin-harness` would pass an MCP config to the CLI.
  - The six tools that drive a conversation cannot go over MCP (`ToolCatalogAndChatUiMcpServer_261120_oo01` line 97), so a CLI conversation would use two paths at once.
  - The four guarantees of position B would move into the CLI's own loop.
  - The core would need a scope that does not depend on a turn and step, because `IoPosition` (`ConversationToolRunner.java:66`) comes from `ChatSession`.
- **B everywhere:**
  - The split is unaffected inside chat-ui.
  - nigsc-connector has no `ChatSession` to parse `<invoke>`, so a harness launched by nigsc-connector would have no way at all to reach the core's tools.
- **A for nigsc-connector, B inside chat-ui:**
  - The core offers its tools through `McpToolEndpoint`, which needs two transports: the Quarkus HTTP adapter for chat-ui (as today) and an HTTP server on localhost inside agent-connector-kernel for nigsc-connector. `AgentConnectorKernelConcept_261121_oo01` line 101 left the choice between stdio and `localhost` HTTP open; the person chose `localhost` HTTP on 2026-11-21 (Decisions 5).
  - `ChatSession` keeps calling `ToolRunner` directly on the `<invoke>` path.
  - The core's `ToolScope` and `ToolCallRecorder` must not require a turn or a step. chat-ui's recorder adds them; nigsc-connector's records a sequence number.

Recommendation: **A for nigsc-connector, B inside chat-ui.**
- In nigsc-connector, MCP is the only way a harness can reach a tool that runs outside its sandbox, so A is required there. Position B is not possible there, because nothing parses `<invoke>`.
- Inside chat-ui, B keeps the four guarantees that the two-loop analysis (`HarnessToolsThroughOneMcpServer_261120_oo01` lines 119–126) shows are lost under A, and the claude and codex conversations already call tools reliably over `<invoke>` (line 128).

**Every stage below works for either choice.**
- The stages move code without changing any path.
- The core exposes the same `ToolRunner` to both callers: `ChatSession` (the `<invoke>` path) and `McpToolEndpoint` (MCP).
- `ToolScope` and `ToolCallRecorder` are written without a turn or a step from stage 5 on.
- No stage registers an MCP server with the CLIs, and no stage removes the `<invoke>` path.

With the decision taken, the localhost HTTP server for nigsc-connector is built in stage 6 (Decisions 5). No MCP config is passed to the harness inside chat-ui.

## Stages

Every stage ends in this order:
1. `rm -rf target` in every module.
2. `mvn test` (all modules, no `-DskipTests`) in a W206 k8s Job.
3. Compare the test counts and failures with stage 1.
4. Read the diff against `main`.

A test that moves to another module still counts once, so the total stays the same.

- [x] 0. Create the branch `nigsc-connector-split` and commit this plan (`e39e158`).
- [x] 1. **Baseline.**
  - W206 Job on the branch head (same tree as `main`): `rm -rf target`, `mvn test`. Record the counts per module.
  - Run the E2E checks against a throw-away chat-ui started inside the same Job, on a port outside 28000–28020: `e2e/McpServerCheck.java`, `e2e/ExternalMcpCheck.java`, `e2e/ToolCallingCheck.java`. Record their output.
  - **Result (2026-11-21, W206 Job `chat77-split-baseline`, pod on stonefly521, GraalVM CE 25):**
    - Library chain from `git archive`: POJO-actor `adc5a49`, Turing-workflow `8300246`, Turing-workflow-plugins `cadc4fd` (`-pl plugin-rst-frame -am`). All installed with their tests.
    - chat-ui at `e39e158` (same tree as `main` `7c4b507` apart from this file): `rm -rf target */target`, then `mvn -B test`, BUILD SUCCESS. Counts per module:

      | Module | Tests | Failures | Errors | Skipped |
      |---|---|---|---|---|
      | `plugin-api` | 0 | 0 | 0 | 0 |
      | `app` | 852 | 0 | 0 | 0 |
      | `plugin-web-tools` | 17 | 0 | 0 | 0 |
      | `plugin-shell` | 11 | 0 | 0 | 0 |
      | `plugin-harness` | 54 | 0 | 0 | 0 |
      | total | 934 | 0 | 0 | 0 |

    - Then `rm -rf target */target` and `mvn -B package` (the same 934 tests ran again, all passed) for the uber-jar `chat-ui-with-audit-trail-3.0.0-SNAPSHOT.jar`.
    - The throw-away instance ran inside the pod:
      - `e2e/ScriptedModelServer.java` on `127.0.0.1:18978`, with jackson from the pod's Maven repository;
      - chat-ui on port 18090 with `-Duser.home=/work/e2e/home`, `-Dchat-ui.read-roots=/work/e2e/root`, `-Dchat-ui.write-root=/work/e2e/root`, `-Dchat-ui.servers=http://127.0.0.1:18978`, `-Dchat-ui.default-model=fake`. Its working directory, and so its iolog database, was `/work/e2e`.
    - `McpServerCheck 18090`: 19 PASS, 0 FAIL, "ALL PASSED".
    - `ToolCallingCheck 18090`: 9 PASS, 0 FAIL, "ALL PASSED".
    - `ExternalMcpCheck` was not run in the first pass (it needs a real model that answers with `tool_calls` and an html-saurus `/mcp`, and the only html-saurus instances are on 28001 and 28009, inside the forbidden range). At chat-48's request it was run afterwards, in the same Job and against the same `e39e158` jar:
      - a throw-away html-saurus (a copy of the jar `html-saurus-portal-server-3.0.0-SNAPSHOT-shaded-261121141211.jar`, `serve --dir /work/ext/hs --port 18001`) serving a `git archive HEAD` copy of `doc_Base010`'s `docusaurus.config.ts`, `sidebars.ts`, `package.json` and `docs/ProjectStandard2`;
      - a throw-away chat-ui on port 18091 with `-Duser.home=/work/ext/home`, read and write roots under `/work/ext/root`, `-Dchat-ui.servers=http://192.168.5.21:30805` (the GPU broker), model `google/gemma-4-26B-A4B-it`.
      - `ExternalMcpCheck 18091 google/gemma-4-26B-A4B-it http://127.0.0.1:18001/mcp`: 8 PASS, 0 FAIL, "ALL PASSED".
      - After stopping and starting that chat-ui again, `ExternalMcpCheck 18091 --restored http://127.0.0.1:18001/mcp`: 1 PASS, 0 FAIL, "ALL PASSED".
    - No production code or test was changed, and nothing failed or needed fixing to run.
- [x] 2. **Empty `agent-connector-kernel` module.**
  - Add `agent-connector-kernel/pom.xml`. Dependencies: `plugin-api`, org.json, jackson-databind, and POJO-actor (needed when `ToolScopes` or the localhost HTTP server's request handling becomes an actor).
    - As built: only `plugin-api` (plus `junit-jupiter` for tests). An empty module needs nothing else; org.json, jackson-databind and POJO-actor are added in the stage whose moved classes use them, so every dependency is in the build because a class needs it.
  - Add `maven-enforcer-plugin` `bannedDependencies` for `io.quarkus*`, `io.quarkiverse*`, `jakarta.*`, `org.eclipse.microprofile*`, `io.vertx*`.
    - As built: `maven-enforcer-plugin` 3.5.0, execution `no-framework-in-agent-connector-kernel` (default phase `validate`), `searchTransitive` true. It bans those five patterns plus `javax.inject`, `javax.enterprise` (the old CDI coordinates) and `io.smallrye*` (Quarkus's MicroProfile implementations).
  - `app` depends on `agent-connector-kernel`. Parent `<modules>` order: `plugin-api`, `agent-connector-kernel`, `app`, plugins.
    - As built: the module order and the parent's managed `agent-connector-kernel` dependency are in, but `app` does **not** depend on the module yet. With the dependency, the first stage-2 run failed in `app` at `quarkus:generate-code`: Quarkus's dependency resolver opens every reactor dependency's `target/classes`, and an empty module has none (`/src/stage2/agent-connector-kernel/target/classes does not exist`). `app` gets the dependency in stage 3, together with the first moved classes.
  - Check: counts equal to stage 1.
  - **Result (2026-11-21, W206 Job `chat77-split-baseline`):**
    - `rm -rf target */target`, then `mvn -B test`, BUILD SUCCESS. The reactor runs `agent-connector-kernel` third, after `plugin-api`. Its enforcer log reads `Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed`.
    - Counts against stage 1:

      | Module | Stage 1 | Stage 2 |
      |---|---|---|
      | `plugin-api` | 0 | 0 |
      | `agent-connector-kernel` | (none) | 0 |
      | `app` | 852 | 852 |
      | `plugin-web-tools` | 17 | 17 |
      | `plugin-shell` | 11 | 11 |
      | `plugin-harness` | 54 | 54 |
      | total | 934 | 934 |

      Every run had 0 failures, 0 errors and 0 skipped.
    - Proof that the rule rejects a forbidden dependency:
      - The same tree was given `jakarta.inject:jakarta.inject-api` as a dependency of `agent-connector-kernel` (pod-local copy, not committed), then `mvn -B validate -pl agent-connector-kernel -am` was run.
      - Result: BUILD FAILURE at `enforcer:3.5.0:enforce (no-framework-in-agent-connector-kernel)`, with `Rule 0: …BannedDependencies failed`, the rule's message, and `jakarta.inject:jakarta.inject-api:jar:2.0.1 <--- banned via the exclude/include list`.
- [x] 3. **Move the plain tools and `PluginRegistry` to `agent-connector-kernel`.**
  - Add the `agent-connector-kernel` dependency to `app/pom.xml` (deferred from stage 2).
  - Move `FileAccessScope`, `FileReadTool`, `FileWriteTool`, `FileRemoveTool`, `CalcExpression`, `DocSearchTool`, `ReferenceLinkTool`, `HttpDocs`, the `DocsSource` interface (its `choose` goes to app `DocsSources`) and `PluginRegistry`, with their tests: `FileAccessScopeTest`, `FileReadToolMultiPathTest`, `FileRemoveToolTest`, `FileToolsScopeTest`, `CalcExpressionTest`, `ReferenceLinkToolTest`, `PluginRegistryTest`, `WriteKeepsCodeFencesTest` if it uses only these.
  - Check: counts equal, and `agent-connector-kernel` builds with the enforcer rule on.
  - As built:
    - Moved, package names unchanged: `FileAccessScope`, `FileReadTool`, `FileWriteTool`, `FileRemoveTool`, `CalcExpression`, `DocSearchTool`, `ReferenceLinkTool`, `HttpDocs`, `DocsSource` (package `com.scivicslab.chatui.agent`) and `PluginRegistry` (package `com.scivicslab.chatui.core.plugin`).
    - Moved tests: `FileAccessScopeTest`, `FileReadToolMultiPathTest`, `FileRemoveToolTest`, `FileToolsScopeTest`, `CalcExpressionTest`, `ReferenceLinkToolTest`. Two tests stay in `app`. `WriteKeepsCodeFencesTest` drives a `ChatSession`. `PluginRegistryTest` registers the app's `OpenAiCompatProviderFactory` (lines 52 and 81) and checks its kind and its "Local LLM" label; it stays unchanged, because rewriting it around a fake factory would change what it proves. The first stage-3 run moved it, and it failed to compile in `agent-connector-kernel`: same package name, so no import showed the dependency.
    - `DocsSource.choose(RemoteNodes, …)` is now the app class `DocsSources.choose`, because `RemoteNodes` is the inter-instance gateway. Its callers changed: `ConversationToolRunner` line 189 and `ToolNodesTest` lines 188, 215 and 223.
    - `ExpressionEvaluator` is not needed yet. `CalcExpression` only checks the argument; the JShell evaluation stays in `app`'s `ConversationToolRunner.Calculator` until stage 5.
    - `agent-connector-kernel` now also depends on jackson-databind (the tree model of html-saurus's JSON answers) and jsoup (`DocSearchTool` reads HTML pages). Neither is banned by the enforcer.
    - Every member that `app` calls on the moved classes is `public`. The package names are shared across the two jars, and package-private access across two class loaders would fail at run time.
  - **Result (2026-11-21, W206 Job `chat77-split-stage3`, pod on stonefly521):**
    - `rm -rf target */target`, then `mvn -B test`, BUILD SUCCESS. Then `rm -rf target */target` and `mvn -B package`; the same counts ran again, all passing.
    - The enforcer log for `agent-connector-kernel` reads `Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed`. None of the moved classes pulls in Quarkus, CDI, MicroProfile or Vert.x.

      | Module | Stage 1 | Stage 3 |
      |---|---|---|
      | `plugin-api` | 0 | 0 |
      | `agent-connector-kernel` | — | 60 |
      | `app` | 852 | 792 |
      | `plugin-web-tools` | 17 | 17 |
      | `plugin-shell` | 11 | 11 |
      | `plugin-harness` | 54 | 54 |
      | total | 934 | 934 |

      Every run had 0 failures, 0 errors and 0 skipped.
    - Tests whose module changed (from `app` to `agent-connector-kernel`): `CalcExpressionTest` 30, `FileReadToolMultiPathTest` 7 (reported under its display name "FileReadTool — several paths in one call"), `FileRemoveToolTest` 8, `FileAccessScopeTest` 5, `FileToolsScopeTest` 5, `ReferenceLinkToolTest` 5. That makes 60, and app's 852 − 60 = 792.
    - The E2E checks ran against throw-away instances in the pod, set up as in stage 1, using the stage-3 jar:
      - `McpServerCheck`: 19 PASS, 0 FAIL.
      - `ToolCallingCheck`: 9 PASS, 0 FAIL.
      - `ExternalMcpCheck`: 8 PASS, 0 FAIL. After a restart, `--restored`: 1 PASS, 0 FAIL.
    - The first stage-3 run failed to compile `PluginRegistryTest` in `agent-connector-kernel` (`cannot find symbol OpenAiCompatProviderFactory`, lines 52 and 81). That test went back to `app`; see "As built" above.
- [x] 4. **Cut `ToolCatalog`.**
  - Core `ToolCatalog` with `Spec` and the core specs; app `ConversationToolCatalog` with the ordered full map.
  - Check: `ToolCatalogTest` (the signature table, unchanged), `ChatSessionToolSetTest`, `BrokenToolCallTest`, `ChatSessionToolCallRepairTest`, `ToolCallingProviderTest`.
  - As built: `ToolCatalog` in `agent-connector-kernel` keeps `Spec`, `define`, `ofPlugin`, `requiredSentence`, `wrap` and the six core specs (`coreTools()`, `core(name)`). The app class `ConversationToolCatalog` builds the 32-tool ordered map with the core specs in their old places (`builtInTools()`, `builtIn(name)`, `conversationDrivingTools()`). Callers changed: `ChatSession`, `ConversationToolRunner`, `ChatUiMcpServer`, `OpenAiCompatProvider`, and the tests `ToolCatalogTest` and `ConversationToolRunnerTest` (calls only; the signature table is unchanged). `agent-connector-kernel` now also depends on org.json, for `Spec.inputSchema()`.
  - **Result (2026-11-21, W206 Job `chat77-split-s4to8`):** `rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS. Counts: `plugin-api` 0, `agent-connector-kernel` 60, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 54, total 934 (no test moved, none added), 0 failures, errors and skipped. The enforcer passed. E2E: `McpServerCheck` 19/19, `ToolCallingCheck` 9/9, `ExternalMcpCheck` 8/8, `--restored` 1/1.
- [x] 5. **Cut `ConversationToolRunner`.**
  - Core `ToolRunner`, `ToolScope`, `ToolExtension`, `ToolCallRecorder`, `DocsLookup`, `ExpressionEvaluator`. The app's `ConversationToolRunner` implements the extension, the recorder and the lookup.
  - `ToolScope` and `ToolCallRecorder` carry no turn and no step; the app's recorder reads its `IoPosition` itself. This keeps both choices of the open question possible.
  - Write the core unit test `ToolRunnerTest` (plain tools, a fake extension, a fake recorder) before the code: it is red because the classes are missing, then green.
  - Check: `ConversationToolRunnerTest`, `ConversationToolsTest`, `ExternalMcpToolsTest`, `NodeArgumentPointsToListRunningToolsTest`, `RunningToolsNodeNamesTest`, `ChatSessionHarnessToolIoTest`.
  - As built: `agent-connector-kernel` package `com.scivicslab.chatui.tool` has `ToolRunner`, `ToolScope`, `ToolExtension`, `ToolCallRecorder`, `DocsLookup` and `ExpressionEvaluator`. `ToolRunner` runs the plain tools and the plugin tools, hands any other name to its `ToolExtension` (which runs before the plugin tools, as the old switch did), and records each MCP call through its `ToolCallRecorder`. Neither interface carries a turn or a step.
    - The app's `ConversationToolRunner` keeps its public API (constructor, `offers`, `callFromMcp`, `run`, `IoPosition`, `Calculator`) and delegates to a `ToolRunner`. It supplies the I/O-log recorder (itself), the tools that operate ConversationSquad actors (an inner `AppTools` extension), and `DocsSources.choose` as the `DocsLookup`. `Calculator` now implements `ExpressionEvaluator`. `ChatSession` is unchanged.
    - New test `ToolRunnerTest` (6 tests), written first. Red run on W206: `mvn test -pl agent-connector-kernel -am` without the classes failed to compile (`cannot find symbol ToolExtension`, `ToolRunner`). Then green.
  - **Result (2026-11-21, W206 Job `chat77-split-s4to8`):** BUILD SUCCESS for `mvn -B test` and `mvn -B package` after `rm -rf target */target`. Counts: `plugin-api` 0, `agent-connector-kernel` 66 (the baseline's 60 plus the 6 new `ToolRunnerTest`), `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 54, total 940 = 934 + 6 new, with 0 failures, errors and skipped. The enforcer passed. E2E: `McpServerCheck` 19/19, `ToolCallingCheck` 9/9, `ExternalMcpCheck` 8/8, `--restored` 1/1.
  - **Correction (2026-11-21):** the `ExternalMcpCheck` result first recorded for this stage did not test this stage's jar. The stage-4 run left its chat-ui on 18091 and its html-saurus on 18001 running: the script saved a subshell's PID, not the JVM's, so its cleanup missed them. This stage's own instances then failed to bind ("Port 18091 seems to be in use"), and the check ran against the stage-4 jar. `McpServerCheck` and `ToolCallingCheck` were not affected. The script was fixed, and `ExternalMcpCheck` was rerun on this stage's own jar on fresh instances: 8/8 and `--restored` 1/1, with no port conflict.
- [x] 6. **MCP endpoint in `agent-connector-kernel`, Quarkus adapter in `app`.**
  - `McpToolEndpoint` and `ToolScopes`. `ChatUiMcpServer` and `ConversationToolFilter` delegate to them. `ChatUiActorSystem.toolRunnerOf` reads `ToolScopes`.
  - The localhost HTTP server of Decisions 5, inside `agent-connector-kernel`:
    - It is built on `com.sun.net.httpserver`, bound to the loopback address only, with POJO-actor concurrency (plain POJO first).
    - Every request names its scope and carries a key. A missing or wrong key is refused, comparing in constant time.
    - It calls the same `McpToolEndpoint.call(scopeName, …)` as the Quarkus adapter. No HTTP detail reaches `McpToolEndpoint`.
    - Its unit tests are written first and fail first: no key, wrong key, right key, unknown scope.
    - `app` does not start this server. chat-ui keeps its Quarkus `/mcp` exactly as today.
  - Check: unit tests, then in a W206 Job the E2E `McpServerCheck`, `ExternalMcpCheck` and `ToolCallingCheck` against a throw-away instance. Output must match stage 1.
  - As built:
    - `McpToolEndpoint` (`agent-connector-kernel`, package `com.scivicslab.chatui.mcp`): `offers(scope, tool)`, `call(scope, tool, argsJson)` and `list(scope)`, over a scope-name → `ToolRunner` function. It has no HTTP in it.
    - `ChatUiMcpServer` and `ConversationToolFilter` call it through `ChatUiMcpServer.endpointOf(chats)`. That resolves a conversation by its qualified name to `ConversationToolRunner.core()`. The `X-Chat-Ui-Conversation` header and the error texts are unchanged.
    - **Deviation from the plan line above:** `ChatUiActorSystem.toolRunnerOf` still reads chat-ui's own map, not `ToolScopes`. chat-ui's Quarkus `/mcp` takes no key, so `ToolScopes`, which holds a key per scope, belongs to the localhost server only. chat-ui's behaviour is unchanged.
    - The localhost server: `LocalMcpServer` binds to `InetAddress.getLoopbackAddress()` on `com.sun.net.httpserver` at `/mcp`. `ToolScopes` is a plain POJO run as an actor (`mcp-scopes`); it issues a 32-byte random key per scope and compares keys with `MessageDigest.isEqual`. Requests run on the `ActorSystem`'s managed thread pool. `McpRequestHandler` is a plain POJO that decides each answer:
      - 400 without the `X-Agent-Connector-Scope` header;
      - 404 for an unknown scope;
      - 401 without `Authorization: Bearer <the scope's key>`;
      - otherwise JSON-RPC `initialize`, `tools/list`, `tools/call`, or 202 for a notification.
    - `app` does not start this server.
    - `agent-connector-kernel` now also depends on pojo-actor (`${pojo-actor.version}`). The enforcer still passes.
    - New tests, written first: `McpRequestHandlerTest` (5: no key, wrong key, right key with `tools/call`, right key with `tools/list`, unknown scope and missing scope) and `LocalMcpServerTest` (1: loopback only, a real HTTP request with the key answered, a wrong key refused). Red run on W206: without the classes, `mvn test -pl agent-connector-kernel -am` failed to compile (`cannot find symbol ToolScopes`, `McpRequestHandler`, `LocalMcpServer`). Then green.
  - **Result (2026-11-21, W206 Job `chat77-split-s4to8`):** BUILD SUCCESS for `mvn -B test` and `mvn -B package` after `rm -rf target */target`. Counts: `plugin-api` 0, `agent-connector-kernel` 72 (60 + 6 `ToolRunnerTest` + 5 `McpRequestHandlerTest` + 1 `LocalMcpServerTest`), `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 54, total 946 = 934 + 12 new, with 0 failures, errors and skipped. The enforcer passed. E2E: `McpServerCheck` 19/19, `ToolCallingCheck` 9/9, `ExternalMcpCheck` 8/8, `--restored` 1/1.
  - **Correction (2026-11-21):** the `ExternalMcpCheck` result first recorded for this stage did not test this stage's jar. The stage-4 run left its chat-ui on 18091 and its html-saurus on 18001 running: the script saved a subshell's PID, not the JVM's, so its cleanup missed them. This stage's own instances then failed to bind ("Port 18091 seems to be in use"), and the check ran against the stage-4 jar. `McpServerCheck` and `ToolCallingCheck` were not affected. The script was fixed, and `ExternalMcpCheck` was rerun on this stage's own jar on fresh instances: 8/8 and `--restored` 1/1, with no port conflict.
- [x] 7. **`HarnessLaunch`, extracted in `plugin-harness`; moved to agent-connector-kernel in stage 11.**
  - **Moved (stage 11, chat-48's t168 answer):** `HarnessLaunch` and `McpEntry` are now in agent-connector-kernel, in the same package `com.scivicslab.chatui.harness`, so that any product can start a harness with them. `plugin-harness` depends on agent-connector-kernel as `provided`. The two tests of the class itself moved with it (`HarnessLaunchTest`, 2). The five that test `CliProcess` stay in `plugin-harness`: `HarnessLaunchPinTest` (4) and `CliProcessMcpEntryTest` (1, formerly `HarnessLaunchTest.claudeIsHandedTheMcpEntryOnlyWhenGivenOne`).
    - Tests first: the kernel's `HarnessLaunchTest` was added before the class moved, and it failed to compile ("cannot find symbol: class HarnessLaunch"). After the move it passed.
    - Result (W206 Job `chat77-s11`; `rm -rf target */target`, `mvn -B test`, `mvn -B package`, BUILD SUCCESS): `agent-connector-kernel` 101 → 103, `plugin-harness` 61 → 59, other modules unchanged (`app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `ssh-tools` 28); total 1010, the same as before the move, and 982 without `ssh-tools`. E2E: `McpServerCheck` and `ToolCallingCheck` ALL PASSED; `ExternalMcpCheck` and `--restored` exit 0.
    - `HarnessLaunch` is in the agent-connector-kernel jar only; the plugin jar no longer holds it.
    - Those three E2E checks do not load `plugin-harness`, so one more check did (`plugincheck.sh`). `app` was started with `-Dchat-ui.plugins=<the plugin-harness jar>` and logged `Plugin harness …: providers [claude, codex]`. A conversation put on `claude` was sent a prompt. A fake `claude` on `PATH` received `--output-format stream-json --input-format stream-json --verbose --model sonnet --permission-mode bypassPermissions`, the arguments `HarnessLaunchPinTest` pins (after `stdbuf -oL`). No missing-class error was logged.
  - The MCP entry `HarnessLaunch` can hand a harness is a URL plus a key (Decisions 5). No stdio-shaped entry. Inside chat-ui none is passed (Decisions 3).
  - First write a test that pins today's argv and environment for claude (`stdbuf -oL claude --output-format stream-json --input-format stream-json --verbose --model …`, `--permission-mode bypassPermissions`, `--tools ""` for the full tool set, `CLAUDECODE` and `CLAUDE_CODE_ENTRYPOINT` removed) and for codex (`codex exec [resume <thread>] --json --skip-git-repo-check -c sandbox_mode="workspace-write" [-m <model>] -`). It passes before and after the change.
  - Check: that test, `StreamEventParserTest`, `CliProcessAutonomousTurnTest`, `CliEndedWithoutResultTest`, `ClaudeCodeRetractionTest`, `ClaudeCodeCommandAnswerTest`, `CodexEventParserTest`, `HarnessPluginTest`, `SlashCommandHandlerTest`.
  - As built:
    - `HarnessLaunch` (record: command, environment variables removed and added, working directory) builds the `ProcessBuilder` for both CLIs. `CliProcess.launch` removes `CLAUDECODE` and `CLAUDE_CODE_ENTRYPOINT` and adds the API key when one is set. `CodexProvider.launch` adds `OPENAI_API_KEY` when one is set.
    - `HarnessLaunch.McpEntry(url, scope, key)` gives Claude Code's `--mcp-config` value: one HTTP server with `Authorization: Bearer <key>` and `X-Agent-Connector-Scope`. There is no stdio form.
    - `CliProcess.setMcpEntry` adds `--mcp-config` only when an entry is set, and chat-ui never sets one (Decisions 3). Codex is given no MCP entry in this stage.
    - The pin test `HarnessLaunchPinTest` (4: claude harness tool set, claude full tool set resumed with effort, codex, and claude's environment and working directory through a fake CLI) was written first and passed on the unchanged code (W206, `mvn test -pl plugin-harness -am`). Then it passed again after the change.
    - Limitation: the removal of `CLAUDECODE` and `CLAUDE_CODE_ENTRYPOINT` can only be seen when the test process has them set, and the W206 pod does not. `HarnessLaunchTest` covers removal itself with `PATH`.
    - New test `HarnessLaunchTest` (3: removed and added variables and working directory, the MCP entry's JSON, `--mcp-config` only when an entry is given).
  - **Result (2026-11-21, W206 Job `chat77-split-s4to8`):** BUILD SUCCESS for `mvn -B test` and `mvn -B package` after `rm -rf target */target`. Counts: `plugin-api` 0, `agent-connector-kernel` 72, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61 (54 + 4 `HarnessLaunchPinTest` + 3 `HarnessLaunchTest`), total 953 = 934 + 19 new (12 from stages 5 and 6, 7 from this stage), with 0 failures, errors and skipped. The enforcer passed. E2E: `McpServerCheck` 19/19, `ToolCallingCheck` 9/9, `ExternalMcpCheck` 8/8, `--restored` 1/1.
  - **Correction (2026-11-21):** the `ExternalMcpCheck` result first recorded for this stage did not test this stage's jar. The stage-4 run left its chat-ui on 18091 and its html-saurus on 18001 running: the script saved a subshell's PID, not the JVM's, so its cleanup missed them. This stage's own instances then failed to bind ("Port 18091 seems to be in use"), and the check ran against the stage-4 jar. `McpServerCheck` and `ToolCallingCheck` were not affected. The script was fixed, and `ExternalMcpCheck` was rerun on this stage's own jar on fresh instances: 8/8 and `--restored` 1/1, with no port conflict.
- [x] 8. **LLM proxy and harness confinement in `agent-connector-kernel`** (Decisions 7).
  - The LLM proxy of `LlmKeyInjectionProxy_261121_oo01`:
    - It runs on the JDK HTTP server, bound to the loopback address only, as in stage 6.
    - A harness is given `http://127.0.0.1:<port>/<scope>` as its LLM base URL and a throw-away key for that scope.
    - The proxy refuses an unknown scope (404) and a missing or wrong key (401). Keys are compared in constant time.
    - Otherwise it puts the real credential in the key's place and passes the request to the provider. The answer is streamed back as it arrives, Server-Sent Events included.
    - The throw-away keys live in an actor (plain POJO first). Requests run on the `ActorSystem`'s managed pool.
  - The Claude Code confinement of `HarnessConfinement_261121_oo01`:
    - a plain argument builder: the `--settings` JSON of that document, and `--mcp-config <agent-connector-kernel's MCP server only> --strict-mcp-config`;
    - the variables that point the harness at the LLM proxy (`ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY` = the throw-away key).
    - Podman confinement for the other harnesses is not part of this stage.
  - The enforcer rule stays unchanged, and only dummy keys are used in tests.
  - `app` uses neither part yet (Decisions 3). Both are plain classes with no Quarkus in them, so quarkus-chat-ui, or any product's launcher, can call them later.
  - Tests are written first and fail first:
    - proxy: no key, wrong key, right key, unknown scope, and one real-HTTP forward through a throw-away upstream;
    - confinement: the `--settings` JSON, and the arguments with `--mcp-config` and `--strict-mcp-config`.
  - Check: full `rm -rf target */target` and `mvn -B test`, with the counts against the baseline and the new tests listed separately; the enforcer rule; and the three E2E checks.
  - As built:
    - Package `com.scivicslab.chatui.llm`: `ProxyKeys` (plain POJO run as the actor `llm-proxy-keys`; a 32-byte throw-away key per scope, compared with `MessageDigest.isEqual`), `LlmProxyRequests` (plain POJO deciding each request), and `LlmProxy` (JDK HTTP server on the loopback address, requests on the managed pool, upstream through `java.net.http`, the answer copied back as it arrives).
    - The key is read from `x-api-key` or `Authorization: Bearer`, so both Claude Code's `ANTHROPIC_API_KEY` and its `ANTHROPIC_AUTH_TOKEN` work.
    - Package `com.scivicslab.chatui.confine`: `ClaudeConfinement` builds the `--settings` JSON of `HarnessConfinement_261121_oo01`, `--mcp-config <json> --strict-mcp-config`, and the proxy variables. `--mcp-config` takes the JSON as a string; Claude Code's CLI reference says it accepts files or strings.
    - `app` uses neither part. The enforcer is unchanged and passed. Tests use dummy keys and a throw-away upstream on the loopback address only.
    - New tests, written first: `LlmProxyRequestsTest` (4: no key, wrong key, right key, unknown scope), `LlmProxyTest` (1: real HTTP forward with the dummy credential put in, wrong key refused before the upstream), `ClaudeConfinementTest` (3: the settings JSON, the arguments with `--mcp-config` and `--strict-mcp-config`, the proxy variables). Red run on W206 without the classes: compile failure (`cannot find symbol ProxyKeys`, `LlmProxyRequests`, `LlmProxy`, `ClaudeConfinement`). Then green.
    - Doc check for the Claude Code launcher (rule of t109:r1), done after the argument builder was written and before any launcher uses it. Both points hold:
      1. MCP over localhost HTTP with headers: `claude mcp add --transport http … --header "Authorization: Bearer …"` and `"type": "http"` with `headers` (https://code.claude.com/docs/en/mcp). `--mcp-config` "Load MCP servers from JSON files or strings"; `--strict-mcp-config` loads only the servers passed with `--mcp-config` (https://code.claude.com/docs/en/cli-reference).
      2. LLM endpoint: `ANTHROPIC_BASE_URL` with `ANTHROPIC_AUTH_TOKEN` (sent as `Authorization: Bearer`) or `ANTHROPIC_API_KEY` (sent as `x-api-key`) (https://code.claude.com/docs/en/llm-gateway-connect, https://code.claude.com/docs/en/llm-gateway).
      Caveat from the docs: `ANTHROPIC_API_KEY` "needs a one-time approval in interactive sessions", which `ANTHROPIC_AUTH_TOKEN` avoids.
  - **Result (2026-11-21, W206 Job `chat77-split-s4to8`):** BUILD SUCCESS for `mvn -B test` and `mvn -B package` after `rm -rf target */target`. Counts: `plugin-api` 0, `agent-connector-kernel` 80 (72 + 4 `LlmProxyRequestsTest` + 1 `LlmProxyTest` + 3 `ClaudeConfinementTest`), `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61, total 961 = 934 + 27 new (19 from stages 5–7, 8 from this stage), with 0 failures, errors and skipped. The enforcer passed. E2E: `McpServerCheck` 19/19, `ToolCallingCheck` 9/9, `ExternalMcpCheck` 8/8, `--restored` 1/1 (run on this stage's jar; see the correction under stage 5).
- [x] 9. **Podman launchers for Codex CLI, OpenCode and Hermes Agent** (Decisions 8 and 9; Copilot CLI dropped).
  - Each launcher builds the rootless Podman command of `HarnessConfinement_261121_oo01`:
    - only the working directory is mounted, never agent-connector-kernel's directory or the user's home;
    - `--network=none`: the container reaches agent-connector-kernel's MCP server and the LLM proxy only through Unix sockets bind-mounted into it, which `socat` inside the container exposes on 127.0.0.1 (Decisions 9);
    - the harness's environment holds only the proxy's throw-away key.
  - Each launcher also writes the harness's own MCP and LLM-endpoint configuration.
  - Tests are written first and fail first, and use dummy keys only. Runs against real models wait for chat-48 (Decisions 8).
  - Doc check (rule of Decisions 8), done 2026-11-21 before any code:

    | Harness | (1) MCP over localhost HTTP with headers | (2) LLM endpoint to the proxy | Launcher |
    |---|---|---|---|
    | OpenAI Codex CLI | Yes: `[mcp_servers.<name>] url`, `http_headers`, `bearer_token_env_var` (https://developers.openai.com/codex/mcp) | Yes: `openai_base_url`, or `[model_providers.<id>] base_url` with `env_key`; user-level `~/.codex/config.toml` only, and project config cannot set them (https://developers.openai.com/codex/config-advanced) | build |
    | OpenCode | Yes: `type: "remote"`, `url`, `headers`, with `oauth: false` for header auth; "Remote servers use OAuth by default" (https://opencode.ai/docs/mcp-servers) | Yes: `providers.<id>.settings.baseURL`, key through `env` (https://opencode.ai/docs/providers) | build |
    | Hermes Agent | Yes: `mcp_servers.<name>.url` and `headers` in `~/.hermes/config.yaml` (https://hermes-agent.nousresearch.com/docs/user-guide/features/mcp) | Yes: `model.provider: custom`, `model.base_url`, `model.api_key` or `key_env` (https://hermes-agent.nousresearch.com/docs/integrations/providers) | build |
    | GitHub Copilot CLI | Yes: `"type": "http"`, `url`, `headers` in `~/.copilot/mcp-config.json`, or `copilot mcp add --transport http --header …` (https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-mcp-servers) | **Only with BYOK.** `providers.json` `baseUrl` and `apiKey` (written literally, not read from the environment), and "GitHub authentication is not required if you use your own LLM provider API keys (BYOK)" (https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/use-byok-models, https://docs.github.com/en/copilot/how-tos/copilot-cli/set-up-copilot-cli/authenticate-copilot-cli). The model of the Copilot subscription goes through the GitHub account, and no page says that endpoint can be pointed elsewhere. | **not built; reported to chat-48** |

  - Design consequence of Decisions 9: the MCP server (stage 6) and the LLM proxy (stage 8) listen on loopback TCP, and the JDK `com.sun.net.httpserver` cannot listen on a Unix socket. Something must therefore serve each of them on a Unix socket on the host side. Whether that is a host-side relay or a Unix-socket listener in agent-connector-kernel is to be settled with chat-48 before the launchers are built.
  - Tests (t118:r1): dummy keys only.
    - Unit tests first, red then green, for each launcher's argument and configuration builder.
    - A confinement proof per Podman harness image, inside a container started the way the launcher starts it: (a) both sockets work through the relay (an MCP `tools/list`, and one proxied request with a dummy key against a stub upstream); (b) an outbound connection to an external host fails; (c) a connection to a host-side TCP port fails.
  - **Part 1 (2026-11-22, W206 Job `chat77-split-s9a`), no Podman:**
    - Unix-domain-socket listener (Decisions 10): `UnixSocketHttpServer` (package `com.scivicslab.chatui.unixhttp`) is a small HTTP/1.1 server on a `java.nio` Unix socket. It handles one request per connection (`Connection: close`), reads `Content-Length` and chunked bodies, and runs connections on the `ActorSystem`'s managed pool. It creates the socket 0600 and a missing directory 0700, and refuses a directory others can read. `LocalMcpServer(system, Path)` and `LlmProxy(system, Path, …)` listen on a socket only, with the same request handling as their TCP forms (`McpRequestHandler`; the proxy's one `forward` method).
    - Launcher builders (package `com.scivicslab.chatui.confine`):
      - `PodmanConfinement.arguments` builds `podman run --rm -i --network=none --userns=keep-id --cap-drop=all --security-opt=no-new-privileges`, with the two sockets bind-mounted at `/run/agent-connector-kernel/{mcp,llm}.sock`, the working directory at `/work`, the configuration read-only, `-e ACK_LLM_KEY -e ACK_MCP_KEY` (values only in podman's environment), the image, and the relay `/usr/local/bin/ack-relay` before the harness.
      - It refuses any mount inside agent-connector-kernel's directory.
      - Inside the container the relay (`socat`) exposes the MCP server on 127.0.0.1:18765 and the proxy on 127.0.0.1:18766.
    - Harness configurations, each in the shape of its official doc:
      - `CodexHarness`: `~/.codex/config.toml`, a custom provider with `base_url` and `env_key`, `wire_api` left at its only value `responses`, and the MCP server with `url`, `bearer_token_env_var` and `http_headers` (https://learn.chatgpt.com/codex/config-file/config-reference, …/config-advanced, https://learn.chatgpt.com/codex/extend/mcp).
      - `OpenCodeHarness`: `~/.config/opencode/opencode.json` in the OpenCode 1 shape: `provider.<id>` with `"npm": "@ai-sdk/openai-compatible"`, `options.baseURL` and `options.apiKey: "{env:…}"`; `mcp.<name>` with `type: "remote"`, `url`, `oauth: false` and `headers` (https://opencode.ai/v1/docs/providers, https://opencode.ai/v1/docs/mcp-servers, https://opencode.ai/v1/docs/config). The current docs at opencode.ai/docs describe OpenCode 2 (`providers`, `mcp.servers`), which has no release.
      - `HermesHarness`: `~/.hermes/config.yaml` with `model.provider: custom`, `base_url` and `key_env`, and `mcp_servers.<name>.url` and `headers` with `${VAR}`, which Hermes expands in server entries (https://hermes-agent.nousresearch.com/docs/integrations/providers, …/docs/user-guide/features/mcp, …/docs/reference/mcp-config-reference).
    - Containerfiles in `agent-connector-kernel/containers/` (`ack-relay` plus `codex/`, `opencode/`, `hermes/`), based on `ubuntu:24.04` with `socat`. Not built or run in part 1. Pinned versions, checked 2026-11-22:

      | Harness | Version | Install | Source |
      |---|---|---|---|
      | Codex CLI | 0.171.0 | `npm install -g @openai/codex@0.171.0` (Ubuntu `nodejs`; engines node >=16) | GitHub openai/codex release `rust-v0.171.0` (Latest); npm `@openai/codex` latest 0.171.0. The quickstart's own installer is `curl -fsSL https://chatgpt.com/codex/install.sh | sh`, which documents no version pin. |
      | OpenCode | 1.19.6 | `npm install -g opencode-ai@1.19.6` (https://opencode.ai/v1/docs) | GitHub anomalyco/opencode release `v1.19.6` (Latest); npm `opencode-ai` latest 1.19.6 |
      | Hermes Agent | 0.23.3 | official installer, `bash -s -- --commit caafe5389ae585ef77387a962aee05b1bcf17029 --non-interactive --skip-browser --skip-computer-use` | GitHub NousResearch/hermes-agent release `v0.23.3` (Latest, "Release Date: November 19, 2026"); the tag's commit `caafe53`. PyPI `hermes-agent` is at 0.19.0 and is not used. |

    - Open risks for part 2: OpenCode 1 installs a provider's npm package (`@ai-sdk/openai-compatible`) when it first uses it, which `--network=none` blocks at run time unless the image has it; the Hermes installer's binary location (`~/.local/bin` in the Containerfile) is not documented.
    - New tests, written first. The red run on W206 failed to compile (`cannot find symbol UnixSocketHttpServer`, `PodmanConfinement`, `CodexHarness`, `OpenCodeHarness`, `HermesHarness`, method `socket()`). New: `UnixSocketHttpServerTest` 2, `LocalMcpServerUnixSocketTest` 3 (tools/list with the key, wrong key 401, 0600/0700), `LlmProxyUnixSocketTest` 1, `PodmanConfinementTest` 3, `HarnessConfigTest` 4.
    - **Result:** `rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS. Counts: `plugin-api` 0, `agent-connector-kernel` 93 (80 + 13 new), `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61, total 974 = 934 + 40 new (27 from stages 5–8, 13 from this part), with 0 failures, errors and skipped. The enforcer passed. E2E on this jar: `McpServerCheck` 19/19, `ToolCallingCheck` 9/9, `ExternalMcpCheck` 8/8, `--restored` 1/1. Native-image probe (stage 10's, now with the Unix socket): 12/12 on the JVM and 12/12 natively (48.2 s build), including tools/list over a Unix socket with the key, a wrong key refused, and the socket 0600.
  - **Blocked (2026-11-22): rootless Podman does not run in the W206 Job pod.** Probe Job `chat77-podman-probe` (ubuntu:24.04 pod on stonefly521, default pod security, Podman 4.9.3 from the Ubuntu packages, user `probe` with `/etc/subuid` `probe:165536:65536`):
    - `unshare --user --map-root-user true` works;
    - `podman info` and `podman run --network=none ubuntu:24.04` both fail with `newuidmap: write to uid_map failed: Operation not permitted` / `cannot set up namespace using "/usr/bin/newuidmap": exit status 1` (exit 125);
    - Podman also warns `"/" is not a shared mount`, and `/dev/fuse` does not exist in the pod;
    - kernel 6.8.0-101-generic, `max_user_namespaces` 514234, `unprivileged_userns_clone` 1, seccomp off, CapEff `00000000a80425fb`.
    - Stage 9 stops here until chat-48 says how to proceed (t118:r1).
  - **Part 2 (2026-11-22): the Podman confinement checks on 192.168.5.14 (stonefly514), as devteam, with rootless Podman 4.9.3** (Decisions 11). Builds and the normal test suite ran in W206 Job `chat77-split-s9b`.
    - Code changes:
      - `PodmanConfinement` now passes `--userns=keep-id:uid=1001,gid=1001`, and each Containerfile creates the image user `agent` with uid and gid 1001 pinned. The first run on 5.14 used the part-1 jar, with a bare `--userns=keep-id`. That mapped devteam to uid 1002 inside the container, while the image user `agent` was 1001, so the relay got `Permission denied` on both 0600 sockets in all three images. That run failed both socket checks and `upstream-got-real-key`; every other check passed. `PodmanConfinementTest` was changed first and failed (red), then the builder was fixed (green).
      - The Hermes Containerfile adds `libatomic1`. The installer's Node.js needs `libatomic.so.1`, and the installer's own error message names that library.
      - The LLM proxy now answers like stage 10's MCP server. An unknown scope gets the same 401, with the same body, as a wrong key, in both the TCP and Unix-socket forms, because both go through `LlmProxyRequests`. A request that names no scope still gets 400. `LlmProxyRequestsTest` was changed first: `anUnknownScopeIsRefused` became `anUnknownScopeIsRefusedLikeAWrongKey` (401 and the same `Refuse` as a wrong key), and `aRequestThatNamesNoScopeIs400` was added (`/` and empty give 400). On W206 it failed with `expected: <401> but was: <404>` and `expected: <400> but was: <404>`. Then `LlmProxyRequests` was fixed.
      - New `e2e/ConfinementCheck.java`, a `main()` outside the Maven build (`TestingStandard_260404_oo01`). Usage: `java -cp <agent-connector-kernel jar and its dependencies> e2e/ConfinementCheck.java <codex|opencode|hermes> <image> <run dir>`.
        - On the host it starts `LocalMcpServer` and `LlmProxy` on Unix sockets, a stub upstream and a stub on host 127.0.0.1. It plants a marker in an agent-connector-kernel directory and one in `~/.ssh`, and removes the `~/.ssh` marker at the end. The real key is a dummy, `ACKREALDUMMY-<random>`.
        - It builds the arguments with `PodmanConfinement.arguments`, exactly as the launcher does. Only the trailing harness command is replaced, by `sh -c <check script>`, and five `-e` variables are added for the script: `HOST_STUB_PORT`, `HOST_IPS`, `KERNEL_MARKER_PATH`, `SSH_MARKER_PATH`, `CONFIG_PATH`. The keys reach Podman only through its environment. The run is under `nice -n 19`.
        - The script builds the secret prefixes from halves, so the secrets never appear on its command line.
    - W206 (`rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS):
      - counts: `plugin-api` 0, `agent-connector-kernel` 94, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61;
      - total 975 = 934 + 41 new (27 from stages 5–8, 13 from part 1, and 1 from this part, `aRequestThatNamesNoScopeIs400`), with 0 failures, errors and skipped;
      - the enforcer passed;
      - E2E on this jar: `McpServerCheck` 19/19, `ToolCallingCheck` 9/9, `ExternalMcpCheck` 8/8, `--restored` 1/1.
    - What ran on 5.14:
      - the agent-connector-kernel jar built from this tree in that Job (sha256 `fb7df521a26bde7e38659d32113b8e443dadd3235290ef4e70b04ea0156f230d`, the same on 5.14 after rsync), its dependency jars, the Containerfiles and `ConfinementCheck.java`, all checked with `sha256sum -c` on 5.14 before the run;
      - GraalVM CE 25+37.1 unpacked in `~/chat77-confinement/jdk`, because 5.14's system Java is 21;
      - the images, built with `nice -n 19 podman build`: `localhost/ack-codex:0.171.0`, `localhost/ack-opencode:1.19.6`, `localhost/ack-hermes:0.23.3`.
    - Launcher arguments, the same for each harness except the image, the config mount and the command: `podman run --rm -i --network=none --userns=keep-id:uid=1001,gid=1001 --cap-drop=all --security-opt=no-new-privileges -v <socket dir>/mcp.sock:/run/agent-connector-kernel/mcp.sock -v <socket dir>/llm.sock:/run/agent-connector-kernel/llm.sock -v <work>:/work -w /work -v <config>:<harness config path>:ro -e ACK_LLM_KEY -e ACK_MCP_KEY <image> /usr/local/bin/ack-relay <harness>`. The config paths are `/home/agent/.codex/config.toml`, `/home/agent/.config/opencode/opencode.json` and `/home/agent/.hermes/config.yaml`.
    - **Result: 16/16 PASS for each of Codex CLI, OpenCode and Hermes Agent.**

      | Check | Command (inside the container unless marked host) | Codex CLI | OpenCode | Hermes Agent |
      |---|---|---|---|---|
      | 1. MCP tools/list through the relay | `POST /mcp` tools/list via `socat TCP:127.0.0.1:18765`, with the MCP key | PASS | PASS | PASS |
      | 1. One proxied request through the relay | `POST /check/v1/chat/completions` via `socat TCP:127.0.0.1:18766`, with the throw-away key | PASS | PASS | PASS |
      | 1. External TCP fails | `socat -T3 /dev/null TCP:1.1.1.1:443` | PASS | PASS | PASS |
      | 1. DNS fails | `getent hosts example.com` | PASS | PASS | PASS |
      | 1. Host loopback port fails | `socat TCP:127.0.0.1:<port>`, where the host stub listens on host 127.0.0.1 | PASS | PASS | PASS |
      | 1. Host addresses fail | `socat TCP:<each host IPv4: 10.28.163.1, 172.17.0.1, 192.168.5.14>:<port>` | PASS | PASS | PASS |
      | 1. Host gateways fail | `socat TCP:10.0.2.2:<port>` and `TCP:10.88.0.1:<port>` | PASS | PASS | PASS |
      | 2. agent-connector-kernel dir marker unreadable | `cat <run>/kernel-dir/marker` | PASS | PASS | PASS |
      | 2. `~/.ssh` marker unreadable | `cat /home/devteam/.ssh/ack-confinement-marker-<random>` | PASS | PASS | PASS |
      | 2. Marker nowhere in the container | `grep -rs <marker prefix> /`, except proc, sys and dev, finds nothing | PASS | PASS | PASS |
      | 3. Real key not in `env` | `env`, grep for the real-key prefix, finds nothing | PASS | PASS | PASS |
      | 3. Real key not in `/proc/1/environ` | `tr '\0' '\n' < /proc/1/environ`, grep for the real-key prefix, finds nothing | PASS | PASS | PASS |
      | 3. Real key not in any file, mounted ones included | `grep -rs <real-key prefix> /` finds nothing | PASS | PASS | PASS |
      | 3. Config names the throw-away variable | `grep ACK_LLM_KEY <harness config path>` | PASS | PASS | PASS |
      | 3. Throw-away key where the harness expects it | host: sha256 of `$ACK_LLM_KEY` in the container = sha256 of the proxy's throw-away key | PASS | PASS | PASS |
      | 3. The upstream got the real key | host: the stub upstream received `Authorization: Bearer <dummy real key>` | PASS | PASS | PASS |

    - Afterwards, on 5.14:
      - the three images and every intermediate layer were removed;
      - `podman ps -a` and `podman ps -a --external` list nothing;
      - `podman images -a` lists only `docker.io/library/ubuntu:24.04`, which chat-01 pulled and which is not mine;
      - no `ack-` file is left in `~/.ssh`.
      - Nothing outside `~/chat77-confinement` was touched: not the docker daemon or its images, not ml-marker (port 8001), the GPU or display :3.
    - Still open: the risk from part 1 that OpenCode 1 installs `@ai-sdk/openai-compatible` on first use. The checks run the script in place of the harness, so that risk shows only in a run against a model, which waits for chat-48 (Decisions 8).
  - Check: full `rm -rf target */target` and `mvn -B test`, with the counts against the baseline and the new tests listed separately; the enforcer rule; and the three E2E checks.
- [x] 10. **Native-image probe of `agent-connector-kernel`.**
  - An E2E program with `main()`, outside the Maven build (testing standard `TestingStandard_260404_oo01`): it builds a `ToolRunner` over a temporary directory, lists the tools, and calls `write`, then `read`.
  - Build it with GraalVM CE `native-image` in a W206 Job (ubuntu:24.04 plus the GraalVM tarball) and run it.
  - It also starts the localhost HTTP server of stage 6 inside the native image and sends it one request with the right key. The check passes when the server compiles into the image and answers that request.
  - It also compiles and runs the LLM proxy of stage 8 (one forward through a throw-away upstream with a dummy key) and the confinement argument builder.
  - Record what fails. Expected: `calc` through JShell, `PluginRegistry`'s runtime jar loading, jackson-databind, and POJO-actor, which is still unproven under native-image (`ConcurrencyWithPojoActor_261121_oo01` line 63).
  - This stage reports; it does not have to pass. What to exclude or replace is decided with chat-48.
  - **Result (2026-11-22, W206 Job `chat77-split-s10`, GraalVM CE 25+37.1):** `e2e/KernelNativeProbe.java` (a `main()` outside the Maven build) was compiled against `agent-connector-kernel`'s jar and its dependencies, built with `native-image --no-fallback` (47.6 s, a 39.8 MB executable, 0 warnings), and run.
    - The JVM run and the native run each gave 9 PASS, 0 FAIL: the core tools are listed; `write` runs; `read` returns what `write` wrote; `calc` runs on the given evaluator; the MCP server is bound to the loopback address; it answers a request with the key; it refuses a wrong key; the LLM proxy forwards with the real credential in place of the throw-away key; and the confinement arguments are built.
    - So `com.sun.net.httpserver`, `java.net.http`, POJO-actor (the scopes and keys actors, the managed pool), org.json and jsoup's classes on that path all work in the native image with no reachability configuration.
    - Not exercised, so still open: `calc` through JShell (the probe passes an arithmetic-only evaluator, as cut 5 planned); `PluginRegistry`'s runtime jar loading (expected not to work in a native image, where plugins would be compiled in); and the jackson-databind path of `search_docs` and `list_references`, which needs an html-saurus to answer.
    - Every process the probe starts runs inside the probe and ends with it. Afterwards no JVM was left in the pod.
  - The stage also changed stage 6's server: an unknown scope now gets the same 401, with the same body, as a wrong key, so a local process without a key cannot learn which scope names exist. A missing scope header still gets 400. `McpRequestHandlerTest.anUnknownScopeIsRefusedLikeAWrongKey` was changed first and failed on W206 (`expected: <401> but was: <404>`). Then `McpRequestHandler` was fixed.
  - Counts (`rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS): `plugin-api` 0, `agent-connector-kernel` 80, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61, total 961 = 934 + 27 (no new test; one test changed), with 0 failures, errors and skipped. The enforcer passed. E2E on this stage's jar: `McpServerCheck` 19/19, `ToolCallingCheck` 9/9, `ExternalMcpCheck` 8/8, `--restored` 1/1, with no port conflict.
- [ ] 11. **`ssh-connector`** (Decisions 6).
  - It produces two things:
    - a module of its own for the ssh tools, which quarkus-chat-ui can reuse;
    - a runnable `ssh-connector` artifact with a `main` entry point. It starts `agent-connector-kernel`'s MCP server (the localhost HTTP server of stage 6) and the LLM proxy, and launches a harness through `HarnessLaunch` (stage 7), handing it the MCP entry as a URL plus a key.
  - The ssh tools are specified in "Stage 11 spec: the ssh tools" below (chat-48, 2026-11-22, on chat-01's t1775:r1).
  - Check: an E2E program with `main()`, outside the Maven build (`TestingStandard_260404_oo01`). Plus full `rm -rf target`, `mvn test` with the counts against stage 1, and the E2E checks of stage 6, which show chat-ui is unchanged.
  - It uses the LLM proxy and the confinement of stage 8, and the one Quarkus domain socket and one-time keys of steps 2–3.

### Stage 11 spec: the ssh tools (chat-48, 2026-11-22)

Design inputs: `SshOnlyNoWebApi_261121_oo01` (doc_SCIVICS003 `docs/ssh-connector/040_design`), the Under the Hood of `AgentConnectorKernelConcept_261121_oo01`, `NigscConnectorComposition_261121_oo01` (doc_Infra001), and chat-01's t1775:r1.

**What must hold** (from those documents; each one gets a test):
- P1. The harness never reads `~/.ssh` and never holds the ssh key. ssh-connector, outside the container, runs OpenSSH `ssh` itself. The container still gets only the one socket (stage 9 part 2's checks stay green).
- P2. Nothing the model passes can run a command on the user's computer. `ssh` is started by `ProcessBuilder` with an argument array, never through a shell, with the fixed options below, and `--` before the host name. Model text appears only after the host name, as one argument, or on standard input.
- P3. Files written on the user's computer stay under ssh-connector's working directory (`FileAccessScope`). No `..`, no absolute paths outside it, no symlink that leads out, no overwriting of an existing file unless the tool call says so.
- P4. The model chooses no ssh option, host, user, key, port, `ProxyCommand`, `ProxyJump` or `LocalCommand`. They all come from ssh-connector's own `ssh_config`.

**The `ssh` command line, fixed** (exactly as `SshOnlyNoWebApi_261121_oo01`):
```
ssh -F <ssh-connector's ssh_config> -o BatchMode=yes -o StrictHostKeyChecking=accept-new
    -o IdentitiesOnly=yes -o ClearAllForwardings=yes -o ForwardAgent=no -o PermitLocalCommand=no
    -- <host alias from ssh-connector's config> '<remote command>'
```
- ssh-connector writes its own `ssh_config` (0600, in a 0700 directory of its own). Each `Host` block sets `IdentityFile`, `IdentitiesOnly yes`, `BatchMode yes`, `StrictHostKeyChecking accept-new`, `UserKnownHostsFile <ssh-connector's own known_hosts>` and `GlobalKnownHostsFile /dev/null`. The user's `~/.ssh/config` and `~/.ssh/known_hosts` are never read.
- The host alias is fixed per deployment, in ssh-connector's config, for example `nig-a003`. The model never names a host.
- Each `ssh` run has a time limit (default 120 s, settable) and an output limit (default 1 MiB per stream, settable). When either is hit, the process is killed, and the tool answers with an error saying which limit.
- Remote strings that ssh-connector builds, such as a path inside `cat > …`, are POSIX single-quoted: `'` becomes `'\''`. A value containing NUL or a newline is refused before `ssh` is started.

**The tools** (MCP tools, registered on agent-connector-kernel through `ToolManager`, in their own module `ssh-tools`):

| Tool | Arguments | What runs on the cluster | Checks before running |
|---|---|---|---|
| `run_command` | `command` (string) | `ssh … -- <host> '<command>'`. The command runs on the cluster under the user's own account. | NUL and newline refused; output and time limits. |
| `submit_job` | `script` (string), `name` (optional: letters, digits, `-`, `_`, up to 64 characters) | The script goes over standard input to `cat > <jobs dir>/<id>/job.sh`, then `sbatch <jobs dir>/<id>/job.sh`. The answer is the Slurm job id parsed from `Submitted batch job N`. | `<id>` is chosen by ssh-connector; `name` is checked against its pattern; the jobs dir comes from config. |
| `job_status` | `job_id` | `squeue -j <id> -h -o …`, and if it isn't found there, `sacct -j <id> -n -P -o …` | `job_id` must match `^[0-9]+(_[0-9]+)?$`. |
| `cancel_job` | `job_id` | `scancel <id>` | the same pattern. |
| `list_remote` | `path` | `ls -la -- '<path>'` | NUL and newline refused; quoted. |
| `upload_file` | `local_path`, `remote_path` | The local file goes over standard input to `cat > '<remote_path>'`. | `local_path` must resolve inside the working directory (P3), with a size limit (default 64 MiB). |
| `download_file` | `remote_path`, `local_path`, `overwrite` (default false) | `cat -- '<remote_path>'`, with standard output streamed to the file | `local_path` must resolve inside the working directory; parent directories are created inside it only; an existing file is refused unless `overwrite`; a size limit (default 64 MiB). The file is written to a temporary name and then moved. |

- `run_command` is kept, as the concept document's Under the Hood decides: `submit_job` already runs anything on the cluster under the user's account, so dropping the general tool would protect nothing more. What the tools must never allow is a command on the user's computer (P2), which no tool can reach.
- The Slurm command names (`sbatch`, `squeue`, `sacct`, `scancel`) and the jobs directory are deployment settings, so nigsc-connector can set its own values (partitions and the like come later).
- Concurrency: one `SshRunner` POJO per host, run as a POJO-actor actor, so ssh calls to one host go one at a time by default. Nothing runs on the caller's thread for longer than one call. No class name ends in `Actor`.

**The `ssh-connector` application** (module `ssh-connector`, a Quarkus app with `quarkus-maven-plugin`):
- It depends on agent-connector-kernel and `ssh-tools`.
- It serves the MCP server and the LLM proxy on the one owner-only domain socket, with `require-key=true` (Decision 13).
- It launches one harness, confined, through `HarnessLaunch` and the Podman launchers.
- Configuration lives in one file in ssh-connector's directory: the host alias and its `ssh_config` values, the key path, the jobs directory, the limits, the harness and model. The native build (`-Dnative`) is tried in a W206 Job and reported; it doesn't have to pass in this stage.

**Tests, written first and red, then green:**
- Unit tests (W206, `mvn test`):
  - the argument array: fixed options in order; `--` immediately before the host alias; model text only after it, as one element;
  - a `command` of `-oProxyCommand=touch /tmp/x` stays one argument after the host, and nothing runs on the user's computer. Check this by running real `ssh -G` on the generated argument array; `proxycommand` must be absent;
  - quoting: `a'b`, `$(id)`, `` `id` `` and `; rm -rf ~` stay literal inside the single-quoted remote string; NUL and newline are refused;
  - `job_id` and `name` patterns: accepted and refused cases;
  - `local_path`: `../x`, `/etc/passwd`, a symlink pointing out of the working directory, and an existing file without `overwrite` are all refused; a nested new path inside the working directory is accepted;
  - the time and output limits kill the process and say which limit;
  - `ssh_config` is written 0600 in a 0700 directory, and every required key is present.
- E2E on 5.14 (a `main()` outside the build), against a throw-away sshd container, never a real cluster:
  - The image is built from `ubuntu:24.04` with `openssh-server`. It has fake `sbatch`, `squeue`, `sacct` and `scancel` scripts that record their arguments and answer like Slurm. It listens only on a host loopback port.
  - A fresh dummy ed25519 key and a fresh host key are made for each run. ssh-connector's `known_hosts` starts empty, and `accept-new` records the host key. A second run with a changed host key must fail with `Host key verification failed`.
  - Each tool runs once through `ssh-connector`, as a direct MCP call with the key. `submit_job` must return the fake job id, and the fake `sbatch` must have received the script exactly.
  - One confined harness (OpenCode, with the stub model) calls `submit_job` and then `job_status` through MCP. The container still passes the 16 confinement checks, including that `~/.ssh` and the dummy ssh key cannot be read from inside it.
  - Afterwards the sshd container and its image are removed. `podman ps -a` and `podman images -a` are shown, and the dummy keys are deleted.
- Rules as for every step: W206 for builds and `mvn test`, never `-DskipTests`; nice 19 on 5.14; don't touch docker, ml-marker, the GPU or `:3`; commit on this branch; don't push.
### Stage 11, step 1: `ssh-tools` (2026-11-22, W206 Job `chat77-s11`)

- **Module `ssh-tools`** (a plain jar on agent-connector-kernel and POJO-actor; package `com.scivicslab.sshtools`):
  - `SshCommandLine`: `ssh -F <config>`, then the fixed options in the spec's order, then `--`, the host alias and the remote command as one element. An alias that is empty or starts with `-` is refused.
  - `RemoteText`: POSIX single quoting (`'` becomes `'\''`). NUL, `\n` and `\r` are refused, and so is an empty value.
  - `SshConfigFile`: writes `ssh_config` (0600) and an empty `known_hosts` (0600) in a 0700 directory. An existing directory that is not 0700 is refused and left as it is. Each `Host` block has `HostName`, `User`, optionally `Port`, `IdentityFile`, `IdentitiesOnly yes`, `BatchMode yes`, `StrictHostKeyChecking accept-new`, `UserKnownHostsFile <own>`, `GlobalKnownHostsFile /dev/null`, and optionally `ProxyJump <another block>`. Values holding white space, quotes, `#`, `=` or control characters are refused.
  - `SshRunner` (a POJO, run as one actor per host): starts the argument array with `ProcessBuilder`, never through a shell. Standard input comes from bytes or a file, and standard output goes to memory or a file. At the time limit or an output limit it kills the process and its descendants, for example the ssh of a `ProxyJump`, and the result names the limit.
  - `JobArguments`: `^[0-9]+(_[0-9]+)?$` for `job_id`; letters, digits, `-` and `_`, up to 64 characters, for `name`.
  - `LocalFiles`: a path must lie under the working directory. Every existing directory on the way must be a real directory, not a symbolic link; the target may not be a symbolic link or a directory; an existing file is refused unless `overwrite`. Reading takes only a regular file whose real path is inside.
  - `SshTools` (a `ToolExtension`): the seven tools.
    - `submit_job` runs `mkdir -p -- '<dir>' && cat > '<dir>/job.sh' && sbatch [--job-name='<name>'] '<dir>/job.sh'`, with the script on standard input. `<dir>` is `<jobs dir>/<yyyyMMdd-HHmmss>-<8 hex>`.
    - `job_status` asks `squeue -j N -h -o '%i|%j|%T|%M|%R'`, then `sacct -j N -n -P -o JobID,JobName,State,ExitCode,Elapsed`.
    - `download_file` writes a dot-named temporary file next to the target and then moves it into place. Without `overwrite` the move fails if the target exists.
- **Tests first: 28 in 7 classes.**
  - Red against the skeleton (every method throws `UnsupportedOperationException`): 28 of 28 failed (`mvn -B test -pl ssh-tools -am`).
  - Then green: 28 of 28 passed.
  - `SshCommandLineTest.realSshDashGDoesNotReadAnOptionAfterTheAlias` runs OpenSSH 9.6p1's `ssh -G` on the generated array. With `--`, `proxycommand` has no `touch`. The control run without `--` does show `touch`, and the marker file is never created.
  - `RemoteTextTest` reads each quoted value back through `sh -c 'printf %s …'`: `a'b`, `$(id)`, `` `id` ``, `; rm -rf ~` and two more all stay literal.
  - `SshToolsTest` runs the seven tools against a fake `ssh` that records its arguments and standard input. A refused argument never starts it (0 calls).
- **Result** (`rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS):
  - counts: `plugin-api` 0, `agent-connector-kernel` 101, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61, `ssh-tools` 28; total 1010, with 0 failures;
  - against 982: +28, all in `ssh-tools`;
  - E2E: `McpServerCheck` and `ToolCallingCheck` ALL PASSED; `ExternalMcpCheck` and `--restored` exit 0.
- The build pod now also installs `openssh-client`, for `ssh -G`.
- `HarnessLaunch` moved to agent-connector-kernel first (chat-48, t168; see stage 7), in a commit of its own.

### Stage 11, steps 1 and 2: the `ssh-connector` module and application (2026-11-22, W206 Job `chat77-s11`)

- **Module `ssh-connector`**: a Quarkus application (`quarkus-maven-plugin`, an uber-jar, `ssh-connector-3.0.0-SNAPSHOT.jar`, and a `native` profile for `-Dnative`). It depends on agent-connector-kernel and `ssh-tools` only.
  - `SshConnectorMain` (`@QuarkusMain`): the directory is `-Dssh-connector.dir`, else `$SSH_CONNECTOR_DIR`, else `~/.ssh-connector`. It must be 0700 and hold `ssh-connector.properties`. Main hands Quarkus that file as `quarkus.config.locations`, the socket `<dir>/sockets/agent-connector-kernel.sock` as `quarkus.http.domain-socket`, and the log `<dir>/ssh-connector.log`. A `-D` on the command line wins over the file.
  - `ConnectorSettings`: the one settings file. Required: `ssh-connector.host.alias`, `.host.name`, `.host.user` and `ssh-connector.key`, an absolute path. Optional: `.host.port`; `ssh-connector.jump.{alias,name,user,port}`; `ssh-connector.ssh`; `ssh-connector.slurm.{sbatch,squeue,sacct,scancel,jobs-dir}`; `ssh-connector.limits.{time-seconds,output-bytes,file-bytes}`; and `ssh-connector.harness.{kind,image,model}` (`kind` is `none`, `opencode`, `codex` or `hermes`). Also required: `ssh-connector.work-dir`, an absolute path that may not overlap the directory and may not hold the key. The LLM upstream is agent-connector-kernel's `agent-connector-kernel.llm.upstream`.
  - Layout of the directory: `private/` is agent-connector-kernel's own directory, never mounted; it holds `ssh/ssh_config`, `ssh/known_hosts` and, with no harness, `mcp-key`. `sockets/` holds the one domain socket, and `harness/` the harness's configuration file. All of them are 0700, and the files in them 0600.
  - `ConnectorScope`: the one scope `ssh-connector`. Its key is 32 random bytes, compared in constant time. Its tools are the seven ssh tools, and its tool set is `HARNESS`.
  - `ConnectorBeans`: at start-up, writes `ssh_config` and `known_hosts` and starts one `SshRunner` actor for the host (`ssh-<alias>`). `ConnectorMcpScopes` is agent-connector-kernel's `McpScopes`.
  - `ConnectorLaunch`: `PodmanConfinement.arguments`, then the harness's own arguments (ssh-connector's command-line arguments), as a `HarnessLaunch`. The keys go only in podman's environment. `AGENT_CONNECTOR_KERNEL_LLM_CREDENTIAL` is removed from that environment, and `-t` is added after `-i` when on a terminal.
  - `SshConnectorApp`: opens the scope to the LLM proxy, writes the harness's configuration, starts the harness with `inheritIO`, and ends with its exit status. With `kind=none` it writes `private/mcp-key` and serves until stopped.
- **Tests first: 8 in 3 classes** (`ConnectorSettingsTest` 4, `ConnectorLaunchTest` 2, `ConnectorScopeTest` 2). Red against the skeleton: 8 of 8 failed (`mvn -B test -pl ssh-connector -am`). Then green.
- **Result** (`rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS):
  - counts: `plugin-api` 0, `agent-connector-kernel` 103, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 59, `ssh-tools` 28, `ssh-connector` 8; total 1018, with 0 failures;
  - against 982: +28 `ssh-tools`, +8 `ssh-connector`;
  - E2E: `McpServerCheck` and `ToolCallingCheck` ALL PASSED; `ExternalMcpCheck` and `--restored` exit 0.
  - The first `ExternalMcpCheck` of this run failed: "port 18091 busy before start". My `plugincheck.sh` instance on that port had not ended on SIGTERM. I stopped it with SIGKILL and ran `ext.sh` again alone, and both checks then exited 0. `plugincheck.sh` now waits and then sends SIGKILL.
- **Step 2 check** (`serve11.sh`, `e2e/SshConnectorCheck.java` with `e2e/SocketMcpClient.java`): `ssh-connector` with `kind=none`, host `unreachable.invalid`, a fresh dummy ed25519 key. ALL PASSED, 11 checks:
  - `private/mcp-key` is 0600; the socket's directory is 0700 and the socket 0600;
  - no scope gets 400; no key, a wrong key and another scope name with the right key get 401;
  - with the key, `initialize` answers 200 and `tools/list` lists exactly the seven ssh tools;
  - `job_status` with `1;id` is answered as an error before `ssh` starts;
  - `run_command` reaches `ssh`, which fails for the unreachable host with exit 255.
  - The log shows `MCP server offers 7 tools`, and the `ssh_config` written has every required key.

### Stage 11, step 3: the 5.14 E2E against a throw-away sshd container (2026-11-22)

- **What ran** (5.14, rootless Podman, nice 19; the jar `b61d9f2a…` copied with rsync, checksums compared; `step3.sh`):
  - `e2e/sshd/Containerfile`: `ubuntu:24.04` with `openssh-server`, one account `dummy`, and fake `sbatch`, `squeue`, `sacct` and `scancel`. They record their arguments in `/var/fake-slurm` and answer like Slurm: `Submitted batch job 4242`; squeue lists 4242 as RUNNING until scancel; sacct reports COMPLETED. The host key and `authorized_keys` are mounted at `/keys`, and the port is published on the host's 127.0.0.1 only.
  - `e2e/SshConnectorE2E.java` (with `ConnectorProcess` and `SocketMcpClient`), and `e2e/ConfinementCheck.java` in its new `ssh-connector` mode. Both run with the ssh-connector jar as the classpath.
- **`SshConnectorE2E`: ALL PASSED, 21 checks.**
  - A fresh dummy ed25519 client key and a fresh host key for each run. ssh-connector's `known_hosts` is empty before the first call; after it, the file holds `[127.0.0.1]:<port>` with the container's host key (accept-new).
  - Each tool once, as a direct MCP call with the key (ssh-connector with no harness):
    - `run_command` ran as `dummy`.
    - `-oProxyCommand=touch <local file>` reached the remote shell: bash read it as its own `-o` and listed its options, exit 2. No local file was made.
    - `submit_job` returned `job_id: 4242`. The fake `sbatch` got the script byte for byte, with the arguments `--job-name=e2e-job ssh-connector/jobs/<dir>/job.sh`.
    - `job_status` 4242 came from squeue (RUNNING), and job 77 from sacct (COMPLETED). `cancel_job` 4242 reached the fake `scancel`.
    - `list_remote` listed the job directory.
    - `upload_file` of a file with quotes, `$(id)` and `ü` to `uploaded e2e.txt` matched byte for byte.
    - `download_file` matched too. Again without `overwrite` it was refused, and `../escaped.txt` was refused with nothing written.
  - **Host key change:** a new sshd container with a new host key on the same port. The next call failed with `Host key verification failed` (with `WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!`), and `known_hosts` was unchanged.
  - **Confined harness:** ssh-connector with `kind=opencode` started OpenCode 1.19.6 confined, with a stub model.
    - The stub answered: a reply to OpenCode's title request (no tools), a `submit_job` call, a `job_status` call with the id it got, then a reply.
    - The fake `sbatch` got the stub's script byte for byte, and the later requests carried the tool results.
    - The harness printed the reply, and ssh-connector ended with the harness's exit 0.
    - Every request reached the stub through the proxy with the real (dummy) key. No container was left.
  - The dummy client key and both host keys were deleted.
- **`ConfinementCheck` in `ssh-connector` mode: ALL PASSED, 18 checks.**
  - ssh-connector itself is the host, and the podman arguments are the ones `ConnectorLaunch` builds: `podman run --rm -i --network=none --userns=keep-id:uid=1001,gid=1001 --cap-drop=all --security-opt=no-new-privileges`, with the one socket, `/work` and the configuration file, and `-e ACK_LLM_KEY -e ACK_MCP_KEY`. The marker is in ssh-connector's `private/`.
  - The original 16 all passed: the tools list (`run_command`) and the proxy through the relay; no external TCP, DNS, host loopback, host addresses or host gateway; neither marker readable (`private/` and `~/.ssh`) nor anywhere; the real key nowhere; the throw-away key where the harness expects it.
  - The two new checks passed: the dummy ssh key named in the settings cannot be read (`cat` fails), and no `BEGIN OPENSSH PRIVATE KEY` is anywhere in the container.
- **Change found by the run.** In ssh-connector with no harness, the scope is also opened to the LLM proxy when an upstream is set, and its key is written to `private/llm-key` (0600). A client the user starts can then use the proxy, and the confinement check needs it.
- **First run: 12 of 21 failed**, the 16 confinement checks passed.
  - Every ssh call got `Permission denied (publickey)`: sshd reads `authorized_keys` with the user's rights, and the `/keys` mount belongs to root inside the container. The image now copies it to `/home/dummy/.ssh/authorized_keys` at start-up, owned by `dummy`.
  - The stub counted OpenCode's title request, which has no tools, as a round. Such a request now gets the reply and does not count.
  - The second run passed everything. The first run's logs are kept as `e2e.1.log` and `cc.1.log`.
- **Clean-up on 5.14:**
  - `podman rmi localhost/ack-sshd-e2e:1 localhost/ack-opencode:1.19.6`, then `podman image prune -f` for their dangling layers;
  - `podman ps -a`: no containers; `podman images -a`: only `docker.io/library/ubuntu:24.04` (chat-01's);
  - `~/.ssh` has 36 entries, as before, and no marker;
  - no key file is left under `~/chat77-confinement/s11`.

### Stage 11, step 4: the native build of `ssh-connector` (2026-11-22, W206 Job `chat77-s11`; it does not have to pass)

- **Command:** `rm -rf target */target`, then `mvn -B package -Dnative -pl ssh-connector -am` (GraalVM CE 25+37.1 `native-image`, through `quarkus-maven-plugin`), with `build-essential` and `zlib1g-dev` installed. The tests ran first and passed: `agent-connector-kernel` 103, `ssh-tools` 28, `ssh-connector` 8.
- **First attempt: failed** in `[2/8] Performing analysis`: `Detected an instance of Random/SplittableRandom class in the image heap`, reached through `SshTools.newJobDirName` (a `static final SecureRandom`). This was ssh-tools' own code, so I fixed it: the `SecureRandom` is now made in the method. No native-image option was added.
- **Second attempt: failed** at the same step, in agent-connector-kernel's code: `An object of type 'jdk.internal.net.http.HttpClientFacade' was found in the image heap`. The object was reached through `DocSearchTool.get` (its `static final HttpClient HTTP`), via `ReferenceLinkTool.list`, `ToolRunner.run`, `McpToolEndpoint.call` and `KernelMcpServer.register`.
  - ssh-connector does not offer `search_docs` or `list_references`, but `ToolRunner.run` still reaches their code.
  - I did not change this: it is the kernel's code, which chat-ui shares, and the step did not ask for a pass. Ways to go on later:
    - make the client in `DocSearchTool` lazily, in a holder class initialized at run time;
    - or add `--initialize-at-run-time` for `DocSearchTool` in ssh-connector's `quarkus.native.additional-build-args`.
    - More such objects may come up after it.
- No executable was produced, so the native step 2 check did not run.

- [x] 12. **Wrap-up.** Done 2026-11-22; see "Review (stage 12)" at the end of this file.
  - Full `rm -rf target` and `mvn test` in a W206 Job, plus the E2E checks of stage 6 and the `ssh-connector` E2E of stage 11.
  - Diff against `main`.
  - A review section in this file.
  - Proposed doc changes for chat-48 to decide: the module table of `ProviderAndToolPlugins_260912_oo01`, and a new 040_design doc for the split.
  - No push and no deploy without asking.

## Plan change (2026-11-22): agent-connector-kernel uses Quarkus (t143:r1)

The person's decision, relayed by chat-01 and chat-48 in t143:r1: "Quarkusを使いなさい". The enforcer ban on Quarkus, CDI, MicroProfile, SmallRye and Vert.x in `agent-connector-kernel` (stage 2) is withdrawn. The hand-written MCP server and its HTTP and Unix-socket listeners give way to Quarkiverse `quarkus-mcp-server`, the library `app` already uses. Stage 9 part 3 showed why: the hand-written server left out `serverInfo.version`, which the MCP specification requires, and Codex CLI's MCP client refused the handshake.

12. **Decision 12: Quarkus in agent-connector-kernel** (t143:r1). It replaces Decisions 5 and 10 where they name `com.sun.net.httpserver` and the `java.nio` listener. The transport stays HTTP on localhost, and Podman still reaches it through a Unix domain socket.
   - `agent-connector-kernel` becomes a library of CDI beans: no `quarkus-maven-plugin` build of its own, with a Jandex index so that `app` and `ssh-connector` discover its beans (https://quarkus.io/guides/cdi-reference, "Bean discovery").
   - Its MCP server is `quarkus-mcp-server-http`, at the version `app` uses (2.0.4), and is built on the library's own extension points, with no fork and no patch:
     - tools are registered with `ToolManager.newTool` (https://docs.quarkiverse.io/quarkus-mcp-server/2.0.x/guides-implementing-tools.html, "Programmatic API");
     - one conversation sees only its own tools through a `ToolFilter` (…/2.0.x/guides-using-filters-and-checks.html), as `app`'s `ConversationToolFilter` already does;
     - the per-conversation key is checked on the Quarkus security layer, with `quarkus.http.auth.permission.<name>.paths=/mcp/*` and `policy=authenticated` (…/2.0.x/reference-security.html, "Securing HTTP Endpoints"). The check is a custom `HttpAuthenticationMechanism` (https://quarkus.io/guides/security-customization). A missing, unknown or wrong key gets the same challenge, so a wrong key and an unknown name get the same 401 with the same body. Annotation-based security is not used, since it answers with an MCP error -32005 instead of an HTTP status (reference-security).
   - There is one MCP server. `app`'s `ChatUiMcpServer` and `ConversationToolFilter` move into `agent-connector-kernel` as that server. `app` keeps only its `ToolExtension` for the ConversationSquad tools (Decisions 2).
   - Concurrency stays with POJO-actor: the scopes and keys stay in actors (`ToolScopes`, `ProxyKeys`), called from the beans.

13. **Decision 13: one key setting per deployment** (chat-48, 2026-11-22, t144:r1). It is `agent-connector-kernel.mcp.require-key`, default `true`, in the kernel's `META-INF/microprofile-config.properties`.
   - With `true`, every scope needs its key. A request that names no scope gets 400. An unknown scope, a missing key and a wrong key get the same 401 with the same (empty) body.
   - `app` alone sets it to `false`. That keeps chat-ui's behaviour before the split exactly: no key, and a request goes on as the conversation it names in `X-Chat-Ui-Conversation` (`agent-connector-kernel.mcp.scope-header`), or with no tools when it names none.
   - It is not a per-scope option, because an unkeyed scope inside ssh-connector would be a hole.
   - Decisions item 3 stands: chat-ui's behaviour does not change during the split.
   - The proxy shares the one Unix socket with the MCP server, at `/llm/<scope>/v1` (t144:r1, question 3). The LLM proxy's component (question 1) waits for chat-01's answer, and step 4 does not start before it.

### Doc answers (asked in t143:r1, points 4 and 5)

- **Point 4: can the Quarkus HTTP server listen on a Unix domain socket? Yes.** The Vert.x reference guide, section "Use a Unix Domain Socket" (https://quarkus.io/guides/vertx-reference#use-a-unix-domain-socket), says:
  - "Unix Domain Sockets are supported in both JVM and native modes. Unix domain sockets require a native transport dependency."
  - The configuration is `quarkus.http.domain-socket=<path>` with `quarkus.http.domain-socket-enabled=true`, and `quarkus.http.host-enabled=false` turns the TCP socket off.
  - The keys are in the configuration reference (https://quarkus.io/guides/all-config): `quarkus.http.domain-socket`, default `/var/run/io.quarkus.app.socket`, and `quarkus.http.domain-socket-enabled`, default `false`.
  - The HTTP reference guide (https://quarkus.io/guides/http-reference) fires `io.quarkus.vertx.http.DomainSocketServerStart` when that server starts, and its `HttpServerOptionsCustomizer` also covers the Domain Socket server.

  What follows for the plan:
  - The HTTP server has one domain socket, not one per endpoint. The MCP server (`/mcp`) and the LLM proxy therefore share one socket. The proxy moves to `/llm/<scope>/v1`, so a scope can never be named `mcp`.
  - The relay keeps its two ports inside the container (18765 and 18766), but both now go to the one socket, so the harness configurations change only the proxy's base path.
  - The guide asks for "the right permissions to write to the socket". The 0600 socket in a 0700 directory (Decisions 10) is checked with a test after start-up, before anything else relies on it.
- **Point 5: can the LLM proxy be written with Quarkus components? Partly. Quarkus has no reverse-proxy component of its own.**
  - The `quarkus.http.proxy.*` keys in the configuration reference are for running behind a reverse proxy (forwarded headers, trusted proxies). They do not make Quarkus one.
  - The component that fits is **Vert.x HTTP Proxy** (`io.vertx:vertx-http-proxy`), "a reverse proxy based on Vert.x" (https://vertx.io/docs/4.5.35/vertx-http-proxy/java/; the current page is https://vertx.io/docs/vertx-http-proxy/java/).
    - Quarkus 3.33.4.2's platform BOM manages it at 4.5.35, the same version as the `vertx-core` Quarkus runs on.
    - A `ProxyInterceptor` can rewrite the proxied request's headers, which is where the throw-away key is checked and the real credential put in.
    - It is mounted on Quarkus's own router with `@Observes Router` (https://quarkus.io/guides/reactive-routes, "Using the Vert.x Web Router") and uses the managed `Vertx` instance (https://quarkus.io/guides/vertx-reference).
    - The Vert.x page marks the module "Tech Preview status, this means the API can change between versions".
  - The alternative uses supported Quarkus components only: a route on the same router, with the managed Vert.x `HttpClient` or WebClient streaming the answer back. The forwarding code (headers, streaming) stays hand-written there, as in `LlmProxy` today.
  - The decisions (`LlmProxyRequests`: scope, key, 400 and 401) and the keys actor (`ProxyKeys`) stay plain objects under either choice.
  - **The choice is for chat-48 before the proxy is rewritten.** chat-77 recommends Vert.x HTTP Proxy, because the platform BOM manages it and it removes the hand-written forwarding.

### Stages and commits affected

| Stage | Commit | What happens |
|---|---|---|
| 0–1 (plan, baseline) | `e39e158`, `b17e4a0`, `e8875db` | kept |
| 2 (empty module, enforcer rule) | `f2568c5` | rule replaced: the ban on `io.quarkus*`, `io.quarkiverse*`, `jakarta.*`, `javax.inject`, `javax.enterprise`, `org.eclipse.microprofile*`, `io.smallrye*` and `io.vertx*` is removed |
| 3 (plain tools, `PluginRegistry`) | `9560b27` | kept |
| 4 (`ToolCatalog` cut) | `a9c1ae2` | kept |
| 5 (`ConversationToolRunner` cut) | `5fbcbc8` | kept |
| 6 (MCP endpoint and localhost server) | `9c4b530`, plus the 401 change in `83d52a8` | `McpToolEndpoint` and `ToolScopes` kept. `LocalMcpServer` and `McpRequestHandler` deleted, and their behaviour moves to the library server. `app`'s `ChatUiMcpServer` and `ConversationToolFilter` move into the kernel as that server. Their tests are rewritten for the library server, red first: key per conversation, the same 401 for an unknown name and a wrong key, 400 for a request that names no conversation, and only that conversation's tools visible. |
| 7 (`HarnessLaunch`) | `97bfa74` | kept |
| 8 (LLM proxy, Claude Code confinement) | `a38a780` | `ProxyKeys`, `LlmProxyRequests` and `ClaudeConfinement` kept. `LlmProxy`'s HTTP server is replaced, as chosen under point 5. |
| 10 (native-image probe) | `83d52a8` | `e2e/KernelNativeProbe.java` deleted. The native image is built the Quarkus way for `ssh-connector` (`quarkus-maven-plugin`, `-Dnative`) in a W206 Job. |
| 9 part 1 (Unix-socket listeners, launcher builders) | `0a0c28f` | `UnixSocketHttpServer` deleted, along with the Unix-socket constructors of `LocalMcpServer` and `LlmProxy`. `PodmanConfinement`, `CodexHarness`, `OpenCodeHarness`, `HermesHarness`, `ack-relay` and the Containerfiles kept, changed only for the one socket and the `/llm/<scope>/v1` base path. |
| 9 part 2 (checks on 5.14) | `4e150e8` | kept: the `keep-id` fix, the Containerfiles, the proxy's 401. `e2e/ConfinementCheck.java` kept and pointed at the Quarkus server. The 16 checks are run again once the server is replaced. |
| 9 part 3 (stub and real-model runs) | not committed; local branch `wip/s9p3` (`688ac1e`) | The harness network-off settings (Codex, OpenCode, Hermes, and the OpenCode image with `@opencode-ai/plugin` and `OPENCODE_DISABLE_MODELS_FETCH`) and their tests are kept and brought over. `e2e/HarnessStubRun.java` and `e2e/traced/Containerfile` are kept. The `serverInfo.version` fix and its test are dropped, because the library writes `serverInfo`. |

Files on `wip/s9p3`:
- `agent-connector-kernel/containers/opencode/Containerfile`
- `confine/CodexHarness.java`, `confine/HermesHarness.java`, `confine/OpenCodeHarness.java`
- `mcp/McpRequestHandler.java`
- `HarnessConfigTest.java`, `McpRequestHandlerTest.java`
- `e2e/HarnessStubRun.java`, `e2e/traced/Containerfile`

### New order of work (each step tests first, red then green on W206; a report after each)

1. **Enforcer rule.** Remove the ban. `agent-connector-kernel` gets `quarkus-arc`, `quarkus-vertx-http`, `quarkus-mcp-server-http` 2.0.4 and the native transport (Linux epoll) that Unix domain sockets need, plus a Jandex index. The build and the 934 + 41 tests stay green.
   - **Done (2026-11-22, W206 Job `chat77-split-k1`).**
     - Change:
       - The enforcer execution is removed.
       - `agent-connector-kernel` depends on `quarkus-arc`, `quarkus-vertx-http` and `quarkus-mcp-server-http`. The MCP library's version, 2.0.4, now lives in the parent's dependency management, so `app` and the kernel share it.
       - `jandex-maven-plugin` 3.2.7 writes `META-INF/jandex.idx`.
       - The native transport waits for step 3, where the socket is used.
       - Only `app` depends on the kernel, so no plain-Java plugin pulls Quarkus in.
     - Test first: `BeanLibraryTest.theBuildWritesAJandexIndex`. It failed on W206 without the plugin (`BeanLibraryTest.java:22`, index not on the class path), and passes with it.
     - Result (`rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS):
       - counts: `plugin-api` 0, `agent-connector-kernel` 95, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61;
       - total 976 = 934 + 42 new (the 41 of stages 5–9 and `BeanLibraryTest`), with 0 failures, errors and skipped;
       - E2E: `McpServerCheck` ALL PASSED, `ToolCallingCheck` ALL PASSED, `ExternalMcpCheck` and `--restored` exit 0.
2. **One MCP server, from the library.**
   - Tests first: key per conversation; the same 401 and body for an unknown name and a wrong key; 400 for a request that names no conversation; only the conversation's tools in `tools/list`, and a `tools/call` outside them refused; `initialize` answers with a complete `serverInfo`.
   - Then the kernel's beans: `ToolManager` registration, `ToolFilter`, `HttpAuthenticationMechanism` and the `/mcp/*` permission, with `app`'s `ChatUiMcpServer` and `ConversationToolFilter` moved in.
   - `LocalMcpServer` and `McpRequestHandler` deleted, and the `McpServerCheck`, `ToolCallingCheck` and `ExternalMcpCheck` E2E checks pass against `app`.
   - **Done (2026-11-22, W206 Job `chat77-split-k1`).** One MCP server, on the library.
     - In `agent-connector-kernel`, package `com.scivicslab.chatui.mcp`:
       - `McpScopes`: what an application provides, as one CDI bean: its tools, the runner of a scope, and whether a key opens a scope.
       - `McpAccess`: the plain decision for both values of the setting (Decision 13).
       - `McpKeyMechanism`: a Quarkus `HttpAuthenticationMechanism` for the MCP path only.
       - `McpScopeToolFilter`: the library's `ToolFilter`.
       - `KernelMcpServer`: `ToolManager.newTool` registration, and calls on the scope's runner.
       - `META-INF/microprofile-config.properties`: the defaults and the permission `agent-connector-kernel-mcp` (`/mcp`, `/mcp/*`, `authenticated`).
       - The kernel also depends on `quarkus-security`.
     - In `app`: `ChatUiMcpScopes` (the conversations, keyless) replaces `ChatUiMcpServer` and `ConversationToolFilter`, which are deleted. `application.properties` sets `require-key=false` and `scope-header=X-Chat-Ui-Conversation`.
     - Deleted: `LocalMcpServer`, `McpRequestHandler`, their tests (`LocalMcpServerTest`, `LocalMcpServerUnixSocketTest`, `McpRequestHandlerTest`) and `e2e/KernelNativeProbe.java`. The `serverInfo.version` fix on `wip/s9p3` is not carried; the library writes `serverInfo`, and `McpKeyCheck` shows it complete.
     - `e2e/ConfinementCheck.java` still names `LocalMcpServer`. It is a program outside the build, and step 5 points it at the Quarkus server.
     - Found on the way: a refusal must leave the request with no identity rather than fail authentication. When authentication fails, quarkus-mcp-server answers 401 with an MCP error naming the exception (`{"error":{"code":-32005,"message":"io.quarkus.security.AuthenticationFailedException"}}`), so a missing scope could not get 400. With no identity, the permission refuses through `McpKeyMechanism.getChallenge`.
     - Tests first. The red run on W206 failed to compile (`cannot find symbol` / `package McpAccess does not exist` in `McpAccessTest`). New tests:
       - `McpAccessTest`, 4: key required, the right key goes on; key required, unknown scope = wrong key = no key = non-Bearer key = empty key, all 401; key required, no scope gives 400; no key required, the request goes on as its scope without a key.
       - `McpToolEndpointTest`, 1: a `FULL` and a `HARNESS` scope each see only their share, and an unknown scope sees none; brought over from `McpRequestHandlerTest`. My first version of it had `HARNESS` backwards and failed; the code was right, and the test was fixed.
     - New E2E `e2e/McpKeyCheck.java`, run against two throw-away `app` instances (`mcpkey.sh`):
       - `key-required` (`-Dagent-connector-kernel.mcp.require-key=true`): no conversation gives 400; a known conversation with no key, with a wrong key, and an unknown conversation each give 401 with the same body; `/q/health` is not refused. ALL PASSED.
       - `no-key` (as shipped): `initialize` gives 200 with `serverInfo` carrying `name` and `version`. ALL PASSED.
     - Result (`rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS):
       - counts: `plugin-api` 0, `agent-connector-kernel` 91, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61; total 972, with 0 failures, errors and skipped;
       - against 976 after step 1: minus 9 deleted (`LocalMcpServerTest` 1, `LocalMcpServerUnixSocketTest` 3, `McpRequestHandlerTest` 5), plus 5 new.
     - E2E on this jar:
       - `McpServerCheck` ALL PASSED; `ExternalMcpCheck` and `--restored` exit 0.
       - `ToolCallingCheck` failed once in that run: the conversation's history had an extra user and assistant pair (`[system, user, assistant, user, assistant, tool, assistant, tool, assistant]`); its other checks passed. It passed in two reruns on the same jar (`e2eonly.sh`, each in a fresh home), both with `[system, user, assistant, tool, assistant, tool, assistant]`.
       - Cause, found in step 3: my Job script `runstage.sh` reused `/work/<label>-e2e` as the instance's home. A second run under the same label restored the earlier run's conversations. chat-ui's log says `Restored 1 turn(s) into project1/chat-02 from I/O log session 3`, and that restored turn is the extra pair. The script now starts each run from an empty home. The code was not at fault.
3. **Unix domain socket.**
   - `quarkus.http.domain-socket` set by the launcher's process, and `UnixSocketHttpServer` deleted.
   - A test checks the socket's 0600 and the directory's 0700.
   - The relay maps both container ports to the one socket, and the harness configs take `/llm/<scope>/v1`. Brought over from `wip/s9p3`: the network-off settings and their tests.
   - **Done (2026-11-22, W206 Job `chat77-split-k1`).** One socket, owner-only.
     - Change:
       - `PodmanConfinement` mounts one socket, `agent-connector-kernel.sock`, at `/run/agent-connector-kernel/agent-connector-kernel.sock`, and the proxy's base URL is `http://127.0.0.1:18766/llm/<scope>/v1`.
       - `ack-relay` maps both container ports, 18765 and 18766, to that socket.
       - In the kernel, `SocketPermissions` (plain) and `DomainSocketGuard` (bean):
         - at start-up the guard refuses a socket directory that others can read or enter, and refuses a domain socket without `quarkus.vertx.prefer-native-transport=true`;
         - on `DomainSocketServerStart` it makes the socket 0600.
       - The kernel depends on `netty-transport-native-epoll` (`linux-x86_64`).
       - Deleted: `UnixSocketHttpServer`, its test, `UnixSocketClient`, `LlmProxyUnixSocketTest`, and `LlmProxy`'s Unix-socket constructor. `LlmProxy`'s TCP form stays until step 4.
       - Brought over from `wip/s9p3`: the network-off settings of `CodexHarness`, `OpenCodeHarness` and `HermesHarness`, their tests, and the OpenCode Containerfile (`@opencode-ai/plugin` installed, `OPENCODE_DISABLE_MODELS_FETCH=1`).
     - Found on the way: the domain socket needs `quarkus.vertx.prefer-native-transport=true` as well as the epoll dependency.
       - Without it, start-up failed with "Unable to bind to Unix domain socket. Consider adding the 'io.netty:netty-transport-native-epoll' dependency", though the dependency was in the jar.
       - The launcher sets it together with `quarkus.http.domain-socket-enabled`, and `app` as shipped does not, since it has no socket.
     - Not yet consistent, until step 4: the launcher and the harness configs point at `/llm/<scope>/v1`, but the proxy at that path arrives with step 4. `e2e/ConfinementCheck.java` still names the deleted `LocalMcpServer` and the two sockets, and step 5 points it at the Quarkus server.
     - Tests first. The red run failed to compile on the new symbols (`PodmanConfinement.SOCKET`, `SocketPermissions`). A second red tree without those two symbols then showed the assertions red:
       - `PodmanConfinementTest`: the mount list, and `expected: <http://127.0.0.1:18766/llm/s1/v1> but was: <http://127.0.0.1:18766/s1/v1>`;
       - `HarnessConfigTest`: 5 failures and 1 error (the base URLs and the network-off settings).
     - New tests:
       - `SocketPermissionsTest`, 3: a missing directory is created 0700; a 0755 directory is refused and left unchanged; a bound socket becomes 0600.
       - `PodmanConfinementTest.theMcpServerAndTheProxyAreReachedThroughTheOneSocket`, 1.
       - `HarnessConfigTest`, 3 from `wip/s9p3`.
     - New E2E `e2e/DomainSocketCheck.java`, against a throw-away `app` with `-Dquarkus.http.domain-socket=… -Dquarkus.http.domain-socket-enabled=true -Dquarkus.vertx.prefer-native-transport=true`, in a 0700 directory (`sock.sh`): the directory is 0700; the socket is 0600; `initialize` through the socket answers 200; the answer carries a complete `serverInfo`. ALL PASSED.
     - Result (`rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS):
       - counts: `plugin-api` 0, `agent-connector-kernel` 95, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61; total 976, with 0 failures, errors and skipped;
       - against 972 after step 2: minus 3 deleted (`UnixSocketHttpServerTest` 2, `LlmProxyUnixSocketTest` 1), plus 7 new.
     - E2E: `McpServerCheck` ALL PASSED; `ExternalMcpCheck` and `--restored` exit 0; `ToolCallingCheck` ALL PASSED in a fresh home (`e2eonly.sh`). It had failed in the run that reused the home (see step 2).
4. **LLM proxy.** Replaced by step 4a below (the person's decision, t147:r1): Vert.x HTTP Proxy, with the part shared with the GPU broker in a library of its own, `llm-relay`. `LlmProxyTest` and `LlmProxyUnixSocketTest` are rewritten against it, red first. `LlmProxyRequestsTest` is unchanged.
5. **Confinement checks again on 5.14** (part 2's 16 checks, then part 3's stub runs, traced and plain), with the network-off settings.
6. **Real-model runs** (t135:r1). Done (see "Step 6" below): OpenCode with GLM-5.3, OpenCode with Qwen3.8 and Hermes Agent with GLM-5.3 passed. Codex CLI with GLM-5.3 failed at the model server, and Codex now waits for an OpenAI key (t163:r1).
   - Codex's config reference (https://learn.chatgpt.com/codex/config-file/config-reference) allows a custom provider, `model_providers.<id>.base_url`, but "responses is the only supported value" of `wire_api`. So the model server must serve the Responses API, and Codex CLI with GLM-5.3 runs only if 5.16 answers `/v1/responses`.
   - Claude Code waits for an Anthropic key, and Codex CLI for an OpenAI key.
7. **`ssh-connector` as a Quarkus application** (stage 11, after chat-48's spec): its own module with `quarkus-maven-plugin`, the native image built with `-Dnative` in a W206 Job (GraalVM CE).
8. **Wrap-up** (stage 12).

## Step 4a: design of `llm-relay`, the shared streaming relay (t147:r1)

The person's decision (2026-11-22, via chat-01 and chat-48):
- the LLM proxy uses Vert.x HTTP Proxy (`io.vertx:vertx-http-proxy`);
- it is built on existing libraries and made robust;
- the part it shares with the GPU broker becomes one shared library, so the same job is not written twice.

This replaces step 4 of the order above. Nothing below is built before chat-48's go-ahead.

### What the broker's relay does today (read before the design)

Source: `~/works/quarkus-gpu-broker` at `1a9033c`, Quarkus 3.28.3. The relay is `llm/HttpAiServiceClient.java`, behind `llm/AiServiceClient.java`, and its one caller is `actor/AiServiceEndpointWorker.java`. The behaviours the library must keep:

| # | Behaviour | Where |
|---|---|---|
| B1 | POSTs the job's request bytes verbatim to `http://<address><path>`, with the client's `Content-Type` when there is one. The broker never parses the body. | `HttpAiServiceClient.java:38-43`; `model/RequestBody.java:3-8` |
| B2 | HTTP/1.1 is forced. The JDK client's h2c upgrade corrupts multipart/form-data bodies sent to uvicorn/FastAPI backends (YomiToku, Marker, embedding). | `HttpAiServiceClient.java:29-34`; test `HttpAiServiceClientTest.underlyingClient_isPinnedToHttp11` |
| B3 | A 5xx raises `AiServiceCallException` before `start`, so the job can be handed to another endpoint. | `HttpAiServiceClient.java:48-51`; `model/ResponseSink.java:13-17` |
| B4 | A connection failure (`IOException`) raises the same exception. | `HttpAiServiceClient.java:59-60` |
| B5 | On that exception the worker requeues the job onto another endpoint through `JobQueue.submit`, up to `MAX_ATTEMPTS = 3`, then fails the sink with "gave up after 3 attempts". | `AiServiceEndpointWorker.java:30, 78-81, 121-133` |
| B6 | Any status below 500 is relayed with its own status, so a 4xx reaches the client as that refusal, not as a 200 with an error body. | `HttpAiServiceClient.java:52-54`; `ResponseSink.java:60-67`; test `HttpAiServiceClientTest.aRefusalOfTheServer_isRelayedWithItsStatus` |
| B7 | `start(contentType, status)` is called once, before any `emit`. The content type is the upstream's, defaulting to `application/octet-stream`. | `HttpAiServiceClient.java:52-54`; `ResponseSink.java:10-12` |
| B8 | The body is relayed as it arrives, in chunks of at most 8 KB, each a fresh copy. | `HttpAiServiceClient.java:27, 67-71` |
| B9 | `stopRequested()` is asked after every `emit`. When it is true, the relay closes the upstream body, which ends the generation, and the job still counts as complete. | `HttpAiServiceClient.java:72-77`; `ResponseSink.java:72-83`; `RepetitionStoppingResponseSink.java:97`; `RunawayGenerationLimits_260915_oo01` |
| B10 | `complete()` is called after the body ends. | `HttpAiServiceClient.java:58` |
| B11 | An interrupt raises the exception and keeps the thread's interrupt flag. | `HttpAiServiceClient.java:61-64` |
| B12 | The call blocks the worker actor's own virtual thread until the response is done, which gives one job at a time per endpoint. | `AiServiceClient.java:10-13`; `AiServiceEndpointWorker.java:78` |
| B13 | `servedBy(address)` comes before the call, from the worker, not the relay. It stays in the worker. | `AiServiceEndpointWorker.java:77` |
| B14 | No request or idle timeout is set: the JDK client's default is none. | `HttpAiServiceClient.java:32-34, 38-40` |

Two findings, for chat-48 to decide on:
- **F1: a failure after `start` is also requeued.** An `IOException` while the body is being read is caught at `HttpAiServiceClient.java:59-60`, after `start` at line 52. The worker then requeues it (`AiServiceEndpointWorker.java:79-81`), so the next attempt calls `start` again on a sink that has already emitted. `ResponseSink`'s contract (lines 10-12) allows `fail` at any point, but not a second `start`.
  - The library tells the two cases apart: before the first byte means another endpoint may try; after the first byte means the sink is failed.
  - Keeping today's behaviour exactly would mean keeping this bug. My recommendation is to fix it, with a test, in the broker step.
- **F2: no timeout (B14).** The library keeps "no timeout" as its default, so the broker's behaviour is unchanged. It makes a connect timeout and an idle timeout settable, and the broker step does not set them unless chat-48 asks.

### The kernel's proxy (what it keeps)

The kernel's proxy keeps `LlmProxyRequests` (scope, key and 400/401 decisions) and `ProxyKeys` (throw-away keys in a POJO-actor actor). It needs:
- both request shapes: `/v1/chat/completions` (OpenAI-compatible, `Authorization: Bearer`) and `/v1/messages` (Anthropic, `x-api-key`, with `anthropic-version` passed through);
- the real credential put in place of the throw-away key;
- Server-Sent Events passed through as they arrive;
- the proxy at `/llm/<scope>/v1` on the Quarkus HTTP server, so it is reached through the domain socket (Decision 13).

### Why one design does not cover both directly

The two consumers enter the relay at different points:
- **The kernel** has a live incoming request: the harness's HTTP request on the Quarkus router. That is the case Vert.x HTTP Proxy is made for (`HttpProxy.reverseProxy(client)` as a request handler, with a `ProxyInterceptor` on the proxied request).
- **The broker** has no live request at the moment it relays. Its REST resource reads the client's body into bytes (`RequestBody`), queues a `Job`, and a worker sends it later, perhaps twice or three times (B5).
  - Vert.x HTTP Proxy proxies an `HttpServerRequest` and streams its body once. It cannot hold a request in a priority queue, and it cannot send the same body again to a second endpoint.

So the shared library has two layers, and the part they share is the streaming upstream call.

### The library: `llm-relay`

- **`llm-relay-core`**, the upstream call, on the Vert.x `HttpClient` (no Quarkus; the caller passes its `Vertx`):
  - `Upstream`: an address and a path, plus the client options: HTTP/1.1 always (B2), keep-alive, an optional connect timeout and an optional idle timeout, both off by default (B14, F2).
  - `RelayCall.send(Upstream, RequestBody, RelaySink)` returns a `Future<Outcome>`. A blocking `sendAndWait`, for a caller on a virtual thread, keeps the broker's B12 and B11.
  - `RelaySink` mirrors the broker's `ResponseSink`: `start(contentType, status)`, `emit(Buffer)`, `stopRequested()`, `complete()`. The broker adapts its `ResponseSink` to it in a few lines.
  - The library takes over B1, B3, B4, B6, B7, B8 (each `emit` is one Vert.x buffer as it arrives; no reading until the end), B9 (the upstream connection is reset when `stopRequested`, and the outcome is `COMPLETED`) and B10.
  - **Outcomes** (`Outcome`): `COMPLETED`; `RETRYABLE` (connection failure or 5xx, before `start`, B3 and B4); `FAILED_AFTER_START` (F1).
    - The caller decides what to do: the broker's worker requeues on `RETRYABLE`, keeping B5 and its actor, and fails the sink on `FAILED_AFTER_START`.
    - Endpoint choice and the attempt count stay in the broker's `JobQueue` and worker, which are POJO-actor actors. The library holds no queue and no actor.
- **`llm-relay-proxy`**, a reverse proxy for a live request, on `vertx-http-proxy`, built on `llm-relay-core`'s client options:
  - `RelayProxy`: mounts `HttpProxy.reverseProxy(client)` for a path prefix on a Vert.x `Router`. The client is made from `Upstream`'s options (HTTP/1.1, timeouts).
  - **Interceptors** (`RelayInterceptor`, on `ProxyInterceptor`): each one can refuse a request with a status and body, rewrite its path and headers, or choose its origin. The proxy runs them in order before the request goes upstream.
    - **The kernel's:** a `ScopeKeyInterceptor` that calls `LlmProxyRequests.decide`. A refusal is 400 or 401. On success the `/llm/<scope>` prefix is stripped, the throw-away key is replaced by the real credential (`Authorization` or `x-api-key`), and hop-by-hop headers are dropped.
    - **An origin-choosing interceptor** with retry before the first byte, which Vert.x HTTP Proxy does not do by itself, is needed by no consumer at present. The broker keeps its queue (see above), so the proxy module does not have to have it.
  - Streaming: Vert.x HTTP Proxy pipes the origin's response body to the client. `llm-relay-proxy`'s first tests show that SSE chunks arrive one by one, in order, and are not held until the end.
- **Request shapes:** both layers pass bytes through and parse no body. The shape matters only for which header carries the credential, which is `LlmProxyRequests`' business, and for the tests. They use both an OpenAI stream (`data:` lines, then `data: [DONE]`) and an Anthropic stream (`event:` and `data:` lines).

### Placement: its own Maven project, `~/works/llm-relay`

- It is a multi-module project: parent `com.scivicslab:llm-relay-parent`, with modules `llm-relay-core` and `llm-relay-proxy`.
- It has its own version, starting at `0.1.0-SNAPSHOT`, and is installed with `mvn install`.
- A module inside quarkus-chat-ui is not cleaner:
  - the broker would then depend on chat-ui's repository, version and release cycle for the one part it shares;
  - chat-ui's modules all build against chat-ui's Quarkus BOM (3.33.4.2), while the broker runs 3.28.3.
- It is not in the POJO-actor, Turing-workflow, Turing-workflow-plugins family, whose versions move together, so it keeps a version of its own.
- Its dependencies are plain Vert.x (`vertx-core`, `vertx-web` for `Router`, `vertx-http-proxy`), with no Quarkus. Tests use JUnit 5 and a Vert.x stub upstream only, with dummy keys.

### Staying compatible with both Quarkus versions

- Quarkus 3.28.3 (the broker) pins `vertx-core` and `vertx-http-proxy` at 4.5.21. Quarkus 3.33.4.2 (the kernel) pins both at 4.5.35. Both are read from the platform BOM POMs in `~/.m2`.
- `llm-relay` compiles against the lower one, 4.5.21, and declares the Vert.x artifacts `provided`. Each consumer's own Quarkus BOM then decides the runtime version, and no second Vert.x reaches either application.
- It uses only API that is in both (Vert.x 4.5 keeps its API within the 4.5 line).
- The library's tests run twice in the W206 Job, with `-Dvertx.version=4.5.21` and with `-Dvertx.version=4.5.35`.
- A Quarkus upgrade to Vert.x 5 would be a new major version of `llm-relay`.

### New order of work, after chat-48's go-ahead

1. **`llm-relay` and its tests** (a new local git repository, `~/works/llm-relay`; commit, no push). Tests first, red then green, against both Vert.x versions:
   - `llm-relay-core`:
     - HTTP/1.1 always (B2);
     - the body and `Content-Type` are sent verbatim (B1);
     - a 5xx and a refused connection give `RETRYABLE` with no `start` (B3, B4);
     - a 4xx is relayed with its status (B6);
     - `start` comes once, before `emit` (B7);
     - several chunks arrive in order, the first before the upstream has finished (B8);
     - `stopRequested` resets the upstream and completes (B9);
     - a failure after `start` gives `FAILED_AFTER_START` (F1);
     - `sendAndWait` keeps the interrupt flag (B11).
   - `llm-relay-proxy`:
     - an interceptor's refusal reaches the client with its status and body;
     - headers and the path are rewritten;
     - SSE chunks pass through unbroken, in order and unbuffered, in both shapes;
     - an upstream error before the first byte reaches the client as 502.
2. **The kernel's LLM proxy on `llm-relay-proxy`** (on the Quarkus router through `@Observes Router`, at `/llm/<scope>/v1`). `LlmProxy`'s `com.sun.net.httpserver` and its hand-written forwarding are deleted. Tests first, with dummy keys and a stub upstream only:
   - no key, wrong key and unknown scope get the same 401;
   - no scope gets 400;
   - the right key is forwarded with the real credential;
   - streaming passes through unbroken (several SSE chunks, in order, not held to the end);
   - an upstream error before the first byte is answered.
   - An E2E check runs it through the domain socket.
3. **The GPU broker on `llm-relay-core`**, on a new local branch of `~/works/quarkus-gpu-broker`:
   - `HttpAiServiceClient`'s body is replaced by a `RelayCall` with the `ResponseSink` adapter, and `AiServiceClient` and the test doubles stay.
   - Every behaviour B1–B13 keeps a test, the existing ones included. F1 is fixed if chat-48 agrees.
   - Never deployed or restarted, and the running broker at 192.168.5.21:30805 and its config are not touched. Deploying waits for the person's approval.
4. **Back to the plan:** step 5 (the 5.14 confinement checks and the stub runs), then step 6 (the real-model runs).

### Answers (chat-48, 2026-11-22, t149:r1)

1. **F1: fix it**, with a test in the broker step. A failure before the first byte gives `RETRYABLE` and is requeued. A failure after it gives `FAILED_AFTER_START`, and the sink is failed. The broker branch's commit message and plan note the change.
2. **The broker's path: `llm-relay-core` alone**, which matches the person's wording: vertx-http-proxy for the reverse proxy, and the Vert.x HTTP client where a plain upstream call is needed.
3. **F2: "no timeout" stays the default**, and can be set. The broker step does not set one.
4. Watch the `provided` scope: in each consumer, exactly one `vertx-core` must end up in the built application, and its version is reported.

### Step 4a.1: `llm-relay` (done 2026-11-22)

- **Repository:** a new local git repository at `~/works/llm-relay`, commit `1f00ec1`.
  - Author `scivicslab <devteam@scivicslab.com>`, the same as `quarkus-gpu-broker`.
  - No remote, nothing pushed.
  - Parent `com.scivicslab:llm-relay-parent` 0.1.0-SNAPSHOT, with modules `llm-relay-core` and `llm-relay-proxy`, Java 25.
  - Installed into the Job's local repository with `mvn install`.
- **`llm-relay-core`:**
  - `RelayCall`, on the Vert.x `HttpClient`, with `send` (a `Future<Result>`) and `sendAndWait`.
  - `RelayOptions`: HTTP/1.1 with no h2c upgrade, keep-alive, chunks of at most 8 KB, and connect and idle timeouts that are 0 (none) unless set.
  - `UpstreamRequest`; `RelaySink` (`start`, `emit`, `stopRequested`, `complete`); outcomes `COMPLETED`, `RETRYABLE` and `FAILED_AFTER_START`.
- **`llm-relay-proxy`:**
  - `RelayProxy`: vertx-http-proxy's `HttpProxy.reverseProxy` with a `ProxyInterceptor` that runs the `RelayInterceptor`s in order. It is mounted on a vertx-web `Router` with vertx-web-proxy's `ProxyHandler`, which both Quarkus BOMs manage (4.5.21 and 4.5.35).
  - `RelayInterceptor`: refuses with a `Refusal(status, contentType, body)`, or rewrites the `ProxyRequest`'s URI and headers.
- **Dependencies:** Vert.x is `provided`, compiled against 4.5.21.
- **Tests first.** The red run (`relay.sh r1red red`) failed to compile (`cannot find symbol` for `RelayCall`, `RelayOptions`, `UpstreamRequest`, `RelaySink`).
- **Green, W206 Job `chat77-relay-1`:** `mvn -B test -Dvertx.version=4.5.35`, then `mvn -B install -Dvertx.version=4.5.21`. Both BUILD SUCCESS, and both give `RelayCallTest` 10 and `RelayProxyTest` 5: 15 tests, 0 failures.
  - `RelayCallTest`:
    - body and Content-Type verbatim over HTTP/1.1 with no `Upgrade` (B1, B2);
    - a 503 is `RETRYABLE` with no sink call (B3);
    - a refused connection is `RETRYABLE` with its cause (B4);
    - a 400 reaches the sink with its status (B6);
    - `start` once and first, `complete` last, `application/octet-stream` by default (B7, B10);
    - the first chunk reaches the sink while the upstream still waits, then the rest in order, no chunk over 8 KB (B8);
    - `stopRequested` closes the upstream connection and completes (B9);
    - a connection closed after the start is `FAILED_AFTER_START` with no `complete` (F1);
    - `sendAndWait` on a virtual thread, interrupted, is `RETRYABLE` and keeps the flag (B11, B12);
    - no timeout by default, and a 1 s idle timeout before the start is `RETRYABLE` (B14, F2).
  - `RelayProxyTest`:
    - a refusal reaches the client with its status and body, and the upstream is not called;
    - the URI and the `Authorization` header are rewritten before the request goes up;
    - an OpenAI stream passes through unbroken: the first `data:` line arrives while the upstream still waits, then the rest in order;
    - the same for an Anthropic stream with `event:` and `data:` lines;
    - an upstream that is down is answered with 502.
- **A library change made for step 2** (`6abdef8`): an interceptor answers through a `CompletionStage<Refusal>`.
  - The kernel's rule asks the `ProxyKeys` actor, and blocking the event loop on `ask().join()` would stall it.
  - `RelayProxy` waits without blocking, goes on in the request's own Vert.x context, and still runs the interceptors in order.
  - Test first: the red run failed to compile on the old type. A new test has an interceptor answer 200 ms later from another thread, followed by a second one in order.
  - 16 tests pass on Vert.x 4.5.21 and 4.5.35.

### Step 4a.2: the kernel's LLM proxy on `llm-relay-proxy` (done 2026-11-22, W206 Job `chat77-relay-1`)

- **Change:**
  - `LlmProxy` is rewritten: it now builds a `RelayProxy` mounted under `/llm/` on a Vert.x `Router`, with the keys in the `ProxyKeys` actor.
  - The new `ScopeKeyInterceptor`:
    - calls `LlmProxyRequests.decide` off the event loop, since it asks the actor;
    - on a refusal answers 400 or 401 with a JSON body;
    - otherwise rewrites the request back in its Vert.x context: the path after `/llm/<scope>`, both `x-api-key` and `Authorization` removed, and the real credential set.
  - The new bean `LlmProxyRoutes` mounts it on Quarkus's router (`@Observes Router`) when `agent-connector-kernel.llm.upstream` is set. The other settings are `credential-header`, default `authorization`, and `credential`. `app` sets no upstream, so it has no proxy.
  - Deleted: `LlmProxy`'s `com.sun.net.httpserver` server and its hand-written forwarding.
- **Not yet:** an https upstream is refused for now, because TLS to the upstream is not built. The real-model servers are http. Anthropic's API, for Claude Code, waits for a key anyway.
- **Dependencies:** the kernel depends on `llm-relay-proxy` 0.1.0-SNAPSHOT (parent property `llm-relay.version`), `vertx-http-proxy` and `vertx-web-proxy`, at the versions Quarkus 3.33.4.2's BOM pins.
  - `mvn dependency:tree` shows one `vertx-core`, 4.5.35, in `agent-connector-kernel` and in `app`.
  - The uber-jar carries one `META-INF/maven/io.vertx/vertx-core/pom.properties`.
- **Tests first.** The red run failed to compile (`incompatible types: io.vertx.core.Vertx cannot be converted to ActorSystem`; `cannot find symbol: method mount(Router)`). The new `LlmProxyTest` has 5 tests, against a stub upstream with dummy keys:
  - no key, a wrong key and an unknown scope get the same 401 and body, a request that names no scope gets 400, and no refused request reaches the upstream;
  - the right key is forwarded with the real credential in both shapes (`/v1/chat/completions` with `Authorization`, `/v1/messages` with `x-api-key` and `anthropic-version`), with the path after the scope and the query kept, and the throw-away key in no upstream header;
  - a stream of four SSE chunks passes through in order, the first while the upstream still waits;
  - an unreachable upstream is answered 502, and an upstream's own 503 reaches the client with its body;
  - an https upstream is refused.
- **Found on the way:** with an empty default, `agent-connector-kernel.llm.credential` counted as missing to MicroProfile Config, and `app` did not start ("Failed to load config value of type class java.lang.String"). Every E2E check of that run failed to connect. It is now an `Optional<String>`.
- **Result** (`rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS):
  - counts: `plugin-api` 0, `agent-connector-kernel` 99, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61; total 980, with 0 failures;
  - against 976 after step 3: the old `LlmProxyTest` (1) is replaced by the new one (5).
- **E2E:** `McpServerCheck` and `ToolCallingCheck` ALL PASSED; `ExternalMcpCheck` and `--restored` exit 0.
- **New E2E `e2e/LlmProxyCheck.java`**, run against a throw-away `app` with an upstream and the domain socket (`llmproxy.sh`). ALL PASSED:
  - over TCP, a wrong key gets 401 with a JSON body;
  - no scope gets 400;
  - through the socket, the same request gets the same 401 and body;
  - `/q/health` is not answered by the proxy.

### Step 4a.3: the GPU broker on `llm-relay-core` (done 2026-11-22; local branch only, not deployed)

- **Where:** `~/works/quarkus-gpu-broker`, new local branch `llm-relay` from `1a9033c`, commit `2d9d336`.
  - Made in a separate worktree, so the broker's working copy stayed on `main` and untouched.
  - Author `scivicslab <devteam@scivicslab.com>`.
  - No push. Not deployed: the running broker at 192.168.5.21:30805, its config and its image were not touched.
- **Change:**
  - `HttpAiServiceClient` now sends through `RelayCall.sendAndWait`, with an adapter from `RelaySink` to `ResponseSink`. `AiServiceClient` and the test doubles are unchanged.
  - `AiServiceEndpointBuilder` passes Quarkus's `Vertx`; a client built outside CDI (tests) uses one private Vert.x.
  - The pom adds `llm-relay-core` 0.1.0-SNAPSHOT.
  - The broker's sinks are called on the Vert.x event loop now. I read the four (`StreamingResponseSink`, `PolledResponseSink`, `RepetitionStoppingResponseSink`, `GenerationMeasuringResponseSink`), and none blocks.
- **F1, fixed as chat-48 decided:**
  - A failure after the start raises the new `AiServiceFailedAfterStartException`. `AiServiceEndpointWorker` fails the sink and records the job failed, instead of requeueing it.
  - A failure before the first byte is requeued as before.
  - The change is noted in the branch's commit message and in the broker's `tasks/todo.md`.
- **F2:** no timeout is set (`RelayOptions.DEFAULTS`).
- **Tests first.** The red run failed to compile (`cannot find symbol AiServiceFailedAfterStartException`).
  - `HttpAiServiceClientTest` grows from 2 to 8 tests: B1/B2 (body, Content-Type, HTTP/1.1, no `Upgrade`), B3, B4, B6, B7/B8/B10, B9, F1 and B11.
  - The new `FailureAfterStartTest`, 1 test, has two endpoints: after a failure that follows the start, the sink is failed, the job reached one endpoint only, and nothing was started twice.
- **Result** (W206 Job `chat77-relay-1`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS): 243 tests, with 0 failures, against 236 at `1a9033c` in the same Job.
- **Vert.x:** the uber-jar `quarkus-gpu-broker-1.2.0-SNAPSHOT.jar` carries one `META-INF/maven/io.vertx/vertx-core/pom.properties`, version 4.5.21, and `com/scivicslab/llmrelay/RelayCall.class`. `mvn dependency:tree` shows `llm-relay-core`, then `vertx-core` 4.5.21.
- **Next:** deploying waits for the person's approval, which chat-48 asks for.

### Step 5: the 5.14 checks through the one Quarkus socket (done 2026-11-22)

- **The test host, `e2e/confinement-host`.** `app` gives no keys and ssh-connector does not exist yet, so this test-only Quarkus application stands in. Its own pom is outside the reactor, and it is not shipped.
  - It is agent-connector-kernel with one keyed scope (`HostScopes`, an `McpScopes` bean) and the LLM proxy, on the domain socket only (`quarkus.http.host-enabled=false`).
  - It writes the two keys to a 0600 file in a 0700 directory (`KeysFile`), and records each `calc` call.
  - It gets the real (dummy) credential through its environment (`AGENT_CONNECTOR_KERNEL_LLM_CREDENTIAL`), not its command line.
  - `e2e/ConfinementHost.java` starts it at nice 19 and stops it by its own PID. `ConfinementCheck` and `HarnessStubRun` use it, compiled together by the source launcher.
- **Builds:** in W206 Job `chat77-split-s5` (the agent-connector-kernel install runs its tests). Every jar and source passed `sha256sum -c` on 5.14 against the Job's checksums. The image files matched `git show HEAD:<path>`, and the six images were rebuilt from them at nice 19.
- **Part 1: the 16 confinement checks, PASS 16/16 for each of Codex CLI 0.171.0, OpenCode 1.19.6 and Hermes Agent 0.23.3.**
  - The socket is `rw-------` in an `rwx------` directory.
  - MCP `tools/list` (stateless, protocol `2026-07-28`) and one proxied request (`/llm/check/v1/chat/completions`) work through the relay.
  - External TCP, DNS, the host's loopback port, addresses and gateways fail.
  - The markers in the agent-connector-kernel directory and in `~/.ssh` cannot be read.
  - The real key is in no environment and no file; the throw-away key is where the harness expects it; the stub upstream received the real dummy key.
- **Part 2: the real harness binaries against the stub model, PASS for all three, plain and traced:**
  - exit 0, and the real key was not given to the container;
  - every request reached the stub with the real dummy key;
  - the stub's tool call reached the MCP server (`calc(6*7)` recorded by the host) and its result was in the next request;
  - the canned reply was printed;
  - traced: every connect and send went to 127.0.0.1:18765/18766, the relay to the one socket (`other targets []`). Not network: glibc's absent nscd socket (a passwd lookup), and, for Hermes, its own gateway socket and its own server on the container's loopback.
- **Found and fixed on the way:**
  1. *My check script:* `socat` half-closed the connection after writing the request, and Vert.x then dropped the unanswered request (empty answers). The old hand-written server had ignored that. The script now uses `shut-none`.
  2. *My stub:* Codex puts MCP tools in a Responses API `namespace` entry (`mcp__agent_connector_kernel`), and a call carries `namespace` beside `name` (codex-rs `protocol/src/models.rs`, `FunctionCall.namespace`, at `rust-v0.171.0`). The stub now finds and calls a tool there.
  3. *Codex refused every MCP call* with "MCP tool call requires approval, but approval policy is never". `CodexHarness` now sets `default_tools_approval_mode = "approve"` under `[mcp_servers.agent-connector-kernel]` only, with no global approval or sandbox setting (https://learn.chatgpt.com/codex/config-file/config-reference, `mcp_servers.<id>.default_tools_approval_mode`).
     - Test first: `HarnessConfigTest.codexApprovesOurMcpServersToolsAndNothingGlobal` was red on W206 (`expected: <true> but was: <false>`), then green.
  4. *Hermes hid MCP tools behind its tool search.* It offers `tool_search`, `tool_describe` and `tool_call` instead of the tools (`tools/tool_search.py`, v0.23.3: "MCP/plugin tools … are replaced in the model-visible array by three bridge tools"). `HermesHarness` now sets `tools.tool_search.enabled: "off"`.
     - Hermes' first chat request then lists `mcp__agent_connector_kernel__calc` and our other five tools by name.
     - Test first: `hermesListsOurMcpToolsDirectly` and the exact `hermes` config test were red, then green.
  5. *The stub limits (chat-48, t161 and t162):*
     - the stub gives its plain reply after 3 tool-call rounds, so a harness that refuses a tool ends the run;
     - plain stub runs stop after 2 minutes, and traced runs after 8, because Hermes starts slowly under strace.
     - Before the cap, Codex's refusals looped 9,531 times against the local stub, none to a model.
- **Hermes' source-only keys, pinned to v0.23.3:** `model_catalog.enabled`, `updates.check` and `tools.tool_search.enabled` are read from its source, not its docs. They must be checked again whenever the Hermes version changes.
- **`Mcp-Session-Id header not found`** (one WARN per Hermes run). It is explained by the host's access log:
  - before initializing, Hermes probes `HEAD /mcp` and `GET /mcp` with no session; the server answers 405, and the `GET` triggers the warning;
  - then `POST /mcp` initialize answers 200 with a session (protocol `2025-11-25`), and every later request carries it, its `GET` stream (200) included.
  - So the library's session handling works for Hermes, and nothing needs patching.
- **W206 full suite on this tree** (`rm -rf target */target`, `mvn -B test` and `mvn -B package`, BUILD SUCCESS):
  - counts: `plugin-api` 0, `agent-connector-kernel` 101, `app` 792, `plugin-web-tools` 17, `plugin-shell` 11, `plugin-harness` 61; total 982, with 0 failures;
  - against 980 after step 4a.2: +2 (`codexApprovesOurMcpServersToolsAndNothingGlobal`, `hermesListsOurMcpToolsDirectly`);
  - E2E: `McpServerCheck` and `ToolCallingCheck` ALL PASSED; `ExternalMcpCheck` and `--restored` exit 0.

### Step 6: real models, through the confined container and the kernel's proxy (done, 2026-11-22)

- **Before the runs:**
  - the broker's `/queues` showed the three chat queues UP and idle (GLM-5.3 0/4, gemma 0/32, Qwen3.8 0/1);
  - 5.16 `GET /v1/responses` answered 404 `{"error": "not found"}`;
  - one minimal `POST /v1/responses` answered 200 (`"model": "GLM-5.3-Flash-Uncensored"`, text `ok`), so Codex with GLM-5.3 was run.
- **The runs:** each was preceded by a fresh read of `/queues`. There was one prompt per run ("Use the calc tool to compute 6*7, then answer."), one request at a time through the pass-through, no retry loop and no admin call.

| Harness | Model served | First line of the reply | MCP tool call | Result |
|---|---|---|---|---|
| OpenCode 1.19.6 | `GLM-5.3-Flash-Uncensored` (5.16) | `42` | `agent-connector-kernel_calc {"expression":"6*7"}`; the host ran `calc(6*7)`; the result was in the next request | PASS |
| OpenCode 1.19.6 | `qwen3.8-flash-next-iq3_xxs-radeon` (5.23) | `42` | the same | PASS |
| Hermes Agent 0.23.3 | `GLM-5.3-Flash-Uncensored` (5.16) | `6 * 7 = 42` | `calc(6*7)` ran and its result was in the next request | PASS |
| Codex CLI 0.171.0 | `GLM-5.3-Flash-Uncensored` (5.16) | none: exit 1 | none | FAIL at the model server; waits for an OpenAI key |

- **Codex's raw error.** The model server answered Codex's first request with 400:
  `{"error": {"message": "tools of type 'namespace' are not supported: this server runs function tools only", "type": "invalid_request_error", "param": null, "code": null}}`.
  - Codex sends its MCP tools as a Responses API `namespace` tool (see step 5), and GLM-5.3's server takes function tools only.
  - One request reached the model, and the run stopped there.
  - The same server answers `POST /v1/responses` itself with 200 (the minimal request above, without tools). What it refuses is the `namespace` tool type.
- **Codex: decided (t163:r1).**
  - The person's decision named OpenAI as Codex's test model; GLM-5.3 was only an extra chance.
  - The proxy does not translate `namespace` tools into function tools. That would be working around the model server.
  - Codex's real-model run waits for an OpenAI key, as Claude Code's waits for an Anthropic key.
- In every passing run: the real key was not given to the container, and every request reached the model through the proxy with the real (dummy) key.
- Hermes' own probes before its chat request went through the proxy too, all read-only: `GET /api/v1/models`, `/api/tags`, `/v1/props`, `/props`, `/version`, `/v1/models`, and `POST /api/show`. They answered 404, or 200 for `/v1/models`.
- The check took the relay's closing `socat … Connection reset by peer` line for Hermes' first reply line. The actual reply is `6 * 7 = 42`.
- **Waiting for keys:** Claude Code (Anthropic) and Codex CLI (OpenAI).
- **Clean-up after step 6:**
  - on 5.14 the six images (`localhost/ack-{codex:0.171.0,opencode:1.19.6,hermes:0.23.3}` and their `-traced`) were removed with `podman rmi`. `podman ps -a` lists no containers, and `podman images -a` lists only `docker.io/library/ubuntu:24.04`, which is chat-01's;
  - no `~/.ssh` markers are left on 5.14;
  - no W206 Job or pod of this work is left.

### Questions on the plan change (all settled)

1. **Point 5: settled by the person's decision (2026-11-22, t147:r1).** The LLM proxy uses Vert.x HTTP Proxy, with the part shared with the GPU broker in `llm-relay` (step 4a).
2. **Keys on `app`'s `/mcp`: settled (t144:r1, Decision 13).** One setting per deployment, `agent-connector-kernel.mcp.require-key`; `app` sets `false`.
3. **One socket for both endpoints: settled (t144:r1).** The proxy is at `/llm/<scope>/v1`.

## Rules every stage follows

- Work on `nigsc-connector-split` only, never on `main`. No push without asking chat-48.
- Do not stop, replace or restart the running chat-ui instances (ports 28000–28020, the jars in `~/works`), and do not deploy. E2E checks run against throw-away instances inside W206 Jobs. They never use the iolog files in the repo root (`chat-ui-iolog-28014.mv.db`, `chat-ui-iolog-28031.mv.db`). Those files are live or local data: never open them, never stage them, and stage only named paths (no `git add -A` or `git add .`).
- Build and test only in W206 k8s Jobs, one Job per build. Never use `-DskipTests`; use `rm -rf target`, not `mvn clean`.
- Concurrency is written with POJO-actor, plain POJO first, and no `Actor` in class names. The raw threads that move unchanged (`CliProcess` lines 137 and 165, `CodexProvider` line 129) are not rewritten in this rebuild.
- Code comments and messages are in English.
- Move classes without changing their package names. Change nothing on the screen-and-record side beyond what a cut needs.

## Review


---

# 急ぎの依頼（110）と timekeeper（120）

設計: `doc_SCIVICS003/.../040_design/110_UrgentRequestAndStatusReport_261107_oo01`、`120_TimekeeperPausesATurnForAnUrgentRequest_261107_oo01`。

## 段D: 急ぎの依頼（110）

- [x] D1. 待ち行列の項目に `urgent` と `queuedAt` を足す。急ぎの項目は、先頭から続く急ぎの項目の並びの末尾に入れる
- [x] D2. `ask_chat_urgent(chatId, prompt)`: 見出し `[urgent request <ID> from <会話>]`、結果に追い越した数。返事も急ぎで入れる。子の依頼として数える
- [x] D3. 道具の説明（状況を尋ねられたときの監督役のふるまい）
- [~] D4. Queue の欄に急ぎの印。ブラウザでの確認は deploy の後
- [x] D5. 単体テスト2件 → W206 で `mvn test`: 全モジュール成功（app 452、plugin 17・11・42、失敗 0）

## 段E: timekeeper（120）

- [x] E0. 確かめた: 中断したターンの途中の段は `--resume` したセッションに残る（CLI が実行したコマンドと終了コード 137 を答えた）。最初のターンで中断されるとセッションの番号が記録されていない穴があり、`init` の事件からも記録するよう直した
- [x] E1. `<会話>.timekeeper` の子アクターと 30 秒ごとの見回り。会話を消すと消える
- [x] E2. 急ぎの依頼が `urgentWaitMinutes` を過ぎて待つとターンを中断し、`[resume interrupted turn …]` を急ぎの項目の直後に入れる。中断したターンは元の依頼に答えない。続けるターンが元の依頼を受け継ぐ
- [x] E3. 中断したターンの途中経過（`turnTranscript`）を、そのターンの答えとして履歴に入れる（`collapseTurn` を飛ばすだけでは、間に入る急ぎのターンのまとめで消える）
- [x] E4. 子の依頼に `childReplyWarnMinutes` を過ぎても返事が無いとき、1回だけ知らせる
- [x] E5. 設定は会話ごとでなくインスタンスの設定の値 `chat-ui.timekeeper.urgent-wait-minutes`（10）・`chat-ui.timekeeper.child-reply-warn-minutes`（30）にした。120 も合わせて直した
- [x] E6. 単体テスト2件（中断して急ぎに答え再開したターンが元の依頼に答える／返事の無い子の依頼を1回だけ知らせる）→ W206 で全モジュール成功（app 454、plugin 17・11・42、失敗 0）

## 段F: deploy（確認を取ってから）・28010 で実機試験

- [x] F1. Build Snapshot と 6 インスタンスの再起動（11/8 00:00、全て AI-workspace の子プロセス、新しい jar）
- [x] F2. 作業役 `chat-20`（Claude Code）に約 13 分のターン（`…:r1`）、00:02:31 に急ぎの依頼（`…:r2`）。00:12:45 に timekeeper が t1 を中断（10 分 14 秒待ち）→ t2 が急ぎに答え（パート1済み、パート2は 137 で中断と正しく報告）→ t3 が再開してパート2を実行し、`…:r1` に `LONG JOB DONE` を返した。t1 は返事を送っていない
- [ ] F3. 未確認: Queue の欄の急ぎの印（ブラウザ）、local LLM での中断と再開、子の依頼の返事が無い知らせ、`init` からのセッション番号

## Review

---

# ターン ID・依頼 ID（090）と、配った仕事の最終報告を依頼元へ届ける（100）

設計: `doc_SCIVICS003/.../040_design/090_TurnAndRequestIds_261107_oo01`、`100_ReportReachesTheAskerAfterDelegation_261107_oo01`。
続く設計（未着手）: 110 急ぎの依頼と状況の報告、120 会話ごとの timekeeper アクターとターンの中断。

## 段A: ID（090）

- [x] A1. `ChatSession` にターン ID（`<会話>:s<セッション>:t<ターン>`）と、ターンごとの `ask_chat` の回数から作る依頼 ID（`<ターン ID>:r<回数>`）を持たせる。素の `startPrompt` の経路でもセッションとターン番号を持たせる
- [x] A2. 待ち行列の項目に `requestId`（依頼ならその ID、返事なら答えている依頼の ID）を足し、`ChatSession.start` まで渡す。`ask_chat` の依頼では `resultKey` に依頼 ID を使う
- [x] A3. `ask_chat` の結果・依頼の見出し `[request <ID> from <会話>]`・返事の見出し `[reply to request <ID>, from <ターン ID>]`・返事が無いときの1行を 090 のとおりにする。`TurnReply` にターン ID を足す
- [x] A4. 会話の記録（`HistoryEntry`）に `turnId` と `requestId` を足し、記録から描く吹き出しの足元に出す。ブラウザでの確認は deploy の後。生の SSE で届く吹き出しには、まだ ID が出ない
- [x] A5. `ask_chat` の道具の説明を新しい見出しに合わせる
- [x] A6. 単体テスト → W206 の Job で `mvn test`: 全モジュール成功（app 442、plugin 17・11・42、失敗 0）

## 段B: 最終報告（100）

- [x] B0. 確かめた: 人が Queue の欄から項目を消す `PromptQueue.removeAt`（と、今は呼ばれていない `clearAgentMessages`）は、別の会話からの依頼を `done` を完了させずに取り除く。依頼元には返事が永久に届かない。取り除くときに返事の無い `TurnReply.NONE` で完了させるよう直した
- [x] B1. 元の依頼の表（`done`、まだ返っていない子の依頼、返事を送ったか）と、子の依頼から元の依頼への対応
- [x] B2. ターンの元の依頼を決める（依頼・子の返事・利用者）、子の依頼を送るときに数える、ターンの終わりに空なら `done` を完了させる
- [x] B3. 自発のターンの答えを続報 `[follow-up to request <ID>, from <ターン ID>]` として送る
- [x] B4. 単体テスト `OpenRequestsTest`（6件）と `ConversationToolsTest` の2件。委ねる試験は段Aのコードで赤（依頼元に最初のターンの答えが届く）、直して緑。W206 の Job で全モジュール成功（app 450、plugin 17・11・42、失敗 0）

## 段C: deploy（確認を取ってから）・28010 で実機試験

- [x] C1. Build Snapshot と 6 インスタンスの再起動（22:30〜22:31、全て AI-workspace の子プロセス、新しい jar）
- [x] C2. 監督役 `chat-19`（Claude Code）が `chat-16`・`chat-18` に配った試験: 依頼元に届いたのは t3 の最終報告の1通だけ。見出しと表の ID が辻褄が合う
- [x] C3. 吹き出しの足元の Turn・Request の表示をブラウザで確認（ユーザー）
- [x] C4. Claude Code の自発のターン: 背景の `sleep 45` の後に t5 が自分で始まり、`[follow-up to request …:t579:r1, from …:t5]` が依頼元に届いた

## Review

---

# ask_chat の返事を done に載せる・道具の説明を直す・会話を作るときにプロバイダとモデルを選ぶ

設計: `doc_SCIVICS003/.../040_design/050_AskChatWithoutWaiting_261022_oo01`（返事の渡し方と道具の説明）。
プロバイダとモデルの選択は、新しい設計文書 `040_design/080_...` を先に書いてから実装する。

## 段1: 返事を done に載せる（050 の設計）

- [x] 1. 返事と終わり方の1行の組を表す型を作る（`TurnReply`、record）
- [x] 2. `done` の型を `CompletableFuture<Void>` から `CompletableFuture<TurnReply>` に変える（`PromptQueue.enqueue` の重ね定義、待ち行列の項目、`ChatSession.start` の重ね定義、`onPromptComplete`）
- [x] 3. 返事と終わり方の1行を `done` に入れて完了させる。`finish` でなく、2つの経路（エージェントループと素の `startPrompt`）が共に通る `onPromptComplete` で入れた。返事の無いターンは `TurnReply.NONE`
- [x] 4. `AskChatTool.deliverReply` と `askQualified` が `done` の値から返事を取る。`getCompletedResult` への問い合わせをやめる
- [x] 5. `resultKey` と `completedResults` は残す。`ChatSessionIIAR` のワークフローの `sendPrompt`/`getResult` が結果の状態を読むのに使っている
- [x] 6. 単体テスト `ConversationToolsTest.theReplyArrivesWhileTheAskedConversationRunsItsNextQueuedTurn`: 前のコードで赤（`error: ask_chat failed: TimeoutException: null`）、直して緑（1.03 秒）。W206 の Job で実行
- [x] 7. `ask_chat` の道具の説明を 050 のとおりに直す
- [x] 8. `rm -rf target` → `mvn test`: W206 の Job で全モジュール成功（app 435、plugin 17・11・42、失敗 0）

## 段2: 会話を作るときにプロバイダとモデルを選ぶ

- [x] 9. 設計文書 080 を書いて確認を取る（doc_SCIVICS003 ca6ca3e）
- [x] 10. `create_chat(provider, model)`、`list_providers`、`set_chat_provider` を実装し、単体テストを書く（`ConversationToolsTest` に 6 件）
- [x] 11. `mvn test`: W206 の Job で全モジュール成功（app 441、plugin 17・11・42、失敗 0）

## 段3: deploy（確認を取ってから）

- [ ] 12. deploy の前に確認を取る。稼働中の 28014 は勝手に再起動しない

## Review

---

# audit trail の使い方チュートリアル（5 段）

置き場所: `doc_SCIVICS003/docs/chat-ui-with-audit-trail/050_tutorials/`（`010_ThreeStartupConfigurations_260912_oo01` の次）

狙い: graph engineering をユーザーの視点で段階的に説明する。1 段につき増えるものは 1 つだけにする。

| 番号 | 題 | 増えるもの |
|---|---|---|
| 020 | 会話を 1 つ回し、その記録を読む | — |
| 030 | 会話を 2 つにして、片方がもう片方に頼む | 2 つ目の会話 |
| 040 | 監督役と作業役に分ける | 役割の固定 |
| 050 | 計画を書いて複数の worker へ並列に配る | 並列 |
| 060 | 出来た graph をプロジェクトのジョブとして回す | 再現性 |

実機確認: 稼働中の 28014 には触れない。使い捨てのポートで Local LLM 構成（`chat-ui.servers` = gpu-broker 28005）を立てて確かめる。provider 切替の段だけ構成 3 を使う。

## 手順

- [x] 1. 使い捨てインスタンスを立て（28032、構成 2）、020 の手順を実際に回して記録を採る
- [x] 2. 020 を書く（`020_OneConversationAndItsRecord_260913_oo01`）
- [x] 3. 030 を回して書く（構成 3 での provider 切替を含む）
- [x] 4. 040 を回して書く
- [x] 5. 050 を回して書く
- [x] 6. 060 を回して書く（Run タブと Batch Jobs、JobParameterForm_260913_oo01 の続き）
- [ ] 7. コミット・push（push は確認を取る）

このあとの予定（別タスク）: 一般論ではなく具体的応用。文章をブラッシュアップさせるワークフロー、文献を調査して詳細なレポートを作り上げるワークフロー。

## Review

5 本とも、使い捨てのポート（28032＝構成 2、28033＝構成 3）で実際に回してから書いた。稼働中の 28014 には触れていない。

実機で分かったこと:
- `ask_chat` などの `chatId` に渡すのは `02`。`chat-02` と書くと `project1/chat-chat-02` になって見つからない
- 循環は待たずに断られる（`error: refused — asking project1/chat-01 would create a circular wait`）。2 本の循環を実機で踏んだのは今回が初めて
- 監督役として走ったターンは Sessions タブから読めない。`lastTurn` は進むのに `GET /api/sessions/{id}/trace/{turn}` が `no such turn` を返す（040 に記載、要修正）
- `turing-workflow` 4.1.0 では値の読み出しは `jexl:state.getString('key')`

次（別タスク）: 具体的応用。文章をブラッシュアップさせるワークフロー、文献を調査して詳細なレポートを作り上げるワークフロー。

---

# 右ペインに Actions タブ（chat と project の両パースペクティブ）

## 経緯
quarkus-chat-ui の Workflow タブと Actions タブを移す話から始まり、Workflow タブは要らないと決めた。
quarkus-chat-ui では workflow の実行器（harness）が会話の provider に直接ターンを送るので workflow は
会話の 1 ターンの代わりになり、プロンプトキューの項目として並ぶ意味があった。CUAT では workflow は別の actor
（`PlanRunner`、project の `job-NN` か会話の `.plan`）が実行し、会話には `sendPrompt` でプロンプトを入れる側
なので、キューの項目にはならない。実行の入口は project パースペクティブの Run が既にある。

## やったこと
- [x] 親 pom: turing-workflow 4.3.0-SNAPSHOT。各モジュールの pom が 4.2.0 を直書きしていたのを親のプロパティに
- [x] app/pom.xml: doclet 実行（`META-INF/turing-plugin/<groupId>.<artifactId>.json` と services の索引）。
      JSON Schema 生成器に module のパスを渡す（4 モジュール化以来 reactor root の空の target/classes を見て
      schema を 1 つも書いていなかった）
- [x] `PlanRunnerIIAR` の 26 step と `PlanWorkerIIAR.ask` を `@Action` メソッドに。Javadoc は第一文・本文・
      同梱 YAML から取った `<pre>` の step・`@param args` の位置
- [x] `ChatUiActorSystem.actorClassOf`（`this` → `PlanRunnerIIAR`、会話の修飾名 → その `ChatSessionIIAR`、
      それ以外は生きている actor のクラス）と `workflowActorNames`
- [x] `ActionsResource`: `GET /api/actions`、`/api/actions/list?actor=`、`/api/actions/describe?actor=&action=`
      （actor 名に `/` が入るのでクエリ）
- [x] console.html / console.js / console.css: Actions タブ。ボタンは perspective ごとに 1 つずつ、section は
      perspective 無しで両方に出る。Show・Clear・一覧に第一文・1 件表示に引数・本文・YAML
- [x] `ActionDescriptionTest`（4 件）。259 テスト緑。使い捨てポート 28042 で両パースペクティブをヘッドレス確認

## Review
- `this` は 43 action（plan の 26 と engine の 17）が全部説明付き。`project1/chat-01` は 46 action のうち
  27 に説明がある: `ChatSessionIIAR` の 29 のうち Javadoc に第一文が無いものは名前と組み立てた step だけ出る。
  書くなら `ChatSessionIIAR` の Javadoc を書けば次のビルドで出る。
- actor の候補は `this` と actor 木の全名（19 件、`promptBuilder` などの内部 actor も含む）。
- 稼働中の 28014・28030・28031 と `~/works` のリンクは触っていない。

## 続き: Actions タブを上下 2 段に（actor の一覧 / 選んだ actor の action）
- actor は増えていくので、名前を打つ field をやめ、上段に actor の一覧（`this` と生きている actor 全部、
  origin ごとの見出し、絞り込みの field）、下段に選んだ actor の action（第一文付き）。action を押すと説明に
  変わり、「← <actor>'s actions」で一覧に戻る。段の境はドラッグで動き高さは localStorage に残る。Clear は
  選択と絞り込みを消す。
- `GET /api/actions` は `{name, type, origin}` の行を返す。会話の行の type はその `ChatSessionIIAR`。
  `list`/`describe` は `origin` も受ける（名前が重複する quarkus-chat-ui と同じ形にするため）。
- 使い捨てポート 28042 でヘッドレス確認: 19 行、`project1/chat-01` を選ぶと 46 action、`sendPrompt` を開くと
  step が出て戻れる、絞り込み・Clear・ドラッグが効く、ブラウザのエラー無し。

---

# 相手の会話を見る・モデルを替える道具と、ask_chat の終わり方の 1 行

背景: project1/05（Gemma 4）に章の開始位置を訊いたとき、返ってきたのは道具呼び出しの文面だった。
モデルを替えて試す手段も、相手の状態を読む手段も、エージェントの道具として無かった。

- [x] `TextToolCallParser` が `<tool_call><function=…>` の形式を読む（テスト 6 件）
- [x] `ConversationControls`（モデル切替・ログ取得の狭い窓口）を作り、`ChatUiActorSystem` が実装して `ChatSession` に渡す
- [x] 道具 `get_chat_status` `list_chat_models` `set_chat_model` `get_chat_log` を作る
  - 読む 3 つは別プロジェクトの会話も可。`set_chat_model` は `set_workflow` と同じく別プロジェクトを拒否
  - `set_chat_model` は相手が選べるモデルの一覧に無い名前を拒否する（broker のモデル名が変わっていて 404 になった事故への手当て）
- [x] `ChatSession` の道具説明と `executeTool` に 4 つを登録する
- [x] `ask_chat` の結果の末尾に終わり方の 1 行を付ける（道具としての `ask_chat` だけ。`PlanRunner` などが使う `askQualified` の戻りは変えない）
- [x] 単体テスト（`mvn test`、`-DskipTests` は使わない）
- [ ] 稼働中の audit-trail への反映（ビルド・再起動）は別途確認

## Review（相手の会話を見る・モデルを替える道具）

- `app` モジュール全体 356 件が通る（新規 `TextToolCallParserTest` 6、`ChatInspectionTest` 11、`ConversationToolsTest` 9）
- 道具の実行は、実際の会話 2 つ（chat-01 が呼び、chat-02 を見る）を 1 つのアクターシステムに置いて通した
- `set_chat_model` は、相手が選べるモデルの一覧に無い名前を拒否し、一覧を答えに載せる
- `ask_chat` の末尾の 1 行は、道具としての `ask_chat` だけ。`askQualified`（`PlanRunner` など）の戻りは変えていない
- 稼働中の audit-trail（28014 など）には反映していない。ビルドして再起動するまで、この機能は使えない
- 未コミット。別の作業の変更が同じ作業ツリーにある（`ArtexProxyResource.java`、`app.js`、`application.properties`、`KeybindResource*`）

# エージェントの道具で会話を作る・消す（`create_chat` `delete_chat`）

背景: 実験用の会話（chat-06 など）を作る道具が、エージェントに無かった。REST の `GET .../status` を
存在しない id に呼ぶと会話が副作用で作られるため、実験のたびにそれを使い、後で消していた。
`get_chat_status` 道具は存在しない id に `error: chat not found` を返し、作らない。
REST には作成 `POST /{projectId}/chats`（id は actor system が採番し `{"chatId":"06"}` を返す）と
削除 `DELETE /{projectId}/chats/{chatId}` が既にある。足りないのは道具だけである。

方針（未承認）:
- [ ] `ConversationControls` に `String createChat(projectId)` と `ChatDeletion deleteChat(projectId, chatId)` を足す
      （`ChatUiActorSystem` は既に両方を持つ。窓口に出すだけ）
- [ ] `CreateChatTool`（`create_chat()`）: 呼んだ会話と同じプロジェクトに採番して作り、`ok: created project1/chat-06` を返す。
      別プロジェクトには作れない（`set_chat_model` と同じ理由）
- [ ] `DeleteChatTool`（`delete_chat(chatId)`）: `ChatUiActorSystem.deleteChat` の拒否理由
      （chat-01、最後の1つ、実行中、キュー有り、子有り）をそのまま答えに載せる。自分自身の削除は拒否する
- [ ] `ChatSession` の道具説明と `executeTool` に2つを登録する
- [ ] `ConversationToolsTest` に、作成→採番→`get_chat_status` で見える／削除→見えない／拒否の各場合を足す（`mvn test`、`-DskipTests` は使わない）
- [ ] 稼働中の audit-trail への反映（ビルド・再起動）は別途確認

決めてほしいこと:
- REST の `GET .../status` ほか約14個が `createChat` を呼ぶ「無ければ作る」は、画面（会話の切替時）が
  依存しているため、今回は変えない。変えるなら別の計画にする。

## 「無ければ作る」の廃止（追加・実装済み）

会話は `POST /api/projects/{p}/chats`（採番）、`POST .../chats/{id}`（id指定）、道具 `create_chat` でだけ作る。
名前を言及しただけの要求（`GET .../status` など12個のエンドポイント、`setModel`/`setNoThink`/`setEffort`/`setTemperature`/`setProvider`）は
存在しない会話を作らず、REST は 404 `no such conversation: ...`、アクターシステムの設定変更は `false`／`IllegalArgumentException` を返す。
- [x] `ChatUiActorSystem.hasChat` を足し、上の暗黙作成を外した（復元・`init`・採番作成は従来どおり）
- [x] `ChatResource.requireChat`: 12個のエンドポイントと provider 切替の前で 404
- [x] `app.js`: 保存済みの会話 id が 404 なら `project1/chat-01` に戻す（旧コメントが書いていた「古い id で空の会話が作られる」不具合の根本対処）
- [x] `ChatIsMadeOnlyWhenAskedTest`（3件）。`setNoThink` に旧動作を戻すと落ちることを確認（赤→緑）
- 全体 391 件。`ArtexProxyLoggingTest` は変更前の `HEAD` でも 3 回中 2 回落ちる既存の不安定なテスト（非同期のログ書き込みを5秒待つ）で、今回の変更とは無関係
- [ ] 稼働中の audit-trail への反映（ビルド・再起動）は別途確認。反映までは旧動作のまま

# request messagesに送ったプロンプトを省略せず残す（取り下げ：下の「エージェントループがプロンプトを決める」に取り込む）

現状: 1 ターンの間、`OpenAiCompatProvider.history` にはそのターンで送った全メッセージ
（先頭は `ChatSession.firstStepPrompt()` の前置き約 1.4 万字 + 利用者の文、続いて各ステップの
モデル応答とツール結果の観察）が積まれる。ターンが終わると `collapseTurn(question, answer)` が
それらを捨て、利用者の文だけ（前置きなし）と最終回答の 2 件に置き換える
（`TurnResourceLimits_260830_oo01`：ステップの観察が前のターンを押し出すため）。
そのため `get_request_messages` / `GET .../request-messages` が見せるrequest messagesは、実際に送った文と一致しない。

要求: request messagesには、やり取りしたプロンプトを省略せずに残す。

方針（案）:
- [ ] `ChatSession` のターン終了時の `collapseTurn` 呼び出しをやめ、ターン中に積んだメッセージ（前置きつきの先頭プロンプト、
      モデルの各応答、ツール結果）をそのままrequest messagesに残す
- [ ] 予算を超えたら古いメッセージから落とす既存の `fitHistoryToBudget` はそのまま。落とすのは 1 メッセージずつではなく
      ターン単位にするか（途中から始まるrequest messagesは user/assistant の対応が崩れる）を決める
- [ ] 再起動後の復元（`ChatUiActorSystem` の `collapseTurn(t.question(), t.answer())`）は、I/O ログの `userPrompt`
      （前置きつきの全文）から作り直す。ステップごとの observation はログにあるので同じ形に戻せるか確かめる
- [ ] テスト: 2 ターン送ったあとのrequest messagesが、実際に送った messages と一致する（前置き含む）／予算超過時の落とし方／復元
- [ ] `RequestMessagesControl_261018_oo01` の説明（「次に送る配列」）と合うか確認

決めてほしいこと:
- 前置き 1.4 万字がターンごとにrequest messagesへ入る（10 ターンで約 14 万字）。そのまま残すのが要求どおりだが、
  前置きを会話の先頭に 1 回だけ置く形（別の変更）にしてよいか。

# ツールなしの会話（`ToolSet.NONE`）（取り下げ：下の「エージェントループがプロンプトを決める」に取り込む）

背景: openai-compat の会話は、ツールを使わなくても毎ターン約 1.4 万字の前置き
（書式の説明 786 + ツール説明 9,874 + 決まり 975 + スキル一覧 2,299）が先頭プロンプトに付く。
文章生成の会話（フレームの生成）では要らず、モデルの答えを変える
（概念リストが 5 個→1 個、`load_skill` の呼び出しを書く）。ツールを選ぶ手段が `full` / `harness` の 2 つしかない。

方針（最小）:
- [ ] `ToolSet`（plugin-api）に `NONE` を足す。いまは除外する名前の一覧で作られていて、
      プラグインのツールは名前が決まっていないため、`contains` を「すべて含まない」と書ける形にする（`parse("none")` も通す）
- [ ] `ChatSession.firstStepPrompt()` で `NONE` のとき、書式の説明・ツール説明・決まり・`read`/`write` の許可先・スキル一覧を付けない。
      プロジェクトの指示（`AGENTS.md` / `CLAUDE.md`）は利用者が置いたものなので残す
- [ ] `executeTool` の既存の拒否（`toolSet.contains` が偽なら `error: tool ... is not available`）がそのまま効くことを確かめる
- [ ] 提供元の一覧に `Local LLM (no tools)`（openai-compat + `NONE`）を足す。画面の選択肢は `kind:toolSet` 形式で既に対応
- [ ] 設定の記録（`tools` は文字列）と復元（`ToolSet.parse`）が `none` を通す
- [ ] テスト: 先頭プロンプトが利用者の文だけになる／ツール呼び出しを書かれても実行しない／`parse` と記録・復元／
      既存 `ChatSessionToolSetTest` が通る。E2E `ProviderSelectE2E` の選択肢の数の前提を確認
- [ ] 別の変更として: 用途別（ファイル、文書・Web、会話操作）の組、各ツール説明の書式の重複（13 文・815 字）の削除、
      `create_chat` が提供元とツールを指定できること

# エージェントループがプロンプトを決める（方針は承認済み、実装は未承認）

背景: 会話に送ると、利用者の文の前に約 1.4 万字の固定の文（ツール説明など）が毎回付く。ワークフローを選んでも、
`ChatSession.firstStepPrompt()`（Java）が付けるので消せない。ワークフローが組み立てるのは文字列で、
モデルへ渡す `messages`（JSON）やrequest messagesは、ワークフローから決められない。ツールなしの会話や、
request messagesに送った文を残す会話を作る手段がない。

方針（承認済み）:
- エージェントループのワークフロー（YAML）が、プロンプトの組み立て・request messagesに残す内容を含めて、会話の動かし方をすべて決める。
  用途ごとにループを用意し、呼び出すときにどれを選ぶかで動作を選ぶ。
- 道具の実行の許可（`ToolSet`）はいまのまま、プロンプトとは別に残す。モデルが知らないツールを呼んでも、実行は断るだけ。

作業:
- [x] 1. 前置きを部品に分け、ワークフローの操作として足す（d9f2e2a。部品は `ChatSession` の操作 `toolInstructions`/`skillCatalog`/`projectInstructions`/`turnText`。
      PromptBuilder 自身はツールを知らないため、操作は会話側に置いた。文章生成用 `prompt-construction-plain.yaml` を追加）。以下は元の記述:: ツール説明（会話の `ToolSet` の分だけ）、ツールの決まり、
      スキル一覧、プロジェクトの指示（`AGENTS.md`/`CLAUDE.md`）。`firstStepPrompt()` はこれらを積む既定の手順に置き換える（出力は変えない）
- [x] 2. ワークフローが `messages`（JSON）をそのまま渡せるようにする: `PromptBuilder.buildMessages(request messages)` の結果を会話が受け取り、
      提供元が文字列の代わりに配列を送れるようにする（`sendPrompt` に並ぶ入口）
- [x] 3. エージェントループの YAML が、使うプロンプト構築のサブワークフローを自分で指定する（最上位の `promptWorkflow: <file>`。
      ループを読み込むときに設定し、書かないループは既定に戻す。存在しないファイルを書いたループは断り、前のループに戻す。
      `plain-text-loop.yaml` を追加。`doc-retrieval-loop.yaml` には書かない：ベンチマークの型 3 はループだけを替えて測るため）。以下は元の記述:（`constructPrompt(ファイル名)` のような操作）。
      別に選ぶ `POST .../prompt-workflow` は、ループの指定が無いときの既定として残す
- [x] 4. request messagesに残す内容もループが決める: 既定は今のまま（ターン終了時に利用者の文と最終回答だけにする）。
      送った文をそのまま残すループを作れるようにする
- [x] 5. 用途別のループを作る: ① 既定（いまと同じ）② 文章生成用（利用者の文だけ、ツール説明なし）
      ③ 文書検索（`doc-retrieval-loop`）
- [ ] 6. テスト: 既定のループが送る文が変更前と一致する／文章生成用ループが送る messages が利用者の文だけである／
      ループがrequest messagesを残す内容を変えられる／`ToolSet` の拒否が効く。`mvn test` を回す
- [x] 7. 実機: 28010 に反映して `chat-06` で確かめた。既定は前置き 13,966 字（変更前と同じ）、plain は 32 字（利用者の文だけ）、plain の会話の概念リストは 5 個（以前は 1 個）。
      `ONLY` に「各文は句点（。）で終える」を足すと、22 件すべてが句点で終わる（足す前は 1/22）。概念リストにも句点が付くので、リストの質問は別の指示にする
- [ ] 8. 設計文書を doc_SCIVICS003 に書く（`ProjectProperty`・`RequestMessagesControl` と同じ置き場）

順序: 1 → 2 → 3 の順に、各段階でテストを通し、段階ごとにコミットする（push は毎回確認）。

# 残りの作業（全部やる。2026-10-20 承認）

順序は、小さくて土台になるものから。各項目をテストして、項目ごとにコミットする（push は区切りで確認）。

- [x] A. 選べるエージェントループの一覧（`AgentLoopCatalog`、Agent Loop タブに「Agent loop:」の選択肢。ブラウザでの動作確認は w206-e2e-test のシナリオで別に足す）: `GET .../chats/{c}/agent-loops`（名前・題・説明・指定しているプロンプト構築・今のループか）。
      選ぶ操作は既存の `POST .../agent-loop-workflow` のまま（ベンチマークが使うため名前を変えない）。
      `.../workflows` は「この会話が今使っているワークフローを読む画面」専用で、名前はそのまま。画面（Agent Loop タブ）に選択肢を足す
- [x] B. 引く操作が作らない（`IoLogStore.sessionIdOf`/`jobSessionIdOf` を足し、引くだけのテストを替えた。`ensure*` は「開く」書き込みとして残す。`hasChat` は登録簿に問い合わせる）。元の記述: `IoLogStore.ensureSession`/`ensureJobSession` を、引くもの（作らない）と作るものに分ける。
      `ChatUiActorSystem.hasChat` は、アプリ側の `chats` ではなく `ActorSystem` の登録簿に問い合わせる
- [x] C. （plugins 7deb3fa を 4.5.0 として Maven Central に公開し、chat-ui の依存を 4.5.0 に上げた（660263d））`H2LogStore` を素の同期 POJO にし（plugins）、`chat-ui-with-audit-trail` の `IoLogStore` が `ActorRef` で包んで `ask(...).join()` で呼ぶ。
      手書きのスレッド・キュー・ラッチを無くす。`quarkus-chat-ui`・`quarkus-chat-ui3` は固定した版（4.2.0 系）のまま動き、版を上げたときに同期書き込みになる
- [x] D. ワークフローが組んだ `messages`（JSON）を会話が受け取り提供元が送る。方針は案1（ターンのために組んだ system メッセージはそのリクエストだけのもので残らず、残るのは質問と答えだけ）。過去のターンを載せない `plain-text-no-context-loop` と載せる版を用意した。`RequestMessagesPerTurnTest` で確認済み。ワークフローの `result` が JSON に見える文字列だと空になる欠陥が POJO-actor にあり、直した（POJO-actor b98bf13、4.5.0 以降で必要）
- [x] E. （コード・テストは完了：`frame/`（`RuleFile`・`FrameFiller`）、`PlanRunner` の操作 `frameNext`/`framePrompt`/`frameFill`/`frameExpand`/`setChatAgentLoop`、`frame-fill-loop.yaml`、`FrameFillLoopTest`。残り：実機で Qwen により通しで動かし、`rst:verify` で検査する）本題: フレームから文章を生成する Turing Workflow（`rst-agent-loop.yaml` に `:frame`/`:sample` の扱いを足す）。 実機（28010、qwen3.8-flash-next-tensorfold、no-think、project1/chat-07）で通した: job-02 が 19 秒で 3 概念 × 4 空欄 + 1 を埋め、`rst:verify` は 0 violations。入力と結果は `~/works/tmp/frame-live-01/`。内容の誤り（Math.sqrt を二乗の手段に挙げる等）は残る
      ブランクごとに 1 回の呼び出し、request messagesつき、`plain-text-loop` 上で動かす
- [x] F. 文書: 設計文書 `AgentLoopDecidesThePrompt_261020_oo01` を doc_SCIVICS003 の `040_design/030_...` に書いた

# 用語（「履歴」は使わない）

- **request messages**: 次にモデルへ送る `system` / `user` / `assistant` の発言の並び。提供元の `history` で、`get_request_messages` が読み、`set_request_messages` が書く。
- **画面の会話記録**: 画面に出す発言の一覧（`ChatSession.conversationHistory`）。モデルへは送らない。
- **入出力ログ**: `IoLogStore` が H2 に書く記録。ターンごと・ステップごとに、送った文と受けた文が入る。モデルへは送らない。
以下の「request messages」は、1 つ目のことである。

# 本からサンプル集とフレームを作る（FramesAndSamplesFromBooks_261021_oo01）

順序: 規則ファイルを扱う処理をプラグインに移す → 本の規則ファイル化 → お手本の引き方 → フレーム作成。各段でテストしてコミットする。

- [x] G1. `plugin-rst-frame` を Turing-workflow-plugins に作る（4.6.0-SNAPSHOT）。`RuleFile`・`FrameFiller` とそのテストを chat-ui から移し、
      規則ファイルを読み書きする操作をこのプラグインのアクターの操作にする。会話に質問を送る操作（`askWorker` など）は chat-ui の `PlanRunner` に残す。
      `fill-frame.yaml` は、このアクターを作って使う形に書き換え、`FillFrameTest` が通ることで確かめる
- [x] G2. 本を読む部分（素の POJO）: ページ番号と柱を除く、行末で切れた文をつなぐ、ファイルの境目で切れた段落をつなぐ、見出しを節にする、
      コード・数式のまとまりを分ける。Core Java 3 章 p001-010 とアルゴリズムとデータ構造 p101-110 を入力にしたテストで確かめる
- [x] G3. 読み違いの直しの採否（文字の単位で比べ、変わった割合で決める）と、関係の答え（文の番号と関係の名前）から規則を作る部分。テストで確かめる
- [x] G4. `make-sample.yaml`（ジョブのワークフロー、名前は小節 1 つ単位に改めた）。`bookOpen`/`bookNext`/`bookAnswer`/`bookWrite`。偽の書き手で 3 通り（新しい構造・上書きの拒否・同じ構造）を確かめた
- [x] G4b. 既にある構造との照合（`SampleCatalog`、判定の質問 same/new/skip、`also-seen` の 1 行）。お手本 1 本 = 構造 1 種類、先頭に use/source/shape。一覧は `rst-on-lisp/bin/catalog`
- [~] G5. 実機（28010 の complete-messages で qwen3.8-flash-next、質問は 1 つずつ単独）: Core Java 3.1・3.2・3.3.1・3.3.2、アルゴリズムとデータ構造 (c) 計算量 を `~/works/tmp/samples-trial/` に作った。3.1 は `example.lisp` と比べて核とサテライトの区別 29/36、関係名 16/36 が一致。5 つとも「新しい構造」と判定された。
      課題: shape（段落ごとの文の関係の並び）では、長さの違う同種の小節（3.3.1 と 3.3.2）が似て見えない。段落の役割の並びで比べる案を相談する。本来の置き場 `rst-on-lisp/samples/` へはまだ移していない
- [ ] G6. お手本の引き方（`:sample-role`、パラメータ `samples`）を `fill-frame.yaml` に足す
- [~] G7. `SampleToFrame`（プラグイン、テスト済み）。ジョブのワークフロー `make-frame.yaml` はまだ。同じ形の段落の並びのサブフレーム化もまだ

確かめること（G1 の前）: Build Snapshot が plugin-rst-frame の SNAPSHOT を取れるか（取れなければ 4.6.0 のリリースが要る）

# PlanRunner の action をプラグインのアクターへ移す（ActionsOnTheInterpreter_261023_oo01）

順序は C → E → B → D（承認済み）。A（finish・reportFailure・countUp・copyState・checkState・takeNext）はインタプリタに残す。

- [x] C. ファイル: プラグインに `FileGate`、audit-trail に起動時の `files` アクター（`ScopedFiles`、インスタンスの範囲）。`readFile`/`writeFile`/`listFiles` を外した（plugins cf0d398、audit-trail 2a260d2）
- [x] E. フレーム関係の 23 action を plugin-rst-frame のジョブのアクター 7 種へ（`tree`・`slots`・`consolidation`・`bookFiles`・`sample`・`frame`・`filler`）。ワークフロー 7 本は最初に `loader.createChild`、終わりと失敗の経路で `removeChild`（plugins 5c55974、audit-trail 073da9b）
- [x] B. 文字列操作 5 つを新しいプラグイン `plugin-text` の `texts` へ。状態を持たないので起動時に 1 つ登録し、ワークフローは作らない（plugins b90797a・9979425、audit-trail 487cdb6・ab5b655）。`PolishFilesWorkflowTest` で polish-files を通しで確かめる
- [x] D. 会話を操作する action 13 個を起動時に登録する `chats` アクター（`Chats`）へ。案a: 作業枠を無くし、ワークフローは役の会話を `chat-<役>`、最後の答えを `reply-<役>` に置く。`askChat` は消した。複数の会話への同時の質問は `askAll`（c2c63ab）

## 解決済み: ジョブのアクターの名前がインスタンス全体で 1 つ（Turing-workflow 4.6.0 の `./名前`、POJO-actor 4.6.0 は使われている名前の登録を拒む）

E のアクター（`tree` など）は固定の名前で作る。`createChild` は同じ名前の既存アクターを黙って置き換えるので、2 つのプロジェクトで同じ種類のジョブが同時に走ると、後のジョブのアクターが先のジョブのものを置き換え、先のジョブの `removeChild` が後のジョブのアクターを消す。
B ではこれが polish 系（普段使うジョブ）に起きたので、`texts` を状態のないアクターにして解消した。E のアクターは状態（質問の進み具合）を持つので、この手が使えない。

案: Turing-workflow の名前解決を「実行中のアクターの子を先に探す」に変え、`createChild` の親に実行中のアクター（`jexl:self`）を渡すと名前が `<plan>.<name>` になるようにする。ワークフローの `actor: tree` は書き換えずに済む。ジョブが止められたときは Project が runner の子を片付ける。土台ライブラリの変更なので確認してから。

## D の設計の選択

1. 会話を指す操作（`setChatModel`・`setChatAgentLoop`・`setChatNoThink`・`setChatEffort`・`setChatProvider`・`stopChat`・`endConversation`）は状態を持たない。`texts` と同じく起動時に 1 つ登録する `chats` アクターにできる。
2. 作業枠の操作（`addWorker`・`askWorker`・`clearWorker`・`keepWorkerReply`・`collectWorkerReplies`）は、枠が計画（PlanRunner）の子で、問い合わせの待ち手の名前（CallWatchdog が循環を見つけるのに使う）が計画の名前である。
   - 案 a: `chats.ask {chat, prompt, waiter: 'jexl:self'}` が答えを message で返し、ワークフローは `putJson` で受ける。枠を無くす。`keepWorkerReply` は不要になる。`apply` で複数の枠に同じ質問を配る `parallel-workers-plan.yaml` は書き直しが要る。
   - 案 b: 枠を残し、Project がジョブごとに `workers` を runner の子として作る。名前の問題（上）と同じ問題を持つ。
   どちらも、LLM がジョブのワークフローを書くときに見る action の一覧（`ActionCatalog`）の書き直しを伴う（polish 系だけで呼び出し 160 箇所）。

---

# スラッシュコマンド: CLI の組み込みコマンド(/compact など)を通す

## 経緯
`/compact` を入力すると `Unknown command: /compact` になる。`plugin-harness` の `SlashCommandHandler` が、
`/` で始まる行を全部自分で受け、5 つ(`/model` `/effort` `/clear` `/session` `/help`)以外を誤りとして返すため。
`quarkus-chat-ui` の `provider-claude-code` の同名クラスも同じ 5 つだが、`quarkus-chat-ui` は画面(app.js)が
5 つ以外を通常のプロンプトとして backend に送り、CLI(tmux)が実行する。

## 実機で分かったこと(claude 2.1.295)
- `--output-format stream-json --input-format stream-json` の常駐プロセスは、`/compact` `/context` `/cost` `/usage` を
  モデルに送らず CLI の中で処理する(結果は `<synthetic>` の assistant 行、ターン 0・費用 0)。
- `/compact` は `system status compacting` → `compact_boundary` → `user`(要約、content が文字列)
  → `user`(`<local-command-stdout>Compacted </local-command-stdout>`)→ `result`(本文は空)の順に返す。
  文脈は 22.3k から 17.3k トークンに減った。
- CLI が知らない `/foo` はモデルに渡り、散文で答える。
- この UI はプロンプトの前に前置き(PREFACE)とツール一覧を付けるので、キューに入れると行頭が `/` でなくなる。
  したがって CLI の組み込みコマンドは、前置きなしの 1 行として provider が直接 CLI に送る必要がある。

## 手順
- [x] 1. `SlashCommandHandler`: この UI 自身のコマンド(`isLocalCommand`)とそれ以外を分ける。Javadoc の「CLI のスラッシュコマンドは使えない」を直す
- [x] 2. `ClaudeCodeProvider.handleCommand`: 自分のコマンド以外は、前置きなしで CLI に送り、答えを info イベントにして返す。ターン実行中は断る
- [x] 3. `StreamEventParser.parseUser`: 文字列 content の `user` 行。`<local-command-stdout>` は本文を表示し、それ以外(要約)は捨てる
- [x] 4. `ChatResource.runProviderCommand`: タイムアウトを 30 秒から 10 分に(`/compact` は長い)
- [x] 5. 単体テストを足し、`rm -rf target && mvn test`(`plugin-harness` と `app`)
- [x] 6. 設計文書 `SlashCommandsAndClearButton_260929_oo01` を直す
- [x] 7. デプロイは行わない(稼働中の 28014 に触れない)

## Review
- `plugin-harness` 41 件、`app` は test-compile まで通った。実物の `claude` に `ClaudeCodeProvider` から
  `/context` `/compact` `/context` `/model` `/nosuchcommand` を順に渡し、答えが 1 回ずつ表示されることを確かめた
  (`/tmp` の使い捨ての `Probe`、稼働中の UI には触れていない)。
- 実機の確認で、答えが 2 回出る誤り(`sawAssistant` の旗を行ごとに作り直していた)と、`/compact` の前に
  古い `/context` の表が混じる件が見つかり、直した。
- 画面(app.js)は触っていない。`/` で始まる行は従来どおりサーバが受ける。
- 動作中の 28014 には反映していない。反映には `app` と `plugin-harness` の jar を作り直して置き換える必要がある。
- CLI のコマンドは I/O ログに残す(ラベル `command/<時刻>`、`REQUEST:`/`RESPONSE:`/`ERROR:`。`oneshot/<時刻>` と同じ置き方)。この製品のコマンドは設定を変えるだけなので残さない。`app` 404 件、`plugin-harness` 42 件が通った。

# Timekeeper view in the actor tree (TimekeeperViewInTheActorTree_261115_oo01)

Clicking a conversation's `.timekeeper` actor in the left-pane tree shows what that timekeeper is watching in the
centre pane; the right pane has nothing for it. Read-only.

## Plan
- [x] Read how the Project click is wired (`switchPerspective`, `data-perspective` right-pane menu, `showConversation`) and reuse it
- [x] Read the design docs 120, 140, 170
- [x] Test first: `TimekeeperWatchTest` (red: did not compile, `Timekeeper.summarize` did not exist)
- [x] `Timekeeper.summarize` / `watch`; `PromisedReports.Pending.number`; `ChatUiActorSystem.timekeeperWatch`; `GET .../chats/{chatId}/timekeeper`
- [x] Third perspective `timekeeper` in console.html / console.css / console.js
- [x] mvn test on W206 (own Job), no `-DskipTests`, `rm -rf target` first
- [x] Real browser against a throwaway instance (28977) with a scripted model server: `e2e/TimekeeperViewCheck.java`
- [x] Docs: new `120_TimekeeperViewInTheActorTree_261115_oo01`, pointer in `ProjectPerspective_260911_oo01`

## Review
- W206 Job: `mvn test` 623 tests, 0 failures (`TimekeeperWatchTest` 9). Red run before the code: compile error on the missing API.
- Browser: 14 checks pass on the jar built on W206 (screenshots `/tmp/uitest/timekeeper-chat0*.png`).
- Found only in the browser: `console.html` is a Qute template, so `{p}` in an HTML comment made `/` answer 500. Comments there must not contain braces.
- Not verified: a real model; a promised report that is already due and waiting for the next 30 s check; the pause actually happening (the view only shows the countdown).

## Review (stage 12, 2026-11-22)

### 1. Full test run (W206 Job `chat77-s12`, on `038b67f`)

`rm -rf target */target`, `mvn -B test` and `mvn -B package`: BUILD SUCCESS.

| Module | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `plugin-api` | 0 | 0 | 0 | 0 |
| `agent-connector-kernel` | 103 | 0 | 0 | 0 |
| `app` | 792 | 0 | 0 | 0 |
| `plugin-web-tools` | 17 | 0 | 0 | 0 |
| `plugin-shell` | 11 | 0 | 0 | 0 |
| `plugin-harness` | 59 | 0 | 0 | 0 |
| `ssh-tools` | 28 | 0 | 0 | 0 |
| `ssh-connector` | 8 | 0 | 0 | 0 |
| **Total** | **1018** | 0 | 0 | 0 |

The same as after stage 11 (1018 = 982 + 28 + 8). This run also covers the `SecureRandom` change of step 4 in every module.

### 2. The E2E checks of stage 6, on this build's jar

`McpServerCheck` 19 of 19, `ToolCallingCheck` 9 of 9, `ExternalMcpCheck` 8 of 8, `ExternalMcpCheck --restored` 1 of 1: ALL PASSED. No port was busy.

### 3. The ssh-connector E2E on 5.14

This build's `ssh-connector` jar (`0e920d02…`) was copied with rsync and checksums compared. The run used a throw-away sshd container, dummy keys only, and nice 19.
- `SshConnectorE2E`: ALL PASSED, 21 of 21: every tool, the host-key change, and the confined OpenCode run calling `submit_job` then `job_status`.
- `ConfinementCheck` in `ssh-connector` mode: ALL PASSED, 18 of 18 (the 16 checks and the two for the ssh key).
- Afterwards:
  - both images were removed (`podman rmi`, then `podman image prune -f`);
  - `podman ps -a`: none; `podman images -a`: only `docker.io/library/ubuntu:24.04` (chat-01's);
  - `~/.ssh`: 36 entries, as before, and no marker;
  - no key file left.

### 4. Diff against `main` (`git diff --stat main...nigsc-connector-split`)

132 files, +8867 −507, in 32 commits.

| Module | Files | + | − | What |
|---|---|---|---|---|
| `agent-connector-kernel` (new) | 62 | 2901 | 0 | The plain tools, docs sources and `PluginRegistry` moved from `app` byte for byte, except `DocsSource`, whose `choose` went to `app`'s `DocsSources` (stage 3). Also: `ToolRunner` and `ToolCatalog`'s core part; the MCP server on Quarkiverse (`KernelMcpServer`, `McpScopes`, `McpKeyMechanism`, `McpAccess`, `McpScopeToolFilter`); the domain-socket guard; the LLM proxy on `llm-relay-proxy`; the Podman launchers and harness configurations; `HarnessLaunch`; the containers. |
| `app` | 13 | 233 | 316 | Depends on the kernel. `ToolCatalog` became `ConversationToolCatalog` (its core part is the kernel's). `ConversationToolRunner` builds on the kernel's `ToolRunner`. `ChatUiMcpScopes` replaces `ChatUiMcpServer` and `ConversationToolFilter`. `application.properties` sets `require-key=false` and the old scope header. Callers were renamed in `ChatSession` and `OpenAiCompatProvider`. The tests of the moved classes moved with them. |
| `plugin-harness` | 5 | 148 | 15 | `HarnessLaunch` extracted (stage 7), then moved to the kernel (stage 11). `CliProcess` and `CodexProvider` launch through it. `HarnessLaunchPinTest` and `CliProcessMcpEntryTest`. |
| `ssh-tools` (new) | 17 | 1425 | 0 | Stage 11. |
| `ssh-connector` (new) | 12 | 872 | 0 | Stage 11. |
| `e2e` | 20 | 2137 | 0 | The E2E programs of stages 8–11, the test-only `confinement-host` (step 5), the `traced` image (stage 9 part 3), and the throw-away `sshd` image (stage 11). |
| `pom.xml` | 1 | 21 | 0 | The three new modules, the kernel's and `ssh-tools`' versions, `quarkus-mcp-server-http` 2.0.4 managed once, `llm-relay.version`. |
| `tasks/todo.md` | 1 | 1110 | 0 | This plan. |

**Checked against the plan:**
- I found no change in `app` that the split did not need. Every edit there serves the move (imports, renamed callers, the kernel dependency, the MCP server on the kernel, the two settings). No behaviour of chat-ui changed: 792 `app` tests and the stage 6 E2E checks pass as on `main`.
- Nothing in `plugin-web-tools` or `plugin-shell` changed.

**To flag** (each one was reported when it was made; none is a hidden change):
- `e2e/ConfinementCheck.java` now runs with the ssh-connector jar as its classpath, in both its modes, because it uses `ConnectorLaunch` (stage 11, step 3).
- ssh-connector with no harness also writes `private/llm-key` (stage 11, step 3). The spec did not ask for it; the confinement check needs it, and so does a client the user starts.
- `e2e/confinement-host` is a test-only Quarkus application, outside the reactor (step 5). It could now be replaced by ssh-connector in `ConfinementCheck`'s first mode.

**Outside this diff:**
- `~/works/llm-relay`: its own repository, no remote, commits `1f00ec1` and `6abdef8`.
- `quarkus-gpu-broker`: local branch `llm-relay` (`2d9d336`), not deployed; it waits for the person's approval.
- The local branch `wip/s9p3` (`688ac1e`) is kept.

### Native build, after chat-01's decision to fix it (chat-48, t175)

- **The fix:** `DocSearchTool`'s `HttpClient` and its base URL are now made on first use, in `static volatile` fields guarded by a `synchronized` block, instead of `static final` fields set when the class is initialized.
  - A holder class would not help: Quarkus initializes application classes at build time for a native image, so a holder's initializer would also run during the build.
  - No `--initialize-at-run-time` option was added.
  - In a JVM nothing changes: one client, with the same 10 s connect timeout, reused after the first call; and one base URL (`-Dchatui.docsearch.url`, else `$DOCSEARCH_URL`, else `http://localhost:28001`).
  - The base URL was made lazy as well: in a native image its static initializer would have frozen the build machine's value into the executable.
- **Tests first:** the new `DocSearchToolClientTest` (2 tests: the client is reused and has the 10 s connect timeout; the base URL is resolved once). Against the unchanged code it failed to compile ("cannot find symbol: method httpClient()"). It passed after the change; `agent-connector-kernel` now has 105 tests.
- **The native build now succeeds** (W206 Job `chat77-s12b`; `rm -rf target */target`, then `mvn -B package -Dnative -pl ssh-connector -am`, GraalVM CE 25+37.1). It produced `ssh-connector-3.0.0-SNAPSHOT`, a 61.7 MB executable. No other static-init finding came up.
- **The executable does not serve: stopped here, a design question.** Run with no harness for `SshConnectorCheck`, it registered its 7 tools and then failed to start. The cause: `Unable to start HTTP server` ← `Unable to bind to Unix domain socket. Consider adding the 'io.netty:netty-transport-native-epoll' dependency`. The event loop was Netty's `NioEventLoop`, not epoll.
  - The dependency is in the image (`netty-transport-native-epoll-4.1.139.Final-linux-x86_64.jar`).
  - The Quarkus Vert.x reference, section "Use native transports", says: "Native transports are not supported in native executables."
  - ssh-connector serves only on the domain socket (Decision 13), and the domain socket needs a native transport. So a native ssh-connector cannot serve as designed.
  - Making it work needs a design decision, for example a TCP port on 127.0.0.1 in the native build, which is what Decision 13 replaced. I did not change anything. `SshConnectorCheck` therefore did not pass against the native executable.

### Open

- **Native image:** it builds, but it cannot listen on the domain socket (above). The decision goes to chat-48 and chat-01.
- **Real-model runs waiting for keys:** Codex CLI (OpenAI) and Claude Code (Anthropic).
- **Proposed doc changes, for chat-48 to decide** (not written):
  - `ProviderAndToolPlugins_260912_oo01`: its module table gains `agent-connector-kernel` (the tools, `ToolRunner`, the MCP server, the LLM proxy, the Podman launchers, `HarnessLaunch`, `PluginRegistry`), `ssh-tools` and `ssh-connector`. `plugin-harness` now depends on the kernel as `provided`.
  - A new 040_design document for the split: why the headless part is a library of CDI beans; the one domain socket with the MCP server and the proxy at `/llm/<scope>/v1`; Decision 13's `require-key`; what each product sets (chat-ui `false` with `X-Chat-Ui-Conversation`, ssh-connector `true`); and the module dependency graph.
- No push and no deploy was done.


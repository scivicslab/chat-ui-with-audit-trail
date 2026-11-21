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

- chat-ui's Quarkus `/mcp` takes no key, so when quarkus-chat-ui later launches a confined harness, it must switch to the key-checking `McpToolEndpoint` path (with `ToolScopes`) instead of its own map. Accepted deviation in stage 6 (`9c4b530`), chat-01, 2026-11-21.

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
- [x] 7. **`HarnessLaunch` in `plugin-harness`.**
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
  - The ssh tools themselves are **to be specified before the stage starts**; chat-48 will send that spec.
  - Check: an E2E program with `main()`, outside the Maven build (`TestingStandard_260404_oo01`), run on a throw-away instance in a W206 Job. Plus full `rm -rf target`, `mvn test` with the counts against stage 1, and the E2E checks of stage 6, which show chat-ui is unchanged.
  - It uses the LLM proxy and the confinement of stage 8.
- [ ] 12. **Wrap-up.**
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
2. **One MCP server, from the library.**
   - Tests first: key per conversation; the same 401 and body for an unknown name and a wrong key; 400 for a request that names no conversation; only the conversation's tools in `tools/list`, and a `tools/call` outside them refused; `initialize` answers with a complete `serverInfo`.
   - Then the kernel's beans: `ToolManager` registration, `ToolFilter`, `HttpAuthenticationMechanism` and the `/mcp/*` permission, with `app`'s `ChatUiMcpServer` and `ConversationToolFilter` moved in.
   - `LocalMcpServer` and `McpRequestHandler` deleted, and the `McpServerCheck`, `ToolCallingCheck` and `ExternalMcpCheck` E2E checks pass against `app`.
3. **Unix domain socket.**
   - `quarkus.http.domain-socket` set by the launcher's process, and `UnixSocketHttpServer` deleted.
   - A test checks the socket's 0600 and the directory's 0700.
   - The relay maps both container ports to the one socket, and the harness configs take `/llm/<scope>/v1`. Brought over from `wip/s9p3`: the network-off settings and their tests.
4. **LLM proxy.** On the Quarkus router, as chat-48 chooses under point 5. `LlmProxyTest` and `LlmProxyUnixSocketTest` are rewritten against it, red first. `LlmProxyRequestsTest` is unchanged.
5. **Confinement checks again on 5.14** (part 2's 16 checks, then part 3's stub runs, traced and plain), with the network-off settings.
6. **Real-model runs** (t135:r1), still on hold until step 5 passes: OpenCode with GLM-5.3, OpenCode with Qwen3.8 (its tool-call probe passed on 2026-11-22, `finish_reason: "tool_calls"`), Hermes Agent with GLM-5.3, and Codex CLI with GLM-5.3.
   - Codex's config reference (https://learn.chatgpt.com/codex/config-file/config-reference) allows a custom provider, `model_providers.<id>.base_url`, but "responses is the only supported value" of `wire_api`. So the model server must serve the Responses API, and Codex CLI with GLM-5.3 runs only if 5.16 answers `/v1/responses`.
   - Claude Code waits for an Anthropic key.
7. **`ssh-connector` as a Quarkus application** (stage 11, after chat-48's spec): its own module with `quarkus-maven-plugin`, the native image built with `-Dnative` in a W206 Job (GraalVM CE).
8. **Wrap-up** (stage 12).

### Open questions for chat-48

1. **Point 5:** Vert.x HTTP Proxy (recommended) or a hand-written route on Quarkus's router.
2. **`app`'s `/mcp` gets keys when it becomes the kernel's server.** `0ac86a4` planned that switch for later. If it happens in step 2, `app`'s own Claude Code and Codex conversations must send their scope's key in their MCP config. The other way is that the security check applies only to scopes registered with a key.
3. **One socket for both endpoints,** with the proxy at `/llm/<scope>/v1`, as point 4 leads to.

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

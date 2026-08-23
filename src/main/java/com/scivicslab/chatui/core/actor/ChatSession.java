package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.iolog.IoLogStore;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.chatui.core.service.AuthMode;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POJO owning the entire state of one conversation tab's chat session.
 *
 * <p>Extends {@link Interpreter} so that, once it loads its own agent-loop workflow
 * (see {@code ChatSessionAgentLoop_260823_oo01}), it can drive a multi-turn tool-calling
 * loop through the same state-machine mechanism a Turing workflow uses for any sub-workflow.
 * This class (stage 1) does not yet load or run such a workflow — it only ports the
 * single-turn prompt/response lifecycle ({@code ChatSessionPorting_260823_oo01}'s 2-a/2-c).</p>
 *
 * <p>All fields are plain (no volatile / synchronized) — thread safety is guaranteed
 * by the actor's sequential message processing once this object is wrapped by
 * {@code ChatSessionIIAR}.</p>
 *
 * <p>Unlike the reference {@code ChatActor} this is ported from, this class holds no
 * {@code ActorRef}-typed fields for its collaborators (provider/watchdog/promptQueue).
 * It holds only their names and looks them up via {@code system.getActor(name)} at the
 * point of use — see {@code ChatSessionPorting_260823_oo01} "How to do it".</p>
 */
public class ChatSession extends Interpreter {

    private static final Logger logger = Logger.getLogger(ChatSession.class.getName());
    private static final int MAX_HISTORY = 200;
    private static final int LOG_BUFFER_SIZE = 500;

    private final LlmProvider provider;
    private final AuthMode authMode;

    /** Complete I/O log (Sessions tab). May be null (logging disabled / not yet ported). */
    private final IoLogStore ioLog;
    /** Conversation turn counter, used to label I/O-log entries ({@code turn<n>/step1/llm}). */
    private int ioTurn = 0;

    /** Name of the {@code provider} child actor. Created and set once by the generating side. */
    private String providerName;
    /**
     * Name of the sibling StallMonitor, or {@code null}. Always null for the openai-compat
     * provider this class currently targets — StallMonitor is not yet ported
     * ({@code ChatSessionPorting_260823_oo01} Under the Hood).
     */
    private String watchdogName;
    /** Name of the sibling PromptQueue, or {@code null} until wired. */
    private String promptQueueName;

    private boolean busy;
    private String apiKey;
    private final LinkedList<HistoryEntry> conversationHistory = new LinkedList<>();

    private final ChatEvent[] logBuffer = new ChatEvent[LOG_BUFFER_SIZE];
    private int logHead = 0;
    private int logCount = 0;
    private Consumer<ChatEvent> sseEmitter;

    // ---- MCP result accumulation ----
    // Keyed by UUID assigned at submitPrompt time. LRU-evicts oldest when >50 entries.
    private final Map<String, String> completedResults = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 50;
        }
    };
    // UUIDs that have been registered (via submitPrompt) but not yet started processing
    private final Set<String> pendingResultKeys = new HashSet<>();
    // UUID of the prompt currently being processed, or null
    private String activeResultKey;

    /**
     * Convenience constructor without the I/O log (used by tests).
     *
     * @param provider     the LLM provider implementation to delegate prompts to
     * @param configApiKey optional API key supplied via application configuration
     */
    public ChatSession(LlmProvider provider, Optional<String> configApiKey) {
        this(provider, configApiKey, null);
    }

    /**
     * Creates a new ChatSession bound to the given LLM provider.
     *
     * <p>Determines the authentication mode by checking (in order):
     * CLI capability, environment variable, and config property.</p>
     *
     * @param provider     the LLM provider implementation to delegate prompts to
     * @param configApiKey optional API key supplied via application configuration
     * @param ioLog        store for the complete I/O log (Sessions tab), or {@code null} to disable logging
     */
    public ChatSession(LlmProvider provider, Optional<String> configApiKey, IoLogStore ioLog) {
        this.provider = provider;
        this.ioLog = ioLog;

        if (provider.capabilities().supportsWatchdog()) {
            // CLI-based provider: no API key needed, CLI binary handles auth
            this.authMode = AuthMode.CLI;
            this.apiKey = null;
            logger.info("Provider: " + provider.displayName() + " (CLI mode)");
        } else {
            // HTTP-based provider: needs API key
            String envKey = provider.detectEnvApiKey();
            if (envKey != null && !envKey.isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = envKey;
                logger.info("Provider: " + provider.displayName() + " (API key from environment)");
            } else if (configApiKey.isPresent() && !configApiKey.get().isBlank()) {
                this.authMode = AuthMode.API_KEY;
                this.apiKey = configApiKey.get();
                logger.info("Provider: " + provider.displayName() + " (API key from config)");
            } else {
                this.authMode = AuthMode.NONE;
                this.apiKey = null;
                logger.info("Provider: " + provider.displayName() + " (no API key — must be set via Web UI)");
            }
        }
    }

    // ---- Wiring — the generating side (ConversationTab) sets these; ChatSession creates none of them ----

    /**
     * Makes {@code system.getActor(...)} resolvable from inside this session's own methods.
     * The generating side sets this once, right after {@code createChild}.
     *
     * @param system the actor system this session's ActorRef is registered in
     */
    public void setActorSystem(IIActorSystem system) { this.system = system; }

    /**
     * @param providerName the name of the {@code provider} child actor, created by the generating side
     */
    public void setProviderName(String providerName) { this.providerName = providerName; }

    /** @return the name of the {@code provider} child actor */
    public String getProviderName() { return providerName; }

    /**
     * @param watchdogName the name of the sibling StallMonitor, or {@code null}
     */
    public void setWatchdogName(String watchdogName) { this.watchdogName = watchdogName; }

    /**
     * @param promptQueueName the name of the sibling PromptQueue
     */
    public void setPromptQueueName(String promptQueueName) { this.promptQueueName = promptQueueName; }

    /**
     * Looks up the sibling PromptQueue by name, on demand — not stored as a field.
     *
     * @return the PromptQueue's ActorRef, or {@code null} if not wired
     */
    public ActorRef<PromptQueue> getPromptQueue() {
        return promptQueueName == null ? null : system.getActor(promptQueueName);
    }

    /** Looks up the {@code provider} child's ActorRef by name, on demand — not stored as a field. */
    private ActorRef<LlmProvider> providerRef() {
        return system.getActor(providerName);
    }

    // ---- Authentication ----

    /**
     * Returns the authentication mode determined at construction time.
     *
     * @return the current authentication mode (CLI, API_KEY, or NONE)
     */
    public AuthMode getAuthMode() { return authMode; }

    /**
     * Checks whether this actor has sufficient credentials to send prompts.
     *
     * <p>Returns {@code true} when the provider uses CLI auth, or when an API key
     * has been supplied via environment, config, or the Web UI.</p>
     *
     * @return {@code true} if the actor is ready to authenticate with the provider
     */
    public boolean isAuthenticated() {
        return authMode == AuthMode.CLI
                || authMode == AuthMode.NONE
                || (authMode == AuthMode.API_KEY && apiKey != null);
    }

    /**
     * Sets the API key, typically called when a user provides one through the Web UI.
     *
     * @param key the API key to store
     */
    public void setApiKey(String key) {
        this.apiKey = key;
        logger.info("API key set via Web UI");
    }

    /**
     * Returns the currently stored API key, or {@code null} if none is set.
     *
     * @return the API key, or {@code null}
     */
    public String getApiKey() { return apiKey; }

    // ---- Provider delegation (cheap synchronous reads — call the plain `provider` field directly) ----

    /**
     * Returns whether a prompt is currently being processed.
     *
     * @return {@code true} if the actor is busy with an LLM request
     */
    public boolean isBusy() { return busy; }

    /**
     * Returns the model identifier currently selected by the provider.
     *
     * @return the active model name
     */
    public String getModel() { return provider.getCurrentModel(); }

    /**
     * Returns the current provider session identifier, or {@code null} if no session is active.
     *
     * @return the session ID
     */
    public String getSessionId() { return provider.getSessionId(); }

    /**
     * Tests whether the given input string is a provider command (e.g. slash command).
     *
     * @param input the user input to check
     * @return {@code true} if the provider recognises this as a command
     */
    public boolean isCommand(String input) { return provider.isCommand(input); }

    /**
     * Returns the list of models available from the current provider.
     *
     * @return an unmodifiable list of model entries
     */
    public List<LlmProvider.ModelEntry> getAvailableModels() { return provider.getAvailableModels(); }

    /**
     * Delegates a slash command to the provider and returns the resulting events.
     *
     * <p>If the command is {@code /clear}, the conversation history is also cleared.
     * A status event is always appended to the response list.</p>
     *
     * @param input the raw command string entered by the user
     * @return a list of {@link ChatEvent}s produced by the command
     */
    public List<ChatEvent> handleCommand(String input) {
        List<ChatEvent> responses = new ArrayList<>(provider.handleCommand(input));
        if (input.trim().toLowerCase().startsWith("/clear")) {
            conversationHistory.clear();
        }
        responses.add(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), busy));
        return responses;
    }

    // ---- Chat lifecycle ----

    /**
     * Begins an asynchronous prompt. Dispatches blocking LLM I/O onto the managed thread pool,
     * returning immediately so the actor can process other messages (cancel, log, etc.) while
     * the request is in flight.
     *
     * <p>Convenience overload with no {@code resultKey} (human-typed prompts) and
     * {@code noThink} left at its default ({@code false}).</p>
     *
     * @param prompt the prompt text to send to the LLM
     * @param model  the model to use, or {@code null}/blank to keep the provider's current model
     * @param emitter callback that receives {@link ChatEvent}s as the response streams in
     * @param self   this actor's own reference, used to queue completion back onto the actor thread
     * @param done   completed once the prompt has finished processing (success or error)
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatSession> self, CompletableFuture<Void> done) {
        startPrompt(prompt, model, emitter, self, done, null, false);
    }

    /**
     * Begins an asynchronous prompt with MCP result accumulation.
     *
     * <p>Convenience overload with {@code noThink} left at its default ({@code false}).</p>
     *
     * @param prompt    the prompt text to send to the LLM
     * @param model     the model to use, or {@code null}/blank to keep the provider's current model
     * @param emitter   callback that receives {@link ChatEvent}s as the response streams in
     * @param self      this actor's own reference, used to queue completion back onto the actor thread
     * @param done      completed once the prompt has finished processing (success or error)
     * @param resultKey UUID under which the accumulated response is stored for later retrieval via
     *                  {@link #getCompletedResult(String)}, or {@code null} for human-typed prompts
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatSession> self, CompletableFuture<Void> done,
                            String resultKey) {
        startPrompt(prompt, model, emitter, self, done, resultKey, false);
    }

    /**
     * Begins an asynchronous prompt with optional result accumulation.
     *
     * <p>When {@code resultKey} is non-null (MCP-submitted prompts), the full assistant
     * response text is accumulated and stored in {@code completedResults} under that key
     * so that {@link #getCompletedResult(String)} can return it after completion.</p>
     *
     * @param prompt    the prompt text to send to the LLM
     * @param model     the model to use, or {@code null}/blank to keep the provider's current model
     * @param emitter   callback that receives {@link ChatEvent}s as the response streams in
     * @param self      this actor's own reference, used to queue completion back onto the actor thread
     * @param done      completed once the prompt has finished processing (success or error)
     * @param resultKey UUID under which the accumulated response is stored for later retrieval via
     *                  {@link #getCompletedResult(String)}, or {@code null} for human-typed prompts
     * @param noThink   whether to ask the provider to skip its reasoning/thinking phase
     */
    public void startPrompt(String prompt, String model, Consumer<ChatEvent> emitter,
                            ActorRef<ChatSession> self, CompletableFuture<Void> done,
                            String resultKey, boolean noThink) {
        if (busy) {
            emitter.accept(ChatEvent.error("Already processing a prompt. Please wait or cancel."));
            done.complete(null);
            return;
        }
        if (!isAuthenticated()) {
            emitter.accept(ChatEvent.error(
                    "No authentication configured. Please provide an API key."));
            done.complete(null);
            return;
        }

        busy = true;
        recordHistory("user", prompt);
        // Open (lazily) the conversation's I/O-log session and number this turn for the Sessions tab.
        final long ioSession = (ioLog != null) ? ioLog.ensureSession() : -1;
        final int ioTurnNo = ++ioTurn;
        if (resultKey != null) {
            pendingResultKeys.remove(resultKey);
            activeResultKey = resultKey;
        }
        // StallMonitor is not yet ported (ChatSessionPorting_260823_oo01 Under the Hood);
        // watchdogName is always null for the openai-compat provider this class targets.
        boolean useWatchdog = false;

        final String snapApiKey = apiKey;
        ActorRef<LlmProvider> providerRef = providerRef();

        // Delegate blocking I/O to the provider child actor on the managed thread pool
        // (real threads). Actor message loops run on virtual threads, so long-running
        // blocking I/O must be dispatched to the managed pool.
        // This actor returns immediately and remains free for other messages (cancel, log).
        // whenComplete() queues onPromptComplete() back onto this actor when done.
        providerRef.ask(p -> {
            try {
                if (model != null && !model.isBlank()) p.setModel(model);

                Runnable heartbeat = () -> {};

                ProviderContext ctx = new ProviderContext(snapApiKey, List.of(), noThink, heartbeat);

                // Wrap emitter to intercept assistant content for history and optional result capture
                StringBuilder assistantBuf = new StringBuilder();
                StringBuilder thinkingBuf = new StringBuilder();
                StringBuilder resultBuf = (resultKey != null) ? new StringBuilder() : null;
                Consumer<ChatEvent> wrappedEmitter = event -> {
                    if ("delta".equals(event.type()) && event.content() != null) {
                        assistantBuf.append(event.content());
                        if (resultBuf != null) resultBuf.append(event.content());
                    } else if ("thinking".equals(event.type()) && event.content() != null) {
                        thinkingBuf.append(event.content());
                    } else if ("result".equals(event.type())) {
                        if (!assistantBuf.isEmpty()) {
                            String content = assistantBuf.toString();
                            self.tell(b -> b.recordHistory("assistant", content));
                        }
                        if (resultBuf != null) {
                            String captured = resultBuf.toString();
                            self.tell(b -> b.storeCompletedResult(resultKey, captured));
                        }
                        // Persist the completed turn to the I/O log in the Sessions-tab marker format.
                        recordTurnIo(ioSession, ioTurnNo, prompt, assistantBuf.toString(), thinkingBuf.toString());
                    }
                    emitter.accept(event);
                };

                emitter.accept(ChatEvent.status(p.getCurrentModel(), p.getSessionId(), true));
                p.sendPrompt(prompt, p.getCurrentModel(), wrappedEmitter, ctx);

            } catch (Exception e) {
                logger.log(Level.WARNING, "Provider sendPrompt failed", e);
                emitter.accept(ChatEvent.error("Error: " + e.getMessage()));
            }
            return null;
        }, system.getManagedThreadPool())
        .whenComplete((r, ex) -> self.tell(b -> b.onPromptComplete(emitter, done, self)));
    }

    /**
     * Called when LLM processing finishes; queued back onto the actor via {@code self.tell()}.
     *
     * @param emitter callback that receives the final {@link ChatEvent} status update
     * @param done    completed to signal the prompt has finished processing
     * @param self    this actor's own reference, forwarded to {@code PromptQueue} to dispatch the next prompt
     */
    public void onPromptComplete(Consumer<ChatEvent> emitter, CompletableFuture<Void> done, ActorRef<ChatSession> self) {
        busy = false;
        activeResultKey = null;

        ActorRef<PromptQueue> promptQueue = getPromptQueue();
        if (promptQueue != null) {
            promptQueue.tell(q -> q.onPromptComplete(self));
        }

        emitter.accept(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), false));
        done.complete(null);
    }

    // ---- Autonomous turns (idle monitor) ----

    /**
     * Idle-monitor entry point. When the session is idle and the provider has buffered autonomous
     * output — output produced outside a {@code sendPrompt} turn, e.g. a background job the model
     * started finishing — drains it as its own assistant turn: streamed to the browser over SSE and
     * recorded in history, with no preceding user message.
     *
     * <p>Invoked periodically via {@code self.tell} by the scheduler in {@code ChatUiActorSystem}.
     * Following the POJO-actor model, the cheap {@code hasAutonomousActivity()} check runs here on
     * the actor thread, while the blocking drain is delegated to the managed thread pool so the
     * actor's message loop stays responsive.</p>
     *
     * @param self this actor's own reference, used to queue completion back onto the actor thread
     */
    public void pollAutonomousActivity(ActorRef<ChatSession> self) {
        if (busy || providerName == null) return;
        if (!provider.supportsAutonomousEvents() || !provider.hasAutonomousActivity()) return;

        // Reserve the session so a user prompt cannot start while the autonomous turn streams.
        busy = true;
        emitToSse(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), true));
        final long ioSession = (ioLog != null) ? ioLog.ensureSession() : -1;
        ActorRef<LlmProvider> providerRef = providerRef();

        providerRef.ask(p -> {
            StringBuilder assistantBuf = new StringBuilder();
            StringBuilder thinkingBuf = new StringBuilder();
            Consumer<ChatEvent> wrapped = event -> {
                if ("delta".equals(event.type()) && event.content() != null) {
                    assistantBuf.append(event.content());
                } else if ("thinking".equals(event.type()) && event.content() != null) {
                    thinkingBuf.append(event.content());
                } else if ("result".equals(event.type())) {
                    String content = assistantBuf.toString();
                    String thinking = thinkingBuf.toString();
                    if (!content.isBlank()) {
                        self.tell(b -> b.recordAutonomousTurn(ioSession, content, thinking));
                    }
                }
                emitToSse(event);
            };
            return p.drainAutonomousActivity(wrapped);
        }, system.getManagedThreadPool())
        .whenComplete((happened, ex) -> self.tell(b -> b.onAutonomousComplete(self)));
    }

    /**
     * Called when an autonomous drain finishes; queued back onto the actor via {@code self.tell}.
     *
     * @param self this actor's own reference, forwarded to {@code PromptQueue} to dispatch any queued prompt
     */
    public void onAutonomousComplete(ActorRef<ChatSession> self) {
        busy = false;
        // A user prompt may have queued while we held the session — let the queue dispatch it now.
        ActorRef<PromptQueue> promptQueue = getPromptQueue();
        if (promptQueue != null) promptQueue.tell(q -> q.onPromptComplete(self));
        emitToSse(ChatEvent.status(provider.getCurrentModel(), provider.getSessionId(), false));
    }

    /**
     * Records a completed autonomous turn (one with no user prompt) into conversation history and
     * the I/O log. Runs on the actor thread so {@code ioTurn} is mutated safely.
     *
     * @param ioSession the open I/O-log session id, or negative when logging is disabled
     * @param assistant the accumulated assistant text
     * @param thinking  the accumulated reasoning text
     */
    public void recordAutonomousTurn(long ioSession, String assistant, String thinking) {
        recordHistory("assistant", assistant);
        recordTurnIo(ioSession, ++ioTurn, "(autonomous continuation)", assistant, thinking);
    }

    /**
     * Cancels the currently running prompt, if any.
     *
     * <p>Uses {@code tellNow()} to bypass the provider actor's queue so that the
     * cancel signal reaches the provider immediately, even while {@code sendPrompt()}
     * is blocking the queue.</p>
     */
    public void cancel() {
        if (providerName == null) return;
        ActorRef<LlmProvider> providerRef = providerRef();
        if (providerRef != null) providerRef.tellNow(LlmProvider::cancel);
    }

    /**
     * Sends a user response to an interactive prompt identified by {@code promptId}.
     *
     * @param promptId the identifier of the prompt awaiting a response
     * @param response the user's response text
     * @throws IOException if communicating with the provider fails
     */
    public void respond(String promptId, String response) throws IOException {
        provider.respond(promptId, response);
    }

    // ---- History ----

    /**
     * Appends an entry to the conversation history, evicting the oldest entry
     * when the maximum history size is exceeded.
     *
     * <p>Blank or null content is silently ignored.</p>
     *
     * @param role    the message role (e.g. "user" or "assistant")
     * @param content the message text
     */
    public void recordHistory(String role, String content) {
        if (content == null || content.isBlank()) return;
        conversationHistory.addLast(new HistoryEntry(role, content));
        while (conversationHistory.size() > MAX_HISTORY) conversationHistory.removeFirst();
    }

    /**
     * Records one completed Claude turn into the H2 I/O log in the marker format the Sessions tab reads
     * ({@code REQUEST:} = the user prompt as a one-message request, {@code RESPONSE:} = the assistant
     * text, {@code REASONING:} = thinking, {@code USAGE:} = token line). No-op when logging is off.
     */
    private void recordTurnIo(long ioSession, int turnNo, String prompt, String assistant, String thinking) {
        if (ioLog == null || ioSession < 0) return;
        try {
            String requestJson = new org.json.JSONObject()
                    .put("messages", new org.json.JSONArray().put(
                            new org.json.JSONObject().put("role", "user").put("content", prompt)))
                    .toString();
            StringBuilder m = new StringBuilder();
            m.append("REQUEST:\n").append(requestJson);
            m.append("\n\nRESPONSE:\n").append(assistant == null ? "" : assistant);
            if (thinking != null && !thinking.isBlank()) {
                m.append("\n\nREASONING:\n").append(thinking);
            }
            m.append("\n\nUSAGE: promptTokens=0 completionTokens=0");
            ioLog.record(ioSession, "agent", "turn" + turnNo + "/step1/llm", m.toString());
        } catch (Exception e) {
            logger.log(Level.WARNING, "I/O log turn record failed", e);
        }
    }

    /**
     * Returns the most recent conversation history entries, up to the given limit.
     *
     * @param limit the maximum number of entries to return
     * @return an unmodifiable list of the most recent history entries
     */
    public List<HistoryEntry> getHistory(int limit) {
        int size = conversationHistory.size();
        int from = Math.max(0, size - limit);
        return Collections.unmodifiableList(new ArrayList<>(conversationHistory.subList(from, size)));
    }

    /** Removes all entries from the conversation history. */
    public void clearHistory() {
        conversationHistory.clear();
        // New conversation: end the current I/O-log session and renumber turns from 1.
        if (ioLog != null) ioLog.resetSession();
        ioTurn = 0;
    }

    // ---- Log ring buffer ----

    /**
     * Stores a log event in the ring buffer and forwards it to the SSE emitter if connected.
     *
     * @param level      the log level (e.g. "INFO", "WARNING")
     * @param loggerName the name of the originating logger
     * @param message    the log message text
     * @param timestamp  the event timestamp in epoch milliseconds
     */
    public void publishLog(String level, String loggerName, String message, long timestamp) {
        ChatEvent event = ChatEvent.log(level, loggerName, message, timestamp);
        logBuffer[logHead] = event;
        logHead = (logHead + 1) % LOG_BUFFER_SIZE;
        if (logCount < LOG_BUFFER_SIZE) logCount++;
        if (sseEmitter != null) {
            try { sseEmitter.accept(event); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Registers an SSE emitter that receives real-time log events.
     *
     * @param emitter the consumer to receive log events
     */
    public void setSseEmitter(Consumer<ChatEvent> emitter) { this.sseEmitter = emitter; }
    /** Unregisters the current SSE emitter, stopping real-time log forwarding. */
    public void clearSseEmitter() { this.sseEmitter = null; }

    /**
     * Streams a chat event straight to the connected browser via the SSE emitter, without buffering
     * it in the log ring (unlike {@link #emitEvent}). Used for autonomous-turn output, which is chat
     * content rather than a log entry. The emitter ({@code SseConnection::emit}) is safe to call from
     * any thread, so this may be invoked from the managed thread pool during a drain.
     *
     * @param event the chat event to stream
     */
    private void emitToSse(ChatEvent event) {
        Consumer<ChatEvent> e = sseEmitter;
        if (e != null) {
            try { e.accept(event); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Buffers a {@link ChatEvent} in the ring buffer and forwards it to the SSE emitter.
     *
     * <p>Used by the autonomous event monitor to emit events that arrive outside of a
     * user-prompted turn (e.g. ScheduleWakeup responses). Unlike {@link #publishLog}, this
     * method accepts a pre-built {@code ChatEvent} and does not wrap it in a log envelope.</p>
     *
     * @param event the event to buffer and emit
     */
    public void emitEvent(ChatEvent event) {
        logBuffer[logHead] = event;
        logHead = (logHead + 1) % LOG_BUFFER_SIZE;
        if (logCount < LOG_BUFFER_SIZE) logCount++;
        if (sseEmitter != null) {
            try { sseEmitter.accept(event); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Returns the contents of the log ring buffer in chronological order.
     *
     * @return a list of the most recent log events (up to {@code LOG_BUFFER_SIZE})
     */
    public List<ChatEvent> getRecentLogs() {
        List<ChatEvent> result = new ArrayList<>(logCount);
        int start = (logHead - logCount + LOG_BUFFER_SIZE) % LOG_BUFFER_SIZE;
        for (int i = 0; i < logCount; i++) result.add(logBuffer[(start + i) % LOG_BUFFER_SIZE]);
        return result;
    }

    // ---- MCP result tracking ----

    /**
     * Registers a UUID so that getResultStatus() returns "processing" until the prompt completes.
     *
     * @param key the MCP result UUID to register as pending
     */
    public void registerPendingResultKey(String key) {
        pendingResultKeys.add(key);
    }

    /**
     * Stores the accumulated LLM response text for a completed MCP prompt.
     *
     * @param key  the MCP result UUID, previously registered via {@link #registerPendingResultKey(String)}
     * @param text the accumulated assistant response text
     */
    public void storeCompletedResult(String key, String text) {
        pendingResultKeys.remove(key);
        completedResults.put(key, text);
        logger.info("MCP result stored: key=" + key + " length=" + text.length());
    }

    /**
     * Returns the status of an MCP result key: "completed", "processing", or "unknown".
     * "unknown" means the key was never registered with this actor.
     *
     * @param key the MCP result UUID to query
     * @return "completed", "processing", or "unknown"
     */
    public String getResultStatus(String key) {
        if (completedResults.containsKey(key)) return "completed";
        if (pendingResultKeys.contains(key) || key.equals(activeResultKey)) return "processing";
        return "unknown";
    }

    /**
     * Returns the stored LLM response text for the given MCP result key, or null if not found.
     *
     * @param key the MCP result UUID to look up
     * @return the accumulated assistant response text, or {@code null} if not found
     */
    public String getCompletedResult(String key) {
        return completedResults.get(key);
    }

    /**
     * One entry in the conversation history.
     *
     * @param role    the message role (e.g. "user" or "assistant")
     * @param content the message text
     */
    public record HistoryEntry(String role, String content) {}
}

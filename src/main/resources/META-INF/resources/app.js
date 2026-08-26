// Chat pane wiring for chat-ui-with-audit-trail (adapted from quarkus-chat-ui3's app.js).
//   - one persistent EventSource per conversation tab
//   - POST /api/tabs/{tabId}/chat only acknowledges; all content streams over SSE
//   - renders delta/thinking/result/error/status ChatEvents into #chat-area
//   - a tab bar (#conv-tab-bar) lets the user switch which ConversationTab this pane talks to
(function () {
    "use strict";

    var TAB_ID_KEY = "chat-ui-last-tab";
    var TAB_ID = localStorage.getItem(TAB_ID_KEY) || "alpha";

    function apiUrl(path) { return path; }

    var chatArea, promptInput, sendBtn, connStatus, activityLabel, modelSelect, notificationBar;
    var themeSelect, queueBtn, queueArea;
    var convTabList, convTabNewBtn;
    var eventSource = null;
    var streamingEl = null;   // the live assistant bubble currently receiving deltas
    var thinkingEl = null;    // the live "thinking" trace bubble, if any
    var busy = false;

    // Enable KaTeX math rendering inside markdown so LaTeX ($...$, $$...$$) in assistant answers
    // is typeset. Optional: if the CDN scripts did not load, fall back to plain markdown.
    try {
        if (typeof marked !== "undefined" && typeof markedKatex === "function") {
            marked.use(markedKatex({ throwOnError: false, nonStandard: true }));
        }
    } catch (e) {
        // math rendering is optional; ignore and render markdown without it
    }

    if (typeof marked !== "undefined") {
        marked.setOptions({ breaks: true, gfm: true });
    }

    // Renders assistant text as markdown (headings/tables/bold/lists) — the agent loop's ChatSession
    // sends the confirmed-final answer as ONE whole-text "delta" event (from finish()), never
    // incremental tokens on that channel, so there is no unclosed-fence mid-stream case to patch.
    function renderMarkdown(text) {
        if (typeof marked === "undefined") return escapeHtml(text);
        try { return marked.parse(text); } catch (e) { return escapeHtml(text); }
    }

    function escapeHtml(s) {
        var d = document.createElement("div");
        d.textContent = s;
        return d.innerHTML;
    }

    function el(id) { return document.getElementById(id); }

    function appendMessage(role, text) {
        var div = document.createElement("div");
        div.className = "message " + role;
        div.textContent = text;
        chatArea.appendChild(div);
        chatArea.scrollTop = chatArea.scrollHeight;
        return div;
    }

    function appendMarkdownMessage(role, text) {
        var div = document.createElement("div");
        div.className = "message " + role;
        div.innerHTML = renderMarkdown(text);
        chatArea.appendChild(div);
        chatArea.scrollTop = chatArea.scrollHeight;
        return div;
    }

    var queuePollTimer = null;

    function setBusy(v) {
        busy = v;
        // Deliberately NOT disabling sendBtn while busy: chat-ui-with-audit-trail queues a prompt
        // sent while ChatSession is busy (PromptQueue, server-side) rather than rejecting it, so a
        // human should be able to type a follow-up and have it wait its turn. Disabling the button
        // here would silently block that — sendPrompt()'s own guard only checks for empty text.
        if (activityLabel) activityLabel.textContent = v ? "thinking…" : "";
        // Poll while busy: a single check right after sending can land in the brief window before
        // a second prompt has actually been queued server-side, showing "empty" even though the
        // queue fills moments later — confirmed by direct testing (curl showed size:1 mid-turn while
        // a single post-send browser check had already moved on).
        if (v && !queuePollTimer) {
            refreshQueue();   // setInterval's first tick is 2s away; check right now too
            queuePollTimer = setInterval(refreshQueue, 2000);
        } else if (!v && queuePollTimer) {
            clearInterval(queuePollTimer);
            queuePollTimer = null;
            refreshQueue();
        }
    }

    function notify(text, isError) {
        if (!notificationBar) return;
        notificationBar.textContent = text;
        notificationBar.className = isError ? "error" : "";
        if (text) {
            setTimeout(function () {
                if (notificationBar.textContent === text) notificationBar.textContent = "";
            }, 5000);
        }
    }

    // ── Theme (chat-ui3-style: [data-theme] on <html>, persisted per-browser) ──

    var THEME_KEY = "chat-ui-theme";

    function initTheme() {
        if (!themeSelect) return;
        var saved = localStorage.getItem(THEME_KEY) || "dark-catppuccin";
        document.documentElement.setAttribute("data-theme", saved);
        themeSelect.value = saved;
        themeSelect.addEventListener("change", function () {
            var theme = themeSelect.value;
            document.documentElement.setAttribute("data-theme", theme);
            localStorage.setItem(THEME_KEY, theme);
        });
    }

    // ── Model selection (persisted the same way as Theme — otherwise loadModels()
    // rebuilding the <select> on every load/tab-switch silently resets it to the first
    // option, and that reset value gets sent as payload.model on the next prompt) ──

    var MODEL_KEY = "chat-ui-model";

    // ── Queue status (server-side: chat-ui-with-audit-trail queues on the server whenever
    // ChatSession is busy, unlike chat-ui3's client-side-only draft queue) ─────

    // Edit/remove/reorder/auto apply to every queued item regardless of who queued it (human or
    // MCP agent) — QueueContentsEditing_260826_oo01. Index-addressed: a concurrent submitter
    // (agent/workflow) could shift indices between fetch and action, same simplification
    // quarkus-chat-ui3's own single-browser queue effectively has too.
    function refreshQueue() {
        if (!queueArea) return;
        fetch(apiUrl("api/tabs/" + TAB_ID + "/queue"))
            .then(function (r) { return r.json(); })
            .then(function (q) {
                var items = (q && q.items) || [];
                var size = items.length;
                queueArea.textContent = "";
                var header = document.createElement("div");
                header.className = "queue-header";
                header.textContent = size > 0
                    ? size + " prompt(s) queued (waiting for the current turn to finish)"
                    : "Queue is empty";
                queueArea.appendChild(header);

                items.forEach(function (item, i) {
                    var row = document.createElement("div");
                    row.className = "queue-item" + (i === 0 ? " current" : "");

                    var index = document.createElement("span");
                    index.className = "queue-index";
                    index.textContent = (i + 1) + ".";
                    row.appendChild(index);

                    var text = document.createElement("span");
                    text.className = "queue-text";
                    text.textContent = item.prompt;
                    if (item.source && item.source !== "human") text.title = "source: " + item.source;
                    row.appendChild(text);

                    var autoLabel = document.createElement("label");
                    autoLabel.className = "queue-auto";
                    var autoCheckbox = document.createElement("input");
                    autoCheckbox.type = "checkbox";
                    autoCheckbox.checked = !!item.auto;
                    autoCheckbox.addEventListener("change", function () {
                        fetch(apiUrl("api/tabs/" + TAB_ID + "/queue/" + i + "/auto"), {
                            method: "POST",
                            headers: { "Content-Type": "application/json" },
                            body: JSON.stringify({ auto: autoCheckbox.checked })
                        }).then(refreshQueue);
                    });
                    autoLabel.appendChild(autoCheckbox);
                    autoLabel.appendChild(document.createTextNode(" Auto"));
                    row.appendChild(autoLabel);

                    var upBtn = document.createElement("button");
                    upBtn.className = "queue-move";
                    upBtn.title = "Move up";
                    upBtn.innerHTML = "&uarr;";
                    upBtn.disabled = (i === 0);
                    upBtn.addEventListener("click", function () { moveQueueItem(i, "up"); });
                    row.appendChild(upBtn);

                    var downBtn = document.createElement("button");
                    downBtn.className = "queue-move";
                    downBtn.title = "Move down";
                    downBtn.innerHTML = "&darr;";
                    downBtn.disabled = (i === items.length - 1);
                    downBtn.addEventListener("click", function () { moveQueueItem(i, "down"); });
                    row.appendChild(downBtn);

                    var editBtn = document.createElement("button");
                    editBtn.className = "queue-edit";
                    editBtn.title = "Edit (copy to input)";
                    editBtn.textContent = "📝";
                    editBtn.addEventListener("click", function () {
                        promptInput.value = item.prompt;
                        promptInput.focus();
                    });
                    row.appendChild(editBtn);

                    var removeBtn = document.createElement("button");
                    removeBtn.className = "queue-remove";
                    removeBtn.title = "Remove";
                    removeBtn.innerHTML = "&times;";
                    removeBtn.addEventListener("click", function () {
                        fetch(apiUrl("api/tabs/" + TAB_ID + "/queue/" + i), { method: "DELETE" }).then(refreshQueue);
                    });
                    row.appendChild(removeBtn);

                    queueArea.appendChild(row);
                });
                queueArea.style.display = (size > 0 || queueArea.dataset.forcedOpen === "1") ? "block" : "none";
            })
            .catch(function () { /* leave the last known state on failure */ });
    }

    function moveQueueItem(index, direction) {
        fetch(apiUrl("api/tabs/" + TAB_ID + "/queue/" + index + "/move"), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ direction: direction })
        }).then(refreshQueue);
    }

    // ── SSE ──────────────────────────────────────────────────────────────────

    function connectSSE() {
        if (eventSource) eventSource.close();
        eventSource = new EventSource(apiUrl("api/tabs/" + TAB_ID + "/chat/stream"));
        eventSource.onopen = function () {
            if (connStatus) { connStatus.textContent = "connected"; connStatus.className = "connected"; }
        };
        eventSource.onerror = function () {
            if (connStatus) { connStatus.textContent = "disconnected"; connStatus.className = "disconnected"; }
        };
        eventSource.onmessage = function (ev) {
            try { handleEvent(JSON.parse(ev.data)); } catch (e) { /* ignore non-JSON keepalive */ }
        };
    }

    function handleEvent(event) {
        switch (event.type) {
            case "status":
                setBusy(!!event.busy);
                refreshQueue();
                if (event.model && modelSelect && !modelSelect.value) {
                    // Model list may not be loaded yet on the very first status event; ignore.
                }
                break;
            case "thinking":
                if (!thinkingEl) {
                    thinkingEl = document.createElement("div");
                    thinkingEl.className = "message thinking";
                    chatArea.appendChild(thinkingEl);
                }
                thinkingEl.textContent += event.content || "";
                chatArea.scrollTop = chatArea.scrollHeight;
                break;
            case "delta":
                // ChatSession's agent loop sends this exactly once per turn, from finish(), with the
                // whole confirmed-final answer text (never incremental tokens on this channel — those
                // stream as "thinking" instead, since an intermediate step might still be a tool call).
                if (thinkingEl) { thinkingEl.remove(); thinkingEl = null; }
                streamingEl = appendMarkdownMessage("assistant", event.content || "");
                chatArea.scrollTop = chatArea.scrollHeight;
                break;
            case "result":
                streamingEl = null;
                thinkingEl = null;
                setBusy(false);
                break;
            case "error":
                appendMessage("error", "Error: " + (event.content || "unknown error"));
                streamingEl = null;
                thinkingEl = null;
                setBusy(false);
                break;
            case "info":
                notify(event.content || "");
                break;
            case "heartbeat":
                break;
            default:
                // log / prompt / mcp_user etc. — not rendered in this first cut.
                break;
        }
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    function sendPrompt() {
        var text = promptInput.value.trim();
        if (!text) {
            // Empty send = "send the next one" (quarkus-chat-ui3's own semantics): force-dispatch
            // the queue's front item, ignoring its auto flag. No-ops server-side if empty.
            fetch(apiUrl("api/tabs/" + TAB_ID + "/queue/advance"), { method: "POST" }).then(refreshQueue);
            return;
        }
        appendMessage("user", text);
        promptInput.value = "";
        setBusy(true);

        var payload = { text: text };
        if (modelSelect && modelSelect.value) payload.model = modelSelect.value;

        fetch(apiUrl("api/tabs/" + TAB_ID + "/chat"), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        }).then(function (r) { return r.json(); })
          .then(function (result) {
              if (result && result.type === "error") {
                  notify(result.message || "request rejected", true);
                  setBusy(false);
              } else {
                  refreshQueue(); // may have landed in the server-side queue if already busy
              }
              // Otherwise: content arrives over SSE.
          })
          .catch(function (err) {
              notify("send failed: " + err.message, true);
              setBusy(false);
          });
    }

    // ── Models ───────────────────────────────────────────────────────────────

    function initModelPersistence() {
        if (!modelSelect) return;
        modelSelect.addEventListener("change", function () {
            localStorage.setItem(MODEL_KEY, modelSelect.value);
        });
    }

    function loadModels() {
        if (!modelSelect) return;
        fetch(apiUrl("api/tabs/" + TAB_ID + "/models"))
            .then(function (r) { return r.json(); })
            .then(function (models) {
                modelSelect.textContent = "";
                (models || []).forEach(function (m) {
                    var opt = document.createElement("option");
                    opt.value = m.name;
                    opt.textContent = m.name;
                    modelSelect.appendChild(opt);
                });
                var saved = localStorage.getItem(MODEL_KEY);
                if (saved && (models || []).some(function (m) { return m.name === saved; })) {
                    modelSelect.value = saved;
                }
            })
            .catch(function () { /* leave the dropdown empty on failure */ });
    }

    // ── Conversation tabs (switch which ConversationTab this pane talks to) ────

    function renderTabBar(tabIds) {
        if (!convTabList) return;
        convTabList.textContent = "";
        tabIds.forEach(function (id) {
            var btn = document.createElement("button");
            btn.className = "rtab-btn" + (id === TAB_ID ? " active" : "");
            btn.textContent = id;
            btn.title = "Switch to tab " + id;
            btn.addEventListener("click", function () { if (id !== TAB_ID) switchTab(id); });
            convTabList.appendChild(btn);
        });
    }

    function loadTabs() {
        fetch(apiUrl("api/tabs"))
            .then(function (r) { return r.json(); })
            .then(function (ids) { renderTabBar(ids || []); })
            .catch(function () { /* leave the tab bar as-is on failure */ });
    }

    // Tears down the current tab's live state and rebuilds the pane for `tabId` — same sequence
    // as the initial page load (hydrate history, load models, check queue, open SSE), just run
    // again against a different tabId instead of only once at DOMContentLoaded.
    function switchTab(tabId) {
        if (eventSource) { eventSource.close(); eventSource = null; }
        TAB_ID = tabId;
        localStorage.setItem(TAB_ID_KEY, tabId);
        chatArea.textContent = "";
        streamingEl = null;
        thinkingEl = null;
        setBusy(false);
        if (queueArea) { queueArea.style.display = "none"; queueArea.dataset.forcedOpen = "0"; }

        renderTabBar(Array.prototype.map.call(convTabList.children, function (b) { return b.textContent; }));
        loadModels();
        hydrateConversation();
        refreshQueue();
        connectSSE();
    }

    function createAndSwitchToNewTab() {
        var id = Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
        fetch(apiUrl("api/tabs/" + id), { method: "POST" })
            .then(function () {
                switchTab(id);
                loadTabs(); // pick up the new tab in the bar
            })
            .catch(function (err) { notify("failed to create tab: " + err.message, true); });
    }

    // ── History hydration ────────────────────────────────────────────────────

    function hydrateConversation() {
        fetch(apiUrl("api/tabs/" + TAB_ID + "/conversation"))
            .then(function (r) { return r.json(); })
            .then(function (turns) {
                (turns || []).forEach(function (t) {
                    if (t.role === "assistant") appendMarkdownMessage(t.role, t.content);
                    else appendMessage(t.role, t.content);
                });
            })
            .catch(function () { /* start with an empty pane on failure */ });
    }

    // ── Init ─────────────────────────────────────────────────────────────────

    document.addEventListener("DOMContentLoaded", function () {
        chatArea = el("chat-area");
        promptInput = el("prompt-input");
        sendBtn = el("send-btn");
        connStatus = el("connection-status");
        activityLabel = el("activity-label");
        modelSelect = el("model-select");
        notificationBar = el("notification-bar");
        themeSelect = el("theme-select");
        queueBtn = el("queue-btn");
        queueArea = el("queue-area");
        convTabList = el("conv-tab-list");
        convTabNewBtn = el("conv-tab-new");

        if (sendBtn) sendBtn.addEventListener("click", sendPrompt);
        if (promptInput) {
            promptInput.addEventListener("keydown", function (e) {
                if (e.key === "Enter" && e.shiftKey) { e.preventDefault(); sendPrompt(); }
            });
        }
        if (queueBtn) {
            queueBtn.addEventListener("click", function () {
                if (!queueArea) return;
                var opening = queueArea.style.display !== "block";
                queueArea.dataset.forcedOpen = opening ? "1" : "0";
                if (opening) refreshQueue(); else queueArea.style.display = "none";
            });
        }
        if (convTabNewBtn) convTabNewBtn.addEventListener("click", createAndSwitchToNewTab);

        initTheme();
        initModelPersistence();
        loadTabs();
        loadModels();
        hydrateConversation();
        refreshQueue();
        connectSSE();
    });
})();

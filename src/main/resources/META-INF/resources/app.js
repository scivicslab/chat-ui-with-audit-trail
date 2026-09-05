// Chat pane wiring for chat-ui-with-audit-trail (adapted from quarkus-chat-ui3's app.js).
//   - one persistent EventSource per conversation tab
//   - POST /api/projects/{projectId}/chats/{chatId}/chat only acknowledges; content streams over SSE
//   - renders delta/thinking/result/error/status ChatEvents into #chat-area
//   - a tab bar (#conv-tab-bar) lets the user switch which ConversationTab this pane talks to
(function () {
    "use strict";

    // A conversation is identified by two coordinates, not one string (Terminology_260829_oo01):
    // the owning project's id and the conversation's id within it.
    var PROJECT_ID_KEY = "chat-ui-last-project";
    var CHAT_ID_KEY = "chat-ui-last-chat";
    var LEGACY_TAB_ID_KEY = "chat-ui-last-tab";
    // Migrates what earlier versions stored under the single legacy key: first "alpha"/"beta"
    // (before ChatActorRename_260827_oo01), then bare "01"/"02" and the short-lived combined
    // "project2-01" form. Without this a returning browser would resend a stale id, and the
    // server would lazily create a brand-new empty conversation instead of reconnecting to the
    // one the browser was actually using.
    var LEGACY_TAB_ID_MAP = { alpha: "01", beta: "02" };
    var PROJECT_ID = localStorage.getItem(PROJECT_ID_KEY);
    var CHAT_ID = localStorage.getItem(CHAT_ID_KEY);
    if (!PROJECT_ID || !CHAT_ID) {
        var legacy = localStorage.getItem(LEGACY_TAB_ID_KEY);
        legacy = LEGACY_TAB_ID_MAP[legacy] || legacy || "01";
        var dash = legacy.lastIndexOf("-");
        if (legacy.indexOf("project") === 0 && dash > 0) {
            PROJECT_ID = legacy.substring(0, dash);
            CHAT_ID = legacy.substring(dash + 1);
        } else {
            PROJECT_ID = "project1";
            CHAT_ID = legacy;
        }
    }

    function apiUrl(path) { return path; }

    // Base path of the active conversation's endpoints.
    function chatUrl(suffix) {
        return "api/projects/" + encodeURIComponent(PROJECT_ID)
                + "/chats/" + encodeURIComponent(CHAT_ID) + suffix;
    }

    var chatArea, promptInput, sendBtn, connStatus, activityLabel, modelSelect, notificationBar;
    var themeSelect, queueBtn, queueArea, stopPlanBtn;
    var eventSource = null;
    var streamingEl = null;   // the live assistant bubble currently receiving deltas
    var streamingMarkdown = "";  // its markdown source, kept for the footer's copy button
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
        try { return withLocalImages(marked.parse(text)); } catch (e) { return escapeHtml(text); }
    }

    // Points every <img> whose source is a path on this machine at /api/local-image, which reads
    // the file server-side (LocalImageInAnswer_260904_oo01). Markdown turns
    // ![](/home/devteam/works/shot.png) into <img src="/home/devteam/works/shot.png">, and the
    // browser asks THIS server for that path — a request no static resource answers, so the picture
    // comes out broken. The browser has no way to open a local file itself; only the server does.
    // Sources under /api/ are left alone: those are this application's own endpoints, not files.
    function withLocalImages(html) {
        var holder = document.createElement("div");
        holder.innerHTML = html;
        var imgs = holder.getElementsByTagName("img");
        for (var i = 0; i < imgs.length; i++) {
            var raw = imgs[i].getAttribute("src") || "";
            var filePath = null;
            if (raw.indexOf("file://") === 0) {
                filePath = decodeURIComponent(raw.substring("file://".length));
            } else if (raw.charAt(0) === "/" && raw.indexOf("/api/") !== 0) {
                filePath = raw;
            }
            if (filePath) {
                imgs[i].setAttribute("src", "api/local-image?path=" + encodeURIComponent(filePath));
            }
        }
        return holder.innerHTML;
    }

    function escapeHtml(s) {
        var d = document.createElement("div");
        d.textContent = s;
        return d.innerHTML;
    }

    function el(id) { return document.getElementById(id); }

    // Writes text to the clipboard. navigator.clipboard exists only in a secure context
    // (https, or http on localhost); when this console is opened over http on a LAN address
    // the property is undefined, so fall back to a hidden textarea plus execCommand("copy").
    function copyTextToClipboard(text) {
        if (navigator.clipboard && window.isSecureContext) {
            return navigator.clipboard.writeText(text);
        }
        return new Promise(function (resolve, reject) {
            var ta = document.createElement("textarea");
            ta.value = text;
            ta.setAttribute("readonly", "");
            ta.style.position = "fixed";
            ta.style.top = "-1000px";
            document.body.appendChild(ta);
            ta.select();
            var ok = false;
            try { ok = document.execCommand("copy"); } catch (e) { ok = false; }
            document.body.removeChild(ta);
            if (ok) resolve(); else reject(new Error("copy command was rejected"));
        });
    }

    // ISO 8601 with the browser's UTC offset — the format the coding standard requires wherever a
    // time is displayed. Same function as quarkus-chat-ui's.
    function formatTime(date) {
        var y = date.getFullYear();
        var m = String(date.getMonth() + 1).padStart(2, "0");
        var d = String(date.getDate()).padStart(2, "0");
        var hh = String(date.getHours()).padStart(2, "0");
        var mm = String(date.getMinutes()).padStart(2, "0");
        var ss = String(date.getSeconds()).padStart(2, "0");
        var tz = -date.getTimezoneOffset();
        var tzSign = tz >= 0 ? "+" : "-";
        var tzH = String(Math.floor(Math.abs(tz) / 60)).padStart(2, "0");
        var tzM = String(Math.abs(tz) % 60).padStart(2, "0");
        return y + "-" + m + "-" + d + "T" + hh + ":" + mm + ":" + ss + tzSign + tzH + ":" + tzM;
    }

    // A clipboard button yielding the message's own markdown source, not the rendered HTML:
    // appendMarkdownMessage() replaces the source with marked.parse()'s output in the DOM, so the
    // source is captured in this closure while it is still available.
    function copyButton(markdownText, label) {
        var btn = document.createElement("button");
        btn.className = "copy-md-btn";
        btn.textContent = label;
        btn.title = "Copy as Markdown";
        btn.addEventListener("click", function () {
            copyTextToClipboard(markdownText).then(function () {
                btn.textContent = "Copied!";
                setTimeout(function () { btn.textContent = label; }, 1500);
            }).catch(function (e) {
                btn.textContent = "Copy failed";
                notify("copy failed: " + e.message, true);
                setTimeout(function () { btn.textContent = label; }, 1500);
            });
        });
        return btn;
    }

    function textSpan(text, title) {
        var span = document.createElement("span");
        span.textContent = text;
        if (title) span.title = title;
        return span;
    }

    function newFooter(div) {
        var footer = document.createElement("div");
        footer.className = "message-footer";
        div.appendChild(footer);
        return footer;
    }

    // Shortens an identifier for display; the whole value stays in the tooltip.
    function shorten(value, max) {
        return value.length > max ? value.substring(0, max) + "..." : value;
    }

    // The line under a finished answer, in the same order and format as quarkus-chat-ui's:
    // cost, duration, session, model, the copy button, then the time. Cost appears only when the
    // server reported one above zero — a local model bills nothing, and a zero is not shown.
    function appendAnswerFooter(div, markdownText, event) {
        var footer = newFooter(div);
        if (event.costUsd != null && event.costUsd > 0) {
            footer.appendChild(textSpan("Cost: $" + event.costUsd.toFixed(4)));
        }
        if (event.durationMs != null && event.durationMs >= 0) {
            footer.appendChild(textSpan("Duration: " + (event.durationMs / 1000).toFixed(1) + "s"));
        }
        if (event.sessionId) {
            var id = String(event.sessionId);
            footer.appendChild(textSpan("Session: " + shorten(id, 12), id));
        }
        // The model the server actually used. The dropdown is the fallback for a server that does
        // not report it with the result.
        var modelName = event.model || (modelSelect && modelSelect.value) || "";
        if (modelName) footer.appendChild(textSpan(shorten(modelName, 30), modelName));
        footer.appendChild(copyButton(markdownText, "Copy MD"));
        footer.appendChild(textSpan(formatTime(new Date())));
    }

    function appendMessage(role, text) {
        var div = document.createElement("div");
        div.className = "message " + role;
        div.textContent = text;
        // The prompt a human typed is worth copying back out; transient error/info bubbles are not.
        if (role === "user") {
            var footer = newFooter(div);
            footer.appendChild(textSpan(formatTime(new Date())));
            footer.appendChild(copyButton(text, "Copy"));
        }
        chatArea.appendChild(div);
        chatArea.scrollTop = chatArea.scrollHeight;
        return div;
    }

    // Renders the bubble only. An answer's footer is added when the result event arrives, because
    // that event carries the duration, the session and the model.
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
        fetch(apiUrl(chatUrl("/queue")))
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
                        fetch(apiUrl(chatUrl("/queue/" + i + "/auto")), {
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
                        fetch(apiUrl(chatUrl("/queue/" + i)), { method: "DELETE" }).then(refreshQueue);
                    });
                    row.appendChild(removeBtn);

                    queueArea.appendChild(row);
                });
                queueArea.style.display = (size > 0 || queueArea.dataset.forcedOpen === "1") ? "block" : "none";
            })
            .catch(function () { /* leave the last known state on failure */ });
    }

    function moveQueueItem(index, direction) {
        fetch(apiUrl(chatUrl("/queue/" + index + "/move")), {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ direction: direction })
        }).then(refreshQueue);
    }

    // ── SSE ──────────────────────────────────────────────────────────────────

    function connectSSE() {
        if (eventSource) eventSource.close();
        eventSource = new EventSource(apiUrl(chatUrl("/chat/stream")));
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
                streamingMarkdown = event.content || "";
                streamingEl = appendMarkdownMessage("assistant", streamingMarkdown);
                chatArea.scrollTop = chatArea.scrollHeight;
                break;
            case "result":
                // The answer's bubble was added by the delta event above; this event is what
                // carries the duration, session and model that go under it.
                if (streamingEl) appendAnswerFooter(streamingEl, streamingMarkdown, event);
                streamingEl = null;
                streamingMarkdown = "";
                thinkingEl = null;
                setBusy(false);
                chatArea.scrollTop = chatArea.scrollHeight;
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
            fetch(apiUrl(chatUrl("/queue/advance")), { method: "POST" }).then(refreshQueue);
            return;
        }
        appendMessage("user", text);
        promptInput.value = "";
        setBusy(true);

        var payload = { text: text };
        if (modelSelect && modelSelect.value) payload.model = modelSelect.value;

        fetch(apiUrl(chatUrl("/chat")), {
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
        fetch(apiUrl(chatUrl("/models")))
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

    // Asks this conversation's plan runner to stop. The runner notices between transitions, so a
    // plan waiting on another conversation stops once that wait returns, not instantly
    // (PlanRunnerLifecycleManagement_260829_oo01).
    function stopPlan() {
        fetch(apiUrl(chatUrl("/plan/stop")), { method: "POST" })
            .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
            .then(function (res) {
                notify(res.ok && res.body && res.body.type === "stopping"
                        ? "Stop requested — the plan stops after its current step."
                        : "No running plan on this conversation.");
            })
            .catch(function (e) { notify("stop_plan failed: " + e.message); });
    }

    // ── Conversation tabs (switch which ConversationTab this pane talks to) ────
    // Switching is triggered from the Actors tree in console.js (click a conversation actor's
    // name), not a bar in this pane — ActorTreeTabSwitcher_260826_oo01. switchChat is exposed on
    // window at the bottom of this file so console.js can call it.

    // Tears down the current conversation's live state and rebuilds the pane for another one —
    // same sequence as the initial page load (hydrate history, load models, check queue, open
    // SSE), just run again against different coordinates instead of only once at DOMContentLoaded.
    function switchChat(projectId, chatId) {
        if (projectId === PROJECT_ID && chatId === CHAT_ID) return;
        if (eventSource) { eventSource.close(); eventSource = null; }
        PROJECT_ID = projectId;
        CHAT_ID = chatId;
        localStorage.setItem(PROJECT_ID_KEY, projectId);
        localStorage.setItem(CHAT_ID_KEY, chatId);
        chatArea.textContent = "";
        streamingEl = null;
        streamingMarkdown = "";
        thinkingEl = null;
        setBusy(false);
        if (queueArea) { queueArea.style.display = "none"; queueArea.dataset.forcedOpen = "0"; }

        loadModels();
        hydrateConversation();
        refreshQueue();
        refreshBusyStatus();
        connectSSE();
    }

    // Shows the existing "thinking…" activity label for the newly-active tab if it's busy —
    // read directly (GET /api/projects/{p}/chats/{c}/status), so this reflects reality even when busy
    // busy with a long turn (e.g. ask_chat) that would otherwise make the conversation/models
    // fetches queue up and silently fail (BusyStateReadableSnapshot_260828_oo01).
    function refreshBusyStatus() {
        var forChat = PROJECT_ID + "/" + CHAT_ID;
        fetch(apiUrl(chatUrl("/status")))
            .then(function (r) { return r.json(); })
            .then(function (s) {
                if (forChat === PROJECT_ID + "/" + CHAT_ID) setBusy(!!(s && s.busy));
            })
            .catch(function () { /* leave whatever setBusy(false) above already set */ });
    }

    // ── History hydration ────────────────────────────────────────────────────

    function hydrateConversation() {
        fetch(apiUrl(chatUrl("/conversation")))
            .then(function (r) { return r.json(); })
            .then(function (turns) {
                (turns || []).forEach(function (t) {
                    if (t.role === "assistant") {
                        // A restored turn gets the copy button but no cost/duration/session/time:
                        // GET /conversation returns the role and the text and nothing else, and a
                        // footer filled with the time of the reload would state something false.
                        var div = appendMarkdownMessage(t.role, t.content);
                        newFooter(div).appendChild(copyButton(t.content, "Copy MD"));
                    } else {
                        appendMessage(t.role, t.content);
                    }
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
        stopPlanBtn = el("stop-plan-btn");

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
        if (stopPlanBtn) stopPlanBtn.addEventListener("click", stopPlan);
        initTheme();
        initModelPersistence();
        loadModels();
        hydrateConversation();
        refreshQueue();
        refreshBusyStatus();
        connectSSE();
    });

    // Exposed for console.js's Actors-tree click handler (ActorTreeTabSwitcher_260826_oo01) —
    // clicking a conversation actor's name calls window.chatUiSwitchChat(projectId, chatId).
    window.chatUiSwitchChat = switchChat;
    window.chatUiGetActiveChat = function () { return { projectId: PROJECT_ID, chatId: CHAT_ID }; };
})();

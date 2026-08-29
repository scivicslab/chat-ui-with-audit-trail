// Console script for chat-ui-with-audit-trail.
//   - right-pane tab switching
//   - Actors tab: fetch GET /api/actors and render the actor tree
//   - Sessions tab: GET /api/sessions?tabId=<active tab>, trace view unchanged (ported from
//     quarkus-chat-ui3)
//   - System Log tab: GET /api/projects/{p}/chats/{c}/log (150_TabScopedLogging_260826_oo01);
//     falls back to GET /api/logs (LogTap, server-wide) only if no conversation is active yet
//   - Agent Loop tab: GET /api/projects/{p}/chats/{c}/workflows[/<name>] (AgentLoopTab_260827_oo01),
//     read-only YAML viewer ported from quarkus-chat-ui3's own "Agent Loop" tab
(function () {
    "use strict";

    // ── Right-pane tabs ─────────────────────────────────────────────────────
    // Single click handler for #right-tab-bar (a second, separate listener here previously raced
    // with initIo()'s own — both fired on the same click, and depending on timing that could kick
    // off two concurrent ioLoadSessions() calls stepping on each other's DOM update).
    function initTabs() {
        var bar = document.getElementById("right-tab-bar");
        if (!bar) return;
        bar.addEventListener("click", function (e) {
            var btn = e.target.closest(".rtab-btn");
            if (!btn) return;
            var tab = btn.getAttribute("data-tab");
            bar.querySelectorAll(".rtab-btn").forEach(function (b) {
                b.classList.toggle("active", b === btn);
            });
            document.querySelectorAll(".rtab-content").forEach(function (c) {
                c.classList.toggle("active", c.id === "tab-" + tab);
            });
            if (tab === "logdb") ioOnShow();
            if (tab === "syslog") refreshLogs();
            if (tab === "agentloop") wfOnShow();
        });
    }

    // ── System Log tab (GET /api/logs — LogTap's server-wide ring buffer) ──────
    // Ported near-verbatim from quarkus-chat-ui3's console.js.
    function fmtLogTime(ms) {
        if (!ms) return "";
        var d = new Date(ms);
        var p = function (n, w) { return String(n).padStart(w || 2, "0"); };
        return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds())
            + "." + p(d.getMilliseconds(), 3);
    }

    function renderLogs(entries) {
        var list = document.getElementById("logs-list");
        if (!list) return;
        list.textContent = "";
        if (!Array.isArray(entries) || entries.length === 0) {
            var empty = document.createElement("div");
            empty.className = "log-empty";
            empty.textContent = "No log entries yet.";
            list.appendChild(empty);
            return;
        }
        entries.forEach(function (e) {
            var line = document.createElement("div");
            line.className = "log-line log-" + (e.level || "INFO");
            if (typeof e.levelValue === "number") line.setAttribute("data-lv", e.levelValue);
            var t = document.createElement("span");
            t.className = "log-time";
            t.textContent = fmtLogTime(e.time) + " ";
            var lv = document.createElement("span");
            lv.className = "log-level";
            lv.textContent = "[" + (e.level || "?") + "] ";
            var lg = document.createElement("span");
            lg.className = "log-logger";
            lg.textContent = (e.logger || "") + ": ";
            var msg = document.createElement("span");
            msg.textContent = e.message || "";   // textContent => no HTML injection
            line.appendChild(t);
            line.appendChild(lv);
            line.appendChild(lg);
            line.appendChild(msg);
            list.appendChild(line);
        });
        list.scrollTop = list.scrollHeight;
    }

    var lastLogEntries = [];

    // Re-render the last-fetched logs, keeping only entries at or above the selected severity.
    function applyLevelFilter() {
        var sel = document.getElementById("logs-level");
        var min = (sel && sel.value !== "") ? Number(sel.value) : -Infinity;
        var filtered = lastLogEntries.filter(function (e) {
            var lv = (typeof e.levelValue === "number") ? e.levelValue : NaN;
            return isNaN(lv) || lv >= min;   // unknown value: always show
        });
        renderLogs(filtered);
        var status = document.getElementById("logs-status");
        if (status) status.textContent = filtered.length + " / " + lastLogEntries.length + " line(s)";
    }

    function hasTextSelectionIn(el) {
        var sel = window.getSelection();
        if (!el || !sel || sel.isCollapsed || sel.rangeCount === 0) return false;
        var node = sel.anchorNode;
        return !!(node && el.contains(node));
    }

    // Maps a tab log multiplexer's RecentEntriesAccumulator.Entry (time/source/type/data) into the
    // shape renderLogs()/applyLevelFilter() already know (LogTap.Entry: time/level/levelValue/
    // logger/message), so the existing rendering + severity filter keep working unchanged. `type`
    // is "INFO" for entries ChatSession/PromptQueue log explicitly, or "log-<LEVEL>" for framework
    // noise forwarded via MultiplexerLogHandler (150_TabScopedLogging_260826_oo01).
    var JUL_LEVEL_VALUES = { SEVERE: 1000, WARNING: 900, INFO: 800, CONFIG: 700, FINE: 500, FINER: 400, FINEST: 300 };
    function fromTabLogShape(entries) {
        return entries.map(function (e) {
            var level = (e.type && e.type.indexOf("log-") === 0) ? e.type.substring(4) : "INFO";
            return {
                time: e.time,
                level: level,
                levelValue: JUL_LEVEL_VALUES.hasOwnProperty(level) ? JUL_LEVEL_VALUES[level] : 800,
                logger: e.source || "",
                message: e.data || ""
            };
        });
    }

    // Base path of the active conversation's endpoints, or null if app.js hasn't loaded yet.
    function activeChatUrl(suffix) {
        var c = (typeof window.chatUiGetActiveChat === "function") ? window.chatUiGetActiveChat() : null;
        if (!c) return null;
        return "api/projects/" + encodeURIComponent(c.projectId)
                + "/chats/" + encodeURIComponent(c.chatId) + suffix;
    }

    var logsRefreshing = false;
    var lastLogSig = null;
    function refreshLogs() {
        if (logsRefreshing) return;
        logsRefreshing = true;
        var status = document.getElementById("logs-status");
        var chatLogUrl = activeChatUrl("/log");
        var url = chatLogUrl || "api/logs";
        fetch(url)
            .then(function (r) {
                if (!r.ok) throw new Error("HTTP " + r.status);
                return r.json();
            })
            .then(function (entries) {
                entries = Array.isArray(entries) ? entries : [];
                if (chatLogUrl) entries = fromTabLogShape(entries);
                var sig = JSON.stringify(entries);
                if (sig === lastLogSig) return;   // unchanged (e.g. idle): do not touch the DOM
                lastLogSig = sig;
                lastLogEntries = entries;
                applyLevelFilter();
            })
            .catch(function (err) {
                if (status) status.textContent = "error: " + err.message;
            })
            .finally(function () { logsRefreshing = false; });
    }

    function initLogs() {
        var btn = document.getElementById("logs-refresh");
        var auto = document.getElementById("logs-auto");
        if (btn) btn.addEventListener("click", refreshLogs);
        var levelSel = document.getElementById("logs-level");
        if (levelSel) levelSel.addEventListener("change", applyLevelFilter);

        var timer = null;
        function applyAuto() {
            if (auto && auto.checked) {
                if (!timer) timer = setInterval(function () {
                    var tab = document.getElementById("tab-syslog");
                    if (tab && tab.classList.contains("active")
                        && !hasTextSelectionIn(document.getElementById("logs-list"))) {
                        refreshLogs();
                    }
                }, 3000);
            } else if (timer) {
                clearInterval(timer);
                timer = null;
            }
        }
        if (auto) auto.addEventListener("change", applyAuto);
        applyAuto();
    }

    // ── Agent Loop tab (GET /api/projects/{p}/chats/{c}/workflows[/<name>]) — read-only YAML viewer,
    // ported near-verbatim from quarkus-chat-ui3's console.js, made tab-scoped (AgentLoopTab_260827_oo01).
    function wfStatus(msg) {
        var s = document.getElementById("wf-status");
        if (s) s.textContent = msg || "";
    }

    // Splits a workflow YAML into a preamble (everything before the first step) and the top-level
    // step items (lines beginning with exactly "  - "). Display only — no reassembly.
    function wfSplitSteps(yaml) {
        var lines = (yaml || "").split("\n");
        var preamble = [], steps = [], cur = null;
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (/^  - /.test(line)) {
                if (cur) steps.push(cur.join("\n"));
                cur = [line];
            } else if (cur) {
                cur.push(line);
            } else {
                preamble.push(line);
            }
        }
        if (cur) steps.push(cur.join("\n"));
        return { preamble: preamble.join("\n").replace(/\s+$/, ""), steps: steps };
    }

    // Box heading: the step's transition direction (the states array) plus its 0-based step number.
    function wfStepTitle(text, idx) {
        var m = text.match(/(^|\n)\s*-?\s*states:\s*(.+)/);
        var states = m ? m[2].trim() : "";
        return (states ? states + "   " : "") + "# step " + idx;
    }

    function wfRenderBox(parent, title, body, kind) {
        var box = document.createElement("div");
        box.className = "wf-box" + (kind ? " wf-" + kind : "");
        var h = document.createElement("div");
        h.className = "wf-box-title";
        h.textContent = title;
        var pre = document.createElement("pre");
        pre.className = "wf-box-yaml";
        pre.textContent = body;   // read-only; textContent => no HTML injection
        box.appendChild(h);
        box.appendChild(pre);
        parent.appendChild(box);
    }

    function wfRender(yaml) {
        var list = document.getElementById("wf-list");
        if (!list) return;
        list.textContent = "";
        var parts = wfSplitSteps(yaml);
        if (parts.preamble) wfRenderBox(list, "workflow header", parts.preamble, "head");
        parts.steps.forEach(function (s, i) {
            wfRenderBox(list, wfStepTitle(s, i), s, "step");
        });
        wfStatus(parts.steps.length + " step(s) — read-only");
    }

    function wfLoad(name) {
        if (!name) return;
        wfStatus("loading…");
        var wfUrl = activeChatUrl("/workflows/" + encodeURIComponent(name));
        if (!wfUrl) { wfStatus("no active conversation"); return; }
        fetch(wfUrl)
            .then(function (r) { return r.json(); })
            .then(function (d) {
                if (!d || !d.yaml) { wfStatus("not found"); return; }
                wfRender(d.yaml);
            })
            .catch(function (e) { wfStatus("error: " + e.message); });
    }

    function wfPopulate(then) {
        var sel = document.getElementById("wf-select");
        if (!sel) return;
        var wfListUrl = activeChatUrl("/workflows");
        if (!wfListUrl) { wfStatus("no active conversation"); return; }
        fetch(wfListUrl)
            .then(function (r) { return r.json(); })
            .then(function (arr) {
                sel.textContent = "";
                (arr || []).forEach(function (w) {
                    var o = document.createElement("option");
                    o.value = w.name;
                    o.textContent = w.title || w.name;
                    sel.appendChild(o);
                });
                if (then) then();
            })
            .catch(function (e) { wfStatus("error: " + e.message); });
    }

    // Always re-populates the catalog (not just on first show) so switching conversation tabs
    // updates which workflow(s) are listed/selected — each tab can be configured with a different
    // agent-loop workflow file (AgentLoopTab_260827_oo01 — tab-sync has no exceptions here).
    function wfOnShow() {
        var sel = document.getElementById("wf-select");
        wfPopulate(function () { if (sel) wfLoad(sel.value); });
    }

    function initWorkflow() {
        var sel = document.getElementById("wf-select");
        if (sel) sel.addEventListener("change", function () { wfLoad(sel.value); });
        var btn = document.getElementById("wf-refresh");
        if (btn) btn.addEventListener("click", function () { wfLoad(sel ? sel.value : ""); });
    }

    // ── (2) Collapsible left dock (actor tree) ──────────────────────────────
    function initDock() {
        var toggle = document.getElementById("dock-toggle");
        var dock = document.getElementById("left-dock");
        if (!toggle || !dock) return;
        toggle.addEventListener("click", function () {
            var collapsed = dock.classList.toggle("collapsed");
            if (!collapsed) refreshActors();   // refresh when re-opening
        });
    }

    // ── Left dock width resize (drag handle, persisted like Theme/model) ────
    var LEFT_DOCK_WIDTH_KEY = "chat-ui-left-dock-width";

    function initLeftDockResize() {
        var dock = document.getElementById("left-dock");
        var handle = document.getElementById("left-dock-resize-handle");
        if (!dock || !handle) return;

        var saved = parseInt(localStorage.getItem(LEFT_DOCK_WIDTH_KEY), 10);
        if (saved && saved > 0) dock.style.width = saved + "px";

        var dragging = false;
        var startX = 0;
        var startWidth = 0;

        handle.addEventListener("mousedown", function (e) {
            e.preventDefault();
            dragging = true;
            startX = e.clientX;
            startWidth = dock.offsetWidth;
            dock.classList.add("resizing");
            handle.classList.add("dragging");
            document.body.style.cursor = "ew-resize";
            document.body.style.userSelect = "none";
        });

        document.addEventListener("mousemove", function (e) {
            if (!dragging) return;
            var newWidth = Math.max(120, Math.min(startWidth + (e.clientX - startX), 600));
            dock.style.width = newWidth + "px";
        });

        document.addEventListener("mouseup", function () {
            if (!dragging) return;
            dragging = false;
            dock.classList.remove("resizing");
            handle.classList.remove("dragging");
            document.body.style.cursor = "";
            document.body.style.userSelect = "";
            localStorage.setItem(LEFT_DOCK_WIDTH_KEY, dock.offsetWidth);
        });
    }

    // ── Actors tab ──────────────────────────────────────────────────────────
    // Each node: {name, type, alive, children[]}. Collapsed state is keyed by actor name (unique
    // in this actor system) and kept outside the tree DOM, so it survives the full rebuild
    // renderActorTree() does on every refresh (including the 3s auto-refresh timer).
    var collapsedActorNodes = new Set();

    function actorNodeEl(node) {
        var wrap = document.createElement("div");
        wrap.className = "actor-node";
        var label = document.createElement("div");
        label.className = "actor-label" + (node.alive ? "" : " actor-dead");
        var hasChildren = !!(node.children && node.children.length);
        var toggle = document.createElement("span");
        toggle.className = "actor-toggle";
        if (hasChildren) toggle.textContent = collapsedActorNodes.has(node.name) ? "▸" : "▾";
        var dot = document.createElement("span");
        dot.className = "actor-dot " + (node.alive ? "alive" : "dead");
        dot.textContent = "●";
        var name = document.createElement("span");
        name.className = "actor-name";
        name.textContent = node.name;
        // "chat-<id>" (ConversationTab) nodes double as the tab switcher — click the name (not
        // the fold toggle) to switch the chat pane, instead of a separate bar in that pane
        // (ActorTreeTabSwitcher_260826_oo01). Children like "chat-<id>.chat" don't match.
        var tabMatch = /^([^/]+)\/chat-([^.]+)$/.exec(node.name);
        if (tabMatch && typeof window.chatUiSwitchChat === "function") {
            name.classList.add("tab-switchable");
            var active = (typeof window.chatUiGetActiveChat === "function") ? window.chatUiGetActiveChat() : null;
            if (active && active.projectId === tabMatch[1] && active.chatId === tabMatch[2]) {
                name.classList.add("tab-active");
            }
            name.title = "Switch to " + tabMatch[1] + " / " + tabMatch[2];
            name.addEventListener("click", function (e) {
                e.stopPropagation(); // don't also trigger the fold/unfold toggle on the label
                window.chatUiSwitchChat(tabMatch[1], tabMatch[2]);
                refreshActors(); // re-render so the tab-active highlight moves immediately
                // Right pane follows the newly active tab (150_TabScopedLogging_260826_oo01) —
                // re-fetch immediately rather than waiting for the next poll/tab-open.
                ioSessionsLoaded = false;
                if (document.getElementById("tab-logdb") && document.getElementById("tab-logdb").classList.contains("active")) {
                    ioLoadSessions();
                }
                lastLogSig = null;
                if (document.getElementById("tab-syslog") && document.getElementById("tab-syslog").classList.contains("active")) {
                    refreshLogs();
                }
                if (document.getElementById("tab-agentloop") && document.getElementById("tab-agentloop").classList.contains("active")) {
                    wfOnShow();
                }
            });
        }
        var type = document.createElement("span");
        type.className = "actor-type";
        type.textContent = node.type ? "  " + node.type : "";
        label.appendChild(toggle);
        label.appendChild(dot);
        label.appendChild(name);
        label.appendChild(type);
        wrap.appendChild(label);
        if (hasChildren) {
            var kids = document.createElement("div");
            kids.className = "actor-children" + (collapsedActorNodes.has(node.name) ? " collapsed" : "");
            node.children.forEach(function (c) { kids.appendChild(actorNodeEl(c)); });
            wrap.appendChild(kids);

            label.classList.add("actor-label-toggleable");
            label.addEventListener("click", function () {
                var willCollapse = !collapsedActorNodes.has(node.name);
                if (willCollapse) collapsedActorNodes.add(node.name);
                else collapsedActorNodes.delete(node.name);
                toggle.textContent = willCollapse ? "▸" : "▾";
                kids.classList.toggle("collapsed", willCollapse);
            });
        }
        return wrap;
    }

    function renderActorTree(root) {
        var el = document.getElementById("actors-tree");
        if (!el) return;
        el.textContent = "";
        if (!root) {
            var empty = document.createElement("div");
            empty.className = "actor-empty";
            empty.textContent = "No actors.";
            el.appendChild(empty);
            return;
        }
        el.appendChild(actorNodeEl(root));
    }

    function countActors(node) {
        if (!node) return 0;
        var n = 1;
        (node.children || []).forEach(function (c) { n += countActors(c); });
        return n;
    }

    var actorsRefreshing = false;
    function refreshActors() {
        if (actorsRefreshing) return;
        actorsRefreshing = true;
        var status = document.getElementById("actors-status");
        fetch("api/actors")
            .then(function (r) {
                if (!r.ok) throw new Error("HTTP " + r.status);
                return r.json();
            })
            .then(function (root) {
                renderActorTree(root);
                if (status) status.textContent = countActors(root) + " actor(s)";
            })
            .catch(function (err) {
                if (status) status.textContent = "error: " + err.message;
            })
            .finally(function () { actorsRefreshing = false; });
    }

    // Creates a new, independent project (ProjectScopedActorTree_260829_oo01) and switches the
    // right pane to its first tab.
    function createProject() {
        fetch("api/projects", { method: "POST" })
            .then(function (r) {
                if (!r.ok) throw new Error("HTTP " + r.status);
                return r.json();
            })
            .then(function (body) {
                refreshActors();
                if (body && body.projectId && window.chatUiSwitchChat) {
                    window.chatUiSwitchChat(body.projectId, "01");
                }
            })
            .catch(function (err) {
                var status = document.getElementById("actors-status");
                if (status) status.textContent = "error: " + err.message;
            });
    }

    function initActors() {
        var btn = document.getElementById("actors-refresh");
        var auto = document.getElementById("actors-auto");
        var newProjectBtn = document.getElementById("new-project-btn");
        if (btn) btn.addEventListener("click", refreshActors);
        if (newProjectBtn) newProjectBtn.addEventListener("click", createProject);

        var timer = null;
        function applyAuto() {
            if (auto && auto.checked) {
                if (!timer) {
                    timer = setInterval(function () {
                        var dock = document.getElementById("left-dock");
                        if (dock && !dock.classList.contains("collapsed")) refreshActors();
                    }, 3000);
                }
            } else if (timer) {
                clearInterval(timer);
                timer = null;
            }
        }
        if (auto) auto.addEventListener("change", applyAuto);
        applyAuto();
    }

    // ── Sessions tab (list conversations; expand one to read its turn-by-turn trace inline) ──
    // Ported from quarkus-chat-ui3's console.js — the REST shape (IoLogView.TraceTurn/TraceStep)
    // and CSS classes (.sess/.tr-turn/.trm/.tr-sec) are already identical, so this is a direct port.
    var ioSessionsLoaded = false;

    function ioSetStatus(t) { var s = document.getElementById("io-status"); if (s) s.textContent = t; }

    function ioDeleteSession(id) {
        if (!confirm("Delete session #" + id + " and all its logs?")) return;
        ioSetStatus("deleting…");
        fetch("api/sessions/" + encodeURIComponent(id), { method: "DELETE" })
            .then(function (r) { return r.json(); })
            .then(function (j) {
                ioSetStatus(j.deleted ? ("deleted session #" + id)
                                      : (j.error || "not deleted (active session is kept)"));
                ioSessionsLoaded = false; ioLoadSessions();
            })
            .catch(function (e) { ioSetStatus("error: " + e); });
    }

    function ioLoadSessions() {
        var el = document.getElementById("io-sessions");
        // Remember which sessions were expanded so a refresh re-fetches their trace (picking up
        // any new turns) instead of silently collapsing whatever the user had open to inspect.
        var openIds = {};
        if (el) {
            el.querySelectorAll("details.sess[open]").forEach(function (d) {
                if (d.dataset.sessionId) openIds[d.dataset.sessionId] = true;
            });
        }
        var c = (typeof window.chatUiGetActiveChat === "function") ? window.chatUiGetActiveChat() : null;
        var url = c ? ("api/sessions?tabId=" + encodeURIComponent(c.projectId + "/chat-" + c.chatId))
                    : "api/sessions";
        return fetch(url).then(function (r) { return r.json(); }).then(function (list) {
            if (!el) return;
            el.textContent = "";
            list = list || [];
            if (!list.length) {
                var e = document.createElement("div"); e.className = "io-empty"; e.textContent = "No sessions.";
                el.appendChild(e); ioSetStatus("0 sessions"); ioSessionsLoaded = true; return;
            }
            list.forEach(function (s) {
                var row = ioSessionEl(s);
                el.appendChild(row);
                if (openIds[String(s.sessionId)]) row.open = true;   // re-triggers its trace fetch
            });
            ioSessionsLoaded = true;
            ioSetStatus(list.length + " session(s)");
        }).catch(function (err) { ioSetStatus("error: " + err.message); });
    }

    function ioSessionEl(s) {
        var det = document.createElement("details"); det.className = "sess";
        det.dataset.sessionId = String(s.sessionId);
        var sum = document.createElement("summary"); sum.className = "sess-head";
        var meta = document.createElement("span"); meta.className = "sess-meta";
        meta.textContent = "#" + s.sessionId + "  ·  " + (s.startedAt || "") + "  ·  "
                         + (s.workflowName || "") + "  ·  "
                         + (s.totalLogEntries != null ? s.totalLogEntries + " entries" : "");
        var del = document.createElement("button"); del.type = "button"; del.className = "io-del";
        del.title = "Delete this session and all its logs"; del.textContent = "🗑";
        del.addEventListener("click", function (ev) { ev.preventDefault(); ev.stopPropagation(); ioDeleteSession(s.sessionId); });
        sum.appendChild(meta); sum.appendChild(del);
        det.appendChild(sum);
        var body = document.createElement("div"); body.className = "sess-body"; body.textContent = "loading…";
        det.appendChild(body);
        var loaded = false;
        det.addEventListener("toggle", function () {
            if (!det.open || loaded) return;
            loaded = true;
            fetch("api/sessions/" + s.sessionId + "/trace")
                .then(function (r) { return r.json(); })
                .then(function (turns) { ioRenderTraceInto(body, turns || [], s.sessionId); })
                .catch(function (err) { body.textContent = "error: " + err.message; loaded = false; });
        });
        return det;
    }

    function ioRenderTraceInto(el, turns, sessionId) {
        el.textContent = "";
        if (!turns.length) {
            var none = document.createElement("div"); none.className = "io-empty";
            none.textContent = "No agent-loop trace in this session."; el.appendChild(none); return;
        }
        turns.forEach(function (t) {
            var box = document.createElement("details"); box.className = "tr-turn"; box.open = true;
            var head = document.createElement("summary"); head.className = "tr-turn-head";
            head.textContent = "Turn " + t.turn;
            box.appendChild(head);
            ioTurnMessages(t).forEach(function (m) { box.appendChild(ioMsgEl(m, sessionId)); });
            el.appendChild(box);
        });
    }

    // Flattens a turn into an ordered list of one-direction messages: an llm step -> (loop→LLM
    // request) + (LLM→loop reply); a tool step -> (loop→tool input) + (tool→loop observation).
    function ioTurnMessages(t) {
        var out = [];
        var firstLlm = (t.steps || []).filter(function (s) { return s.kind === "llm"; })[0];
        out.push({ dir: "user → loop", cls: "user",
                   summary: "🗣 " + (t.userPrompt || "(user prompt not found)"),
                   id: firstLlm ? firstLlm.id : -1, part: "USER" });
        (t.steps || []).forEach(function (s) {
            if (s.kind === "tool") {
                out.push({ dir: "loop → tool", cls: "to-tool",
                           summary: "↳ " + s.toolName + "(" + s.toolInput + ")", id: s.id, part: "INPUT" });
                out.push({ dir: "tool → loop", cls: "from-tool",
                           summary: "→ " + s.observation + " …  [" + s.obsChars + " chars]", id: s.id, part: "OBSERVATION" });
                return;
            }
            var tokIn = (typeof s.promptTokens === "number" && s.promptTokens >= 0) ? (" · " + s.promptTokens + " tok in") : "";
            out.push({ dir: "loop → LLM", cls: "to-llm",
                       summary: "request" + tokIn + "  (system + history + user + tools offered)", id: s.id, part: "REQUEST" });
            var reply;
            if (s.toolCalls) {
                reply = "🔧 " + s.toolCalls.replace(/\s+/g, " ").trim();
                if (s.reason) reply += "   💡 " + s.reason;
            } else {
                reply = "💬 " + (s.thought || "(empty)");
            }
            var tokOut = (typeof s.completionTokens === "number" && s.completionTokens >= 0) ? ("  ·  " + s.completionTokens + " tok out") : "";
            out.push({ dir: "LLM → loop", cls: "from-llm", summary: reply + tokOut, id: s.id, part: "RESPONSE" });
        });
        return out;
    }

    function ioMsgEl(m, sessionId) {
        var det = document.createElement("details"); det.className = "trm " + m.cls;
        var sum = document.createElement("summary"); sum.className = "trm-sum";
        var dir = document.createElement("span"); dir.className = "trm-dir"; dir.textContent = m.dir;
        var txt = document.createElement("span"); txt.className = "trm-txt"; txt.textContent = m.summary;
        sum.appendChild(dir); sum.appendChild(txt);
        det.appendChild(sum);
        var body = document.createElement("div"); body.className = "trm-body"; body.textContent = "loading…";
        det.appendChild(body);
        var loaded = false;
        det.addEventListener("toggle", function () {
            if (!det.open || loaded) return;
            loaded = true;
            if (m.id < 0) { body.textContent = "(no source entry)"; return; }
            fetch("api/sessions/" + sessionId + "/entry/" + m.id)
                .then(function (r) { return r.json(); })
                .then(function (d) { ioRenderPart(body, d.message || "", m.part); })
                .catch(function (err) { body.textContent = "error: " + err.message; loaded = false; });
        });
        return det;
    }

    function ioRenderPart(holder, message, part) {
        holder.textContent = "";
        var sections;
        if (part === "USER") {
            sections = [{ spec: { t: "user message", cls: "user" }, body: ioUserMessageOf(message) }];
        } else {
            var kind = (part === "INPUT" || part === "OBSERVATION") ? "tool" : "llm";
            var want = { REQUEST: ["REQUEST"], RESPONSE: ["RESPONSE", "REASONING", "TOOL_CALLS"],
                         INPUT: ["TOOL", "INPUT"], OBSERVATION: ["OBSERVATION"] }[part] || [];
            sections = ioSplitEntry(message, kind).filter(function (sec) {
                return want.indexOf(sec.spec.k.slice(0, -1)) >= 0;
            });
        }
        if (!sections.length) {
            var pre0 = document.createElement("pre"); pre0.className = "tr-full-body"; pre0.textContent = "(empty)";
            holder.appendChild(pre0); return;
        }
        sections.forEach(function (sec) {
            var block = document.createElement("div"); block.className = "tr-sec " + (sec.spec.cls || "");
            var h = document.createElement("div"); h.className = "tr-sec-head";
            var tt = document.createElement("span"); tt.className = "tr-sec-title"; tt.textContent = sec.spec.t;
            h.appendChild(tt);
            var pre = document.createElement("pre"); pre.className = "tr-full-body";
            var b = sec.body;
            if (sec.spec.json) { try { b = JSON.stringify(JSON.parse(b), null, 2); } catch (e) { /* keep raw */ } }
            pre.textContent = b ? b : "(empty)";
            block.appendChild(h); block.appendChild(pre);
            holder.appendChild(block);
        });
    }

    function ioUserMessageOf(message) {
        var req = ioSplitEntry(message, "llm").filter(function (s) { return s.spec.k === "REQUEST:"; })[0];
        if (!req) return "";
        try {
            var msgs = (JSON.parse(req.body).messages) || [];
            var last = "";
            msgs.forEach(function (mm) { if (mm.role === "user") last = mm.content || ""; });
            return last;
        } catch (e) { return ""; }
    }

    function ioEntrySections(kind) {
        if (kind === "tool") return [
            { k: "TOOL:",        t: "TOOL (name)",          cls: "to-tool" },
            { k: "INPUT:",       t: "INPUT (arguments)",    cls: "to-tool" },
            { k: "OBSERVATION:", t: "OBSERVATION (result)", cls: "from-tool" }
        ];
        return [
            { k: "REQUEST:",     t: "REQUEST (system + history + user + tools offered)", cls: "to-llm", json: true },
            { k: "RESPONSE:",    t: "RESPONSE (assistant text)", cls: "from-llm" },
            { k: "REASONING:",   t: "REASONING (chain of thought)", cls: "from-llm" },
            { k: "TOOL_CALLS:",  t: "TOOL_CALLS (functions the model asked to run)", cls: "from-llm" },
            { k: "USAGE:",       t: "USAGE (token counts)", cls: "meta" }
        ];
    }

    function ioSplitEntry(message, kind) {
        var specs = ioEntrySections(kind);
        var found = [], cursor = 0;
        specs.forEach(function (s) {
            var i = message.indexOf(s.k, cursor);
            if (i >= 0) { found.push({ spec: s, mark: i, start: i + s.k.length }); cursor = i + s.k.length; }
        });
        return found.map(function (f, j) {
            var end = (j + 1 < found.length) ? found[j + 1].mark : message.length;
            return { spec: f.spec, body: message.substring(f.start, end).trim() };
        });
    }

    function ioOnShow() {
        if (!ioSessionsLoaded) ioLoadSessions();
    }

    function initIo() {
        var refresh = document.getElementById("io-refresh");
        if (refresh) refresh.addEventListener("click", function () { ioSessionsLoaded = false; ioLoadSessions(); });
        var delOld = document.getElementById("io-del-old");
        if (delOld) delOld.addEventListener("click", function () {
            var d = document.getElementById("io-del-days");
            var days = d ? parseInt(d.value, 10) : 30;
            if (isNaN(days) || days < 0) { ioSetStatus("enter a valid day count"); return; }
            if (!confirm("Delete ALL sessions older than " + days + " day(s)? (the active conversation is kept)")) return;
            ioSetStatus("deleting…");
            fetch("api/sessions/old?days=" + days, { method: "DELETE" })
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    ioSetStatus("deleted " + (j.deleted || 0) + " session(s) older than " + days + "d");
                    ioSessionsLoaded = false; ioLoadSessions();
                })
                .catch(function (e) { ioSetStatus("error: " + e); });
        });
        // Tab-switch-triggered lazy load is wired once, in initTabs()'s own #right-tab-bar handler.
    }


    // ── Extensions panel (SkillAndAgentsFile_260830_oo01) ─────────────────────
    // Two tabs, each backed by an endpoint that exists: Skills lists what
    // GET /api/skills indexed, Project sets the working directory whose AGENTS.md
    // (or CLAUDE.md) every conversation in that project receives.
    function extSetContent(node) {
        var content = document.getElementById("ext-content");
        if (!content) return;
        content.textContent = "";
        content.appendChild(node);
    }

    function extMessage(text, cls) {
        var d = document.createElement("div");
        d.className = cls || "ext-loading";
        d.textContent = text;
        return d;
    }

    function extShowDialog(title, body) {
        var overlay = document.getElementById("ext-dialog-overlay");
        var t = document.getElementById("ext-dialog-title");
        var b = document.getElementById("ext-dialog-body");
        if (!overlay || !t || !b) return;
        t.textContent = title;
        b.textContent = body;
        overlay.style.display = "flex";
    }

    function extRenderSkills() {
        extSetContent(extMessage("Loading…"));
        fetch("api/skills")
            .then(function (r) { return r.json(); })
            .then(function (j) {
                var wrap = document.createElement("div");
                var roots = document.createElement("div");
                roots.className = "ext-roots";
                roots.textContent = "roots: " + (j.roots || []).join(", ");
                wrap.appendChild(roots);
                if (!j.skills || j.skills.length === 0) {
                    wrap.appendChild(extMessage("No skill was indexed.", "ext-loading"));
                }
                (j.skills || []).forEach(function (s) {
                    var row = document.createElement("div");
                    row.className = "ext-item";
                    var name = document.createElement("div");
                    name.className = "ext-item-name";
                    name.textContent = s.name;
                    var desc = document.createElement("div");
                    desc.className = "ext-item-desc";
                    desc.textContent = s.description;
                    var dir = document.createElement("div");
                    dir.className = "ext-item-dir";
                    dir.textContent = s.directory;
                    row.appendChild(name);
                    row.appendChild(desc);
                    row.appendChild(dir);
                    row.addEventListener("click", function () {
                        fetch("api/skills/" + encodeURIComponent(s.name))
                            .then(function (r) { return r.text(); })
                            .then(function (text) { extShowDialog(s.name, text); })
                            .catch(function (e) { extShowDialog(s.name, "error: " + e); });
                    });
                    wrap.appendChild(row);
                });
                (j.problems || []).forEach(function (p) {
                    wrap.appendChild(extMessage(p, "ext-problem"));
                });
                var rescan = document.createElement("button");
                rescan.className = "ext-action";
                rescan.textContent = "Rescan";
                rescan.addEventListener("click", function () {
                    fetch("api/skills/rescan", { method: "POST" }).then(extRenderSkills);
                });
                wrap.appendChild(rescan);
                extSetContent(wrap);
            })
            .catch(function (e) { extSetContent(extMessage("error: " + e, "ext-problem")); });
    }

    function extRenderProject() {
        var c = (typeof window.chatUiGetActiveChat === "function") ? window.chatUiGetActiveChat() : null;
        if (!c) { extSetContent(extMessage("No conversation is active.")); return; }
        var wrap = document.createElement("div");
        var label = document.createElement("div");
        label.className = "ext-roots";
        label.textContent = "Working directory of " + c.projectId
            + " — its AGENTS.md (or CLAUDE.md) is given to every conversation here.";
        var input = document.createElement("input");
        input.type = "text";
        input.className = "ext-input";
        input.placeholder = "/home/devteam/works/<repository>";
        var apply = document.createElement("button");
        apply.className = "ext-action";
        apply.textContent = "Apply";
        var result = document.createElement("div");
        result.className = "ext-roots";
        apply.addEventListener("click", function () {
            result.textContent = "applying…";
            fetch("api/projects/" + encodeURIComponent(c.projectId) + "/working-dir", {
                method: "POST",
                headers: { "Content-Type": "text/plain" },
                body: input.value
            })
                .then(function (r) { return r.json(); })
                .then(function (j) { result.textContent = j.message || JSON.stringify(j); })
                .catch(function (e) { result.textContent = "error: " + e; });
        });
        wrap.appendChild(label);
        wrap.appendChild(input);
        wrap.appendChild(apply);
        wrap.appendChild(result);
        extSetContent(wrap);
    }

    function extRender(tab) {
        if (tab === "project") extRenderProject();
        else extRenderSkills();
    }

    function initExtensions() {
        var btn = document.getElementById("extensions-btn");
        var panel = document.getElementById("extensions-panel");
        var tabs = document.querySelector("#extensions-panel .ext-tabs");
        if (!btn || !panel) return;
        btn.addEventListener("click", function (e) {
            e.stopPropagation();
            var showing = panel.style.display === "none" || panel.style.display === "";
            panel.style.display = showing ? "block" : "none";
            if (showing) {
                var active = panel.querySelector(".ext-tab.active");
                extRender(active ? active.getAttribute("data-tab") : "skills");
            }
        });
        // A click anywhere else closes the panel — except inside the skill dialog, which is a
        // sibling of the panel in the DOM, so dismissing the dialog would otherwise take the panel
        // with it and leave the reader back at the chat with no list to return to.
        document.addEventListener("click", function (e) {
            if (panel.style.display !== "block") return;
            if (panel.contains(e.target) || e.target === btn) return;
            if (e.target.closest && e.target.closest("#ext-dialog-overlay")) return;
            panel.style.display = "none";
        });
        if (tabs) tabs.addEventListener("click", function (e) {
            var t = e.target.closest(".ext-tab");
            if (!t) return;
            tabs.querySelectorAll(".ext-tab").forEach(function (b) { b.classList.toggle("active", b === t); });
            extRender(t.getAttribute("data-tab"));
        });
        var close = document.getElementById("ext-dialog-close");
        var overlay = document.getElementById("ext-dialog-overlay");
        if (close && overlay) {
            close.addEventListener("click", function () { overlay.style.display = "none"; });
            overlay.addEventListener("click", function (e) {
                if (e.target === overlay) overlay.style.display = "none";
            });
        }
    }

    document.addEventListener("DOMContentLoaded", function () {
        initTabs();
        initActors();
        initDock();
        initLeftDockResize();
        initIo();
        initLogs();
        initWorkflow();
        initExtensions();
        refreshActors();   // the actor dock is visible by default
        ioOnShow();         // Sessions is the default-active right-pane tab
    });
})();

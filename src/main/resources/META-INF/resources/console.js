// Minimal console script for chat-ui-with-audit-trail (from-scratch rebuild stage).
//   - right-pane tab switching
//   - Actors tab: fetch GET /api/actors and render the actor tree
// Other right-pane tabs (Sessions / System Log / Workflow) have no backend yet.
(function () {
    "use strict";

    // ── Right-pane tabs ─────────────────────────────────────────────────────
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
        });
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

    // ── Actors tab ──────────────────────────────────────────────────────────
    // Each node: {name, type, alive, children[]}.
    function actorNodeEl(node) {
        var wrap = document.createElement("div");
        wrap.className = "actor-node";
        var label = document.createElement("div");
        label.className = "actor-label" + (node.alive ? "" : " actor-dead");
        var dot = document.createElement("span");
        dot.className = "actor-dot " + (node.alive ? "alive" : "dead");
        dot.textContent = "●";
        var name = document.createElement("span");
        name.className = "actor-name";
        name.textContent = node.name;
        var type = document.createElement("span");
        type.className = "actor-type";
        type.textContent = node.type ? "  " + node.type : "";
        label.appendChild(dot);
        label.appendChild(name);
        label.appendChild(type);
        wrap.appendChild(label);
        if (node.children && node.children.length) {
            var kids = document.createElement("div");
            kids.className = "actor-children";
            node.children.forEach(function (c) { kids.appendChild(actorNodeEl(c)); });
            wrap.appendChild(kids);
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

    function initActors() {
        var btn = document.getElementById("actors-refresh");
        var auto = document.getElementById("actors-auto");
        if (btn) btn.addEventListener("click", refreshActors);

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
        return fetch("api/sessions").then(function (r) { return r.json(); }).then(function (list) {
            var el = document.getElementById("io-sessions");
            if (!el) return;
            el.textContent = "";
            list = list || [];
            if (!list.length) {
                var e = document.createElement("div"); e.className = "io-empty"; e.textContent = "No sessions.";
                el.appendChild(e); ioSetStatus("0 sessions"); ioSessionsLoaded = true; return;
            }
            list.forEach(function (s) { el.appendChild(ioSessionEl(s)); });
            ioSessionsLoaded = true;
            ioSetStatus(list.length + " session(s)");
        }).catch(function (err) { ioSetStatus("error: " + err.message); });
    }

    function ioSessionEl(s) {
        var det = document.createElement("details"); det.className = "sess";
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
        // Load lazily the first time the Sessions tab is actually shown.
        var bar = document.getElementById("right-tab-bar");
        if (bar) {
            bar.addEventListener("click", function (e) {
                var btn = e.target.closest(".rtab-btn");
                if (btn && btn.getAttribute("data-tab") === "logdb") ioOnShow();
            });
        }
    }

    document.addEventListener("DOMContentLoaded", function () {
        initTabs();
        initActors();
        initDock();
        initIo();
        refreshActors();   // the actor dock is visible by default
        ioOnShow();         // Sessions is the default-active right-pane tab
    });
})();

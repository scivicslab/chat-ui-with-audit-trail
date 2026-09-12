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
            if (tab === "jobrun") jobRunOnShow();
            if (tab === "jobs") jobsOnShow();
            if (tab === "joblog") jobLogOnShow();
        });
    }

    // ── Project perspective, right pane: run a workflow, list the jobs, read a job's log ────
    // (ProjectPerspective_260911_oo01, step 4). A job is one workflow run as the project's child
    // actor; the Project actor owns the list. Lists auto-refresh every 3s while their tab shows.
    var jobLogSelected = null;
    var jobsTimer = null;
    var jobLogTimer = null;

    function projectJobsUrl(suffix) {
        if (!perspectiveProjectId) return null;
        return "api/projects/" + encodeURIComponent(perspectiveProjectId) + "/jobs" + (suffix || "");
    }
    function setText(id, text) { var el = document.getElementById(id); if (el) el.textContent = text || ""; }
    function tabShowing(id) {
        var el = document.getElementById(id);
        return !!el && el.classList.contains("active") && perspective === "project";
    }

    // Run tab: mirrors what the centre pane has open.
    function jobRunOnShow() {
        setText("jobrun-workflow", projectWfOpenName || "(none open)");
        var btn = document.getElementById("jobrun-run");
        if (btn) btn.disabled = !projectWfOpenName;
    }

    function jobRun() {
        var url = projectJobsUrl("");
        if (!url || !projectWfOpenName) return;
        setText("jobrun-status", "starting…");
        fetch(url, { method: "POST", headers: { "Content-Type": "application/json" },
                     body: JSON.stringify({ workflow: projectWfOpenName }) })
            .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); })
            .then(function (res) {
                if (!res.ok) { setText("jobrun-status", (res.body && res.body.error) || ("HTTP " + res.status)); return; }
                setText("jobrun-status", "started " + res.body.jobId);
                jobLogSelected = res.body.jobId;
                jobsOnShow();
            })
            .catch(function (e) { setText("jobrun-status", "error: " + e.message); });
    }

    function fmtInstant(s) {
        if (!s) return "";
        var d = new Date(s);
        if (isNaN(d.getTime())) return s;
        var p = function (n) { return String(n).padStart(2, "0"); };
        return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
    }

    function jobRowEl(j) {
        var row = document.createElement("div");
        row.className = "job-row job-" + (j.state || "").toLowerCase() + (j.jobId === jobLogSelected ? " selected" : "");
        var head = document.createElement("div");
        head.className = "job-head";
        var id = document.createElement("span"); id.className = "job-id"; id.textContent = j.jobId;
        var wf = document.createElement("span"); wf.className = "job-wf"; wf.textContent = j.workflow || "";
        var st = document.createElement("span"); st.className = "job-state"; st.textContent = j.state || "";
        var when = document.createElement("span"); when.className = "job-when";
        when.textContent = fmtInstant(j.startedAt) + (j.finishedAt ? " → " + fmtInstant(j.finishedAt) : "");
        head.appendChild(id); head.appendChild(wf); head.appendChild(st); head.appendChild(when);
        var actions = document.createElement("span");
        actions.className = "job-actions";
        var logBtn = document.createElement("button");
        logBtn.type = "button"; logBtn.textContent = "Log"; logBtn.title = "Show this job's log";
        logBtn.addEventListener("click", function (e) {
            e.stopPropagation();
            jobLogSelected = j.jobId;
            var tab = document.querySelector('#right-tab-bar .rtab-btn[data-tab="joblog"]');
            if (tab) tab.click();
        });
        actions.appendChild(logBtn);
        if (j.state === "RUNNING") {
            var stop = document.createElement("button");
            stop.type = "button"; stop.textContent = "Stop"; stop.title = "Ask this job to stop between steps";
            stop.addEventListener("click", function (e) {
                e.stopPropagation();
                fetch(projectJobsUrl("/" + encodeURIComponent(j.jobId) + "/stop"), { method: "POST" })
                    .then(function (r) { return r.json().then(function (b) { setText("jobs-status", r.ok ? "stop requested for " + j.jobId : ((b && b.error) || ("HTTP " + r.status))); }); })
                    .catch(function (err) { setText("jobs-status", "error: " + err.message); })
                    .finally(jobsLoad);
            });
            actions.appendChild(stop);
        }
        head.appendChild(actions);
        row.appendChild(head);
        if (j.result) {
            var res = document.createElement("div");
            res.className = "job-result";
            res.textContent = j.result;
            row.appendChild(res);
        }
        row.addEventListener("click", function () {
            jobLogSelected = j.jobId;
            document.querySelectorAll("#jobs-list .job-row").forEach(function (r) { r.classList.toggle("selected", r === row); });
        });
        return row;
    }

    var jobsLoading = false;
    function jobsLoad() {
        var list = document.getElementById("jobs-list");
        var url = projectJobsUrl("");
        if (!list || !url || jobsLoading) return;
        jobsLoading = true;
        fetch(url)
            .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
            .then(function (jobs) {
                list.textContent = "";
                if (!jobs || !jobs.length) {
                    var empty = document.createElement("div");
                    empty.className = "project-placeholder";
                    empty.textContent = "No jobs yet. Open a workflow and Run it.";
                    list.appendChild(empty);
                } else {
                    jobs.forEach(function (j) { list.appendChild(jobRowEl(j)); });
                }
                var running = (jobs || []).filter(function (j) { return j.state === "RUNNING"; }).length;
                setText("jobs-status", (jobs || []).length + " job(s), " + running + " running");
            })
            .catch(function (e) { setText("jobs-status", "error: " + e.message); })
            .finally(function () { jobsLoading = false; });
    }

    function jobsOnShow() {
        jobsLoad();
        jobsApplyAuto();
    }

    function jobsApplyAuto() {
        var auto = document.getElementById("jobs-auto");
        if (jobsTimer) { clearInterval(jobsTimer); jobsTimer = null; }
        if (auto && auto.checked) {
            jobsTimer = setInterval(function () { if (tabShowing("tab-jobs")) jobsLoad(); }, 3000);
        }
    }

    function jobLogRender(entries) {
        var list = document.getElementById("joblog-list");
        if (!list) return;
        list.textContent = "";
        (entries || []).forEach(function (e) {
            var line = document.createElement("div");
            line.className = "joblog-line joblog-" + String(e.type || "").toLowerCase();
            var t = document.createElement("span"); t.className = "joblog-time"; t.textContent = fmtLogTime(e.time);
            var ty = document.createElement("span"); ty.className = "joblog-type"; ty.textContent = e.type || "";
            var d = document.createElement("span"); d.className = "joblog-data"; d.textContent = e.data || "";
            line.appendChild(t); line.appendChild(ty); line.appendChild(d);
            list.appendChild(line);
        });
        list.scrollTop = list.scrollHeight;
    }

    var jobLogLoading = false;
    function jobLogLoad() {
        setText("joblog-job", jobLogSelected || "(none selected)");
        if (!jobLogSelected) { setText("joblog-status", "pick a job under Batch Jobs"); return; }
        var url = projectJobsUrl("/" + encodeURIComponent(jobLogSelected) + "/log");
        if (!url || jobLogLoading) return;
        jobLogLoading = true;
        fetch(url)
            .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
            .then(function (entries) {
                jobLogRender(entries);
                setText("joblog-status", (entries || []).length + " line(s)");
            })
            .catch(function (e) { setText("joblog-status", "error: " + e.message); })
            .finally(function () { jobLogLoading = false; });
    }

    function jobLogOnShow() {
        jobLogLoad();
        jobLogApplyAuto();
    }

    function jobLogApplyAuto() {
        var auto = document.getElementById("joblog-auto");
        if (jobLogTimer) { clearInterval(jobLogTimer); jobLogTimer = null; }
        if (auto && auto.checked) {
            jobLogTimer = setInterval(function () { if (tabShowing("tab-joblog")) jobLogLoad(); }, 3000);
        }
    }

    function initJobs() {
        var run = document.getElementById("jobrun-run");
        if (run) run.addEventListener("click", jobRun);
        var jr = document.getElementById("jobs-refresh");
        if (jr) jr.addEventListener("click", jobsLoad);
        var ja = document.getElementById("jobs-auto");
        if (ja) ja.addEventListener("change", jobsApplyAuto);
        var lr = document.getElementById("joblog-refresh");
        if (lr) lr.addEventListener("click", jobLogLoad);
        var la = document.getElementById("joblog-auto");
        if (la) la.addEventListener("change", jobLogApplyAuto);
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

    // Draws a workflow as boxes into any container. Shared by the Agent Loop tab and the project
    // pane (ProjectPerspective_260911_oo01). Returns the step count for the caller's status line.
    function wfRenderInto(container, yaml) {
        if (!container) return 0;
        container.textContent = "";
        var parts = wfSplitSteps(yaml);
        if (parts.preamble) wfRenderBox(container, "workflow header", parts.preamble, "head");
        parts.steps.forEach(function (s, i) {
            wfRenderBox(container, wfStepTitle(s, i), s, "step");
        });
        return parts.steps.length;
    }

    function wfRender(yaml) {
        var n = wfRenderInto(document.getElementById("wf-list"), yaml);
        wfStatus(n + " step(s) — read-only");
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

    // ── Perspective: what the centre and right panes show (ProjectPerspective_260911_oo01) ──
    // "chat" shows one conversation (#left-panel and the chat-scoped right tabs); "project" shows
    // one project (#project-panel and the project-scoped right tabs). Persisted like the theme, so
    // a reload comes back to the same view.
    var PERSPECTIVE_KEY = "chat-ui-perspective";
    var PERSPECTIVE_PROJECT_KEY = "chat-ui-perspective-project";
    var perspective = "chat";
    var perspectiveProjectId = null;

    function switchPerspective(kind, projectId) {
        if (kind !== "project") kind = "chat";
        var root = document.getElementById("console-root");
        if (!root) return;
        perspective = kind;
        if (kind === "project" && projectId) perspectiveProjectId = projectId;
        root.setAttribute("data-perspective", kind);
        localStorage.setItem(PERSPECTIVE_KEY, kind);
        if (perspectiveProjectId) localStorage.setItem(PERSPECTIVE_PROJECT_KEY, perspectiveProjectId);
        var title = document.getElementById("project-panel-title");
        if (title && kind === "project") title.textContent = perspectiveProjectId || "Project";
        activateFirstTabOf(kind);
        if (kind === "project") projectWfOnShow();
    }

    // ── Project perspective, centre pane: the project's workflows ─────────────
    // GET api/projects/{p}/workflows lists them (the project's own under workflows/ in its working
    // directory first, then the bundled ones); GET .../workflows/{name} reads one
    // (ProjectPerspective_260911_oo01, step 2). Drawn with the same boxes as the Agent Loop tab.
    var projectWfOpenName = null;

    function projectWfUrl(suffix) {
        if (!perspectiveProjectId) return null;
        return "api/projects/" + encodeURIComponent(perspectiveProjectId) + "/workflows" + (suffix || "");
    }

    function projectWfStatus(msg) {
        var s = document.getElementById("project-wf-status");
        if (s) s.textContent = msg || "";
    }

    function projectWfRowEl(w) {
        var row = document.createElement("div");
        row.className = "pwf-row" + (w.name === projectWfOpenName ? " selected" : "");
        var name = document.createElement("span");
        name.className = "pwf-name";
        name.textContent = w.title && w.title !== w.name ? w.title + "  (" + w.name + ")" : w.name;
        var origin = document.createElement("span");
        origin.className = "pwf-origin pwf-origin-" + (w.origin || "");
        origin.textContent = w.origin === "project" ? "project" : "bundled";
        origin.title = w.origin === "project"
            ? "This project's own file, under workflows/ in its working directory. Editable."
            : "Shipped with this program. Read-only.";
        var desc = document.createElement("div");
        desc.className = "pwf-desc";
        desc.textContent = w.description || "";
        row.appendChild(name);
        row.appendChild(origin);
        if (w.description) row.appendChild(desc);
        row.addEventListener("click", function () { projectWfOpen(w.name); });
        return row;
    }

    function projectWfLoadList() {
        var list = document.getElementById("project-wf-list");
        var search = document.getElementById("project-wf-search");
        if (!list) return;
        var q = search ? search.value.trim() : "";
        var url = projectWfUrl(q ? "?q=" + encodeURIComponent(q) : "");
        if (!url) { projectWfStatus("no project selected"); return; }
        projectWfStatus("loading…");
        fetch(url)
            .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
            .then(function (rows) {
                list.textContent = "";
                (rows || []).forEach(function (w) { list.appendChild(projectWfRowEl(w)); });
                var own = (rows || []).filter(function (w) { return w.origin === "project"; }).length;
                projectWfStatus((rows || []).length + " workflow(s), " + own + " of this project"
                    + (q ? " matching “" + q + "”" : ""));
            })
            .catch(function (e) { projectWfStatus("error: " + e.message); });
    }

    // The workflow last opened, as the server sent it: {name, yaml, origin, editable}. What Edit
    // starts from.
    var projectWfDoc = null;

    function projectWfOpen(name) {
        var url = projectWfUrl("/" + encodeURIComponent(name));
        var head = document.getElementById("project-wf-head");
        var view = document.getElementById("project-wf-view");
        if (!url || !view) return;
        projectWfOpenName = name;
        projectWfEditorHide();
        document.querySelectorAll("#project-wf-list .pwf-row").forEach(function (r) {
            r.classList.toggle("selected", r.querySelector(".pwf-name") && r.querySelector(".pwf-name").textContent.indexOf(name) >= 0);
        });
        if (head) head.textContent = "loading " + name + "…";
        fetch(url)
            .then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
            .then(function (d) {
                projectWfDoc = d;
                var n = wfRenderInto(view, d.yaml || "");
                if (head) head.textContent = d.name + "  —  " + n + " step(s), "
                    + (d.editable ? "this project's own file" : "bundled, read-only");
                view.scrollTop = 0;
                var edit = document.getElementById("project-wf-edit");
                if (edit) {
                    edit.disabled = false;
                    edit.textContent = d.editable ? "Edit" : "Edit a copy";
                    edit.title = d.editable
                        ? "Edit this file"
                        : "Bundled workflows are read-only; saving creates this project's own copy, which then replaces it";
                }
                jobRunOnShow(); // the Run tab mirrors what is open here
            })
            .catch(function (e) { if (head) head.textContent = "error: " + e.message; projectWfDoc = null; });
    }

    // ── Editing (ProjectPerspective_260911_oo01, step 3) ──
    // The editor takes the reader's place. Save PUTs the text as-is; the server refuses anything
    // Turing-workflow cannot read, and its reason is shown next to the buttons.
    var WF_TEMPLATE = "name: my-workflow\ndescription: |\n  What this workflow does.\nsteps:\n"
        + "  - states: [\"0\", \"1\"]\n    label: first-step\n    actions:\n"
        + "      - actor: this\n        method: noop\n        arguments: []\n        execution: direct\n";

    function projectWfEditStatus(msg, isError) {
        var s = document.getElementById("project-wf-edit-status");
        if (!s) return;
        s.textContent = msg || "";
        s.classList.toggle("pwf-error", !!isError);
    }

    function projectWfEditorShow(name, yaml, hint) {
        var editor = document.getElementById("project-wf-editor");
        var view = document.getElementById("project-wf-view");
        var nameEl = document.getElementById("project-wf-edit-name");
        var yamlEl = document.getElementById("project-wf-edit-yaml");
        if (!editor || !view || !nameEl || !yamlEl) return;
        nameEl.value = name || "";
        yamlEl.value = yaml || "";
        view.style.display = "none";
        editor.style.display = "";
        projectWfEditStatus(hint || "");
        (name ? yamlEl : nameEl).focus();
    }

    function projectWfEditorHide() {
        var editor = document.getElementById("project-wf-editor");
        var view = document.getElementById("project-wf-view");
        if (editor) editor.style.display = "none";
        if (view) view.style.display = "";
    }

    function projectWfEdit() {
        if (!projectWfDoc) return;
        projectWfEditorShow(projectWfDoc.name, projectWfDoc.yaml,
            projectWfDoc.editable ? "" : "Saving creates this project's own copy of this bundled workflow.");
    }

    function projectWfNew() {
        projectWfEditorShow("", WF_TEMPLATE, "Give it a name, then Save.");
    }

    function projectWfSave() {
        var nameEl = document.getElementById("project-wf-edit-name");
        var yamlEl = document.getElementById("project-wf-edit-yaml");
        if (!nameEl || !yamlEl) return;
        var name = nameEl.value.trim();
        if (!name) { projectWfEditStatus("a name is required", true); nameEl.focus(); return; }
        if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name)) {
            projectWfEditStatus("a name is letters, digits, '.', '_' and '-' only", true); nameEl.focus(); return;
        }
        var url = projectWfUrl("/" + encodeURIComponent(name));
        if (!url) { projectWfEditStatus("no project selected", true); return; }
        projectWfEditStatus("saving…");
        fetch(url, { method: "PUT", headers: { "Content-Type": "text/plain; charset=utf-8" }, body: yamlEl.value })
            .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, status: r.status, body: j }; }); })
            .then(function (res) {
                if (!res.ok) {
                    projectWfEditStatus((res.body && res.body.error) || ("HTTP " + res.status), true);
                    return;
                }
                projectWfEditStatus("saved");
                projectWfLoadList();
                projectWfOpen(name);
            })
            .catch(function (e) { projectWfEditStatus("error: " + e.message, true); });
    }

    function initProjectWorkflowEditor() {
        var edit = document.getElementById("project-wf-edit");
        var neu = document.getElementById("project-wf-new");
        var save = document.getElementById("project-wf-save");
        var cancel = document.getElementById("project-wf-cancel");
        if (edit) edit.addEventListener("click", projectWfEdit);
        if (neu) neu.addEventListener("click", projectWfNew);
        if (save) save.addEventListener("click", projectWfSave);
        if (cancel) cancel.addEventListener("click", projectWfEditorHide);
    }

    function projectWfOnShow() {
        projectWfLoadList();
    }

    function initProjectWorkflows() {
        var search = document.getElementById("project-wf-search");
        var refresh = document.getElementById("project-wf-refresh");
        var debounce = null;
        if (search) search.addEventListener("input", function () {
            if (debounce) clearTimeout(debounce);
            debounce = setTimeout(projectWfLoadList, 200);
        });
        if (refresh) refresh.addEventListener("click", function () {
            projectWfLoadList();
            if (projectWfOpenName) projectWfOpen(projectWfOpenName);
        });
    }

    // The right pane keeps one active tab per perspective. After a switch, the tab that was
    // active may belong to the other perspective and be hidden now; if none of the new
    // perspective's tabs is active, pick its first. Clicking goes through initTabs' handler, so
    // the tab's own onShow runs as if the user had clicked it.
    function activateFirstTabOf(kind) {
        var bar = document.getElementById("right-tab-bar");
        if (!bar) return;
        var btns = Array.prototype.slice.call(
            bar.querySelectorAll('.rtab-btn[data-perspective="' + kind + '"]'));
        if (!btns.length) return;
        var already = btns.filter(function (b) { return b.classList.contains("active"); })[0];
        if (!already) btns[0].click();
    }

    function restorePerspective() {
        var kind = localStorage.getItem(PERSPECTIVE_KEY) || "chat";
        var pid = localStorage.getItem(PERSPECTIVE_PROJECT_KEY);
        if (kind === "project" && !pid) kind = "chat";
        perspectiveProjectId = pid;
        switchPerspective(kind, pid);
    }

    // ── Actors tab ──────────────────────────────────────────────────────────
    // Each node: {name, type, alive, children[]}. Collapsed state is keyed by actor name (unique
    // in this actor system) and kept outside the tree DOM, so it survives the full rebuild
    // renderActorTree() does on every refresh (including the 3s auto-refresh timer).
    var collapsedActorNodes = new Set();

    // Actor names are absolute ("project1/chat-01.chat.promptBuilder"), so every row would repeat
    // its ancestors. The tree's indentation already shows the hierarchy: strip the parent's name
    // from the front and show only what this node adds. The full name stays in the tooltip.
    function actorDisplayName(node, parentName) {
        var n = node.name;
        if (parentName && n.indexOf(parentName) === 0 && n.length > parentName.length) {
            n = n.substring(parentName.length).replace(/^[./]+/, "");
        }
        return n || node.name;
    }

    function actorNodeEl(node, parentName) {
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
        name.textContent = actorDisplayName(node, parentName);
        // The note of the workflow transition that created this actor, if a workflow created it:
        // what its author said that step was for (ActorPurposeFromWorkflowNote_260831_oo01).
        // Actors Java created carry none, and then the tooltip falls back to the class name.
        label.title = node.note ? (node.name + "\n\n" + node.note) : (node.name + "\n\n" + (node.type || ""));
        if (node.note) label.classList.add("actor-has-note");
        // "chat-<id>" (ConversationTab) nodes double as the tab switcher — click the name (not
        // the fold toggle) to switch the chat pane, instead of a separate bar in that pane
        // (ActorTreeTabSwitcher_260826_oo01). Children like "chat-<id>.chat" don't match.
        var tabMatch = /^([^/]+)\/chat-([^.]+)$/.exec(node.name);
        if (tabMatch && typeof window.chatUiSwitchChat === "function") {
            name.classList.add("tab-switchable");
            var active = (typeof window.chatUiGetActiveChat === "function") ? window.chatUiGetActiveChat() : null;
            if (perspective === "chat" && active
                    && active.projectId === tabMatch[1] && active.chatId === tabMatch[2]) {
                name.classList.add("tab-active");
            }
            name.title = "Switch to " + tabMatch[1] + " / " + tabMatch[2];
            name.addEventListener("click", function (e) {
                e.stopPropagation(); // don't also trigger the fold/unfold toggle on the label
                window.chatUiSwitchChat(tabMatch[1], tabMatch[2]);
                // A conversation is shown in the chat perspective; leave the project one if in it
                // (ProjectPerspective_260911_oo01).
                switchPerspective("chat");
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
        // A Project node switches the whole console to that project's perspective
        // (ProjectPerspective_260911_oo01): the centre pane shows its workflows, the right pane
        // its jobs. Matched by type rather than by name, so a project named anything switches.
        if (node.type === "Project") {
            name.classList.add("tab-switchable");
            if (perspective === "project" && perspectiveProjectId === node.name) {
                name.classList.add("tab-active");
            }
            name.title = "Show project " + node.name;
            name.addEventListener("click", function (e) {
                e.stopPropagation(); // don't also trigger the fold/unfold toggle on the label
                switchPerspective("project", node.name);
                refreshActors(); // re-render so the tab-active highlight moves immediately
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
            node.children.forEach(function (c) { kids.appendChild(actorNodeEl(c, node.name)); });
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
            ioLoadTurnWindow(body, s.sessionId, 0, false)
                .then(function () { return ioLoadSettings(body, s.sessionId); })
                .catch(function (err) { body.textContent = "error: " + err.message; loaded = false; });
        });
        return det;
    }

    /**
     * Puts the conversation's settings history above its turns: each time the provider, tool set
     * or model was set, with the values (ConversationSettingsRecord_260913_oo01). Nothing is
     * drawn for a session that never changed them.
     */
    function ioLoadSettings(el, sessionId) {
        return fetch("api/sessions/" + sessionId + "/settings")
            .then(function (r) { return r.json(); })
            .then(function (records) {
                if (!records || !records.length) return;
                var box = document.createElement("div"); box.className = "sess-settings";
                var head = document.createElement("div"); head.className = "sess-settings-head";
                head.textContent = "Settings (" + records.length + ")";
                box.appendChild(head);
                records.forEach(function (rec) {
                    var line = document.createElement("div"); line.className = "sess-settings-line";
                    line.textContent = (rec.time || "").replace("T", " ").slice(0, 19) + "  ·  provider "
                            + (rec.provider || "?") + "  ·  tools " + (rec.tools || "?")
                            + "  ·  model " + (rec.model || "?");
                    box.appendChild(line);
                });
                el.insertBefore(box, el.firstChild);
            })
            .catch(function () { /* the turns are already there; settings are extra */ });
    }

    // How many turns a session shows at a time. A session in this archive runs to 1,417 of them;
    // drawing every turn with its messages open put all of that in the page at once.
    var IO_TURN_WINDOW = 20;

    /**
     * Draws a window of a session's turns into `el`, newest first. `before` is the turn to read
     * back from (0 = the newest); `append` keeps what is already there and adds older ones below.
     */
    function ioLoadTurnWindow(el, sessionId, before, append) {
        if (!append) el.textContent = "loading…";
        return fetch("api/sessions/" + sessionId + "/turns?before=" + before
                     + "&limit=" + IO_TURN_WINDOW)
            .then(function (r) { return r.json(); })
            .then(function (d) {
                var turns = (d && d.turns) || [];
                if (!append) el.textContent = "";
                var more = el.querySelector(".tr-more");
                if (more) more.remove();
                if (!turns.length && !append) {
                    var none = document.createElement("div"); none.className = "io-empty";
                    none.textContent = "No agent-loop trace in this session.";
                    el.appendChild(none);
                    return;
                }
                turns.forEach(function (h) { el.appendChild(ioTurnEl(h, sessionId)); });
                var oldest = turns.length ? turns[turns.length - 1].turn : 0;
                if (oldest > 1) {
                    var btn = document.createElement("button");
                    btn.type = "button"; btn.className = "tr-more";
                    btn.textContent = "older turns (" + (oldest - 1) + " before this)";
                    btn.addEventListener("click", function () {
                        btn.disabled = true;
                        ioLoadTurnWindow(el, sessionId, oldest, true);
                    });
                    el.appendChild(btn);
                }
            });
    }

    /**
     * One turn as a row: its number and what was asked. Its messages are fetched and shown under
     * it when it is opened, and only one turn is open at a time, so what is in the page does not
     * depend on how many turns the session has.
     */
    function ioTurnEl(head, sessionId) {
        var box = document.createElement("details"); box.className = "tr-turn";
        var sum = document.createElement("summary"); sum.className = "tr-turn-head";
        var num = document.createElement("span"); num.className = "tr-turn-num";
        num.textContent = "Turn " + head.turn;
        var q = document.createElement("span"); q.className = "tr-turn-q";
        q.textContent = head.question || "(no question recorded)";
        q.title = q.textContent;
        sum.appendChild(num); sum.appendChild(q);
        box.appendChild(sum);
        var body = document.createElement("div"); box.appendChild(body);
        var loaded = false;
        box.addEventListener("toggle", function () {
            if (!box.open) {
                // Closed turns give their messages back. Kept, they pile up hidden — a tool row's
                // line carries its whole input, and fifty read turns held fifty of those.
                body.textContent = "";
                loaded = false;
                return;
            }
            // One turn open at a time: two turns of messages is already more than the pane holds,
            // and the point of the window is that the page does not grow with the session.
            box.parentNode.querySelectorAll("details.tr-turn[open]").forEach(function (other) {
                if (other !== box) other.open = false;
            });
            if (loaded) return;
            loaded = true;
            body.textContent = "loading…";
            fetch("api/sessions/" + sessionId + "/trace/" + head.turn)
                .then(function (r) { return r.json(); })
                .then(function (t) {
                    body.textContent = "";
                    ioTurnMessages(t).forEach(function (m) {
                        body.appendChild(ioMsgEl(m, sessionId));
                    });
                })
                .catch(function (err) { body.textContent = "error: " + err.message; loaded = false; });
        });
        return box;
    }

    // Flattens a turn into an ordered list of one-direction messages: an llm step -> (loop→LLM
    // request) + (LLM→loop reply); a tool step -> (loop→tool input) + (tool→loop observation).
    // One line of a summary: the whole text is read by picking the message, so its row carries a
    // bounded slice. tail() rather than a head for what the person sent — this program folds the
    // system prompt in front of the question, so the head is the same in every turn.
    function ioTrim(s, n) { s = s || ""; return s.length > n ? s.slice(0, n) + "…" : s; }
    function ioTail(s, n) { s = s || ""; return s.length > n ? "…" + s.slice(-n) : s; }

    function ioTurnMessages(t) {
        var out = [];
        var firstLlm = (t.steps || []).filter(function (s) { return s.kind === "llm"; })[0];
        out.push({ dir: "user → loop", cls: "user",
                   summary: "🗣 " + (ioTail(t.userPrompt, 200) || "(user prompt not found)"),
                   id: firstLlm ? firstLlm.id : -1, part: "USER" });
        (t.steps || []).forEach(function (s) {
            if (s.kind === "tool") {
                out.push({ dir: "loop → tool", cls: "to-tool",
                           summary: "↳ " + s.toolName + "(" + ioTrim(s.toolInput, 200) + ")",
                           id: s.id, part: "INPUT" });
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

    // One message is a row that is picked, not a block that opens where it sits. Opened in place,
    // each one pushed everything below it further down and the session and turn it belongs to off
    // the top — five of them made this list six screens tall. The one picked is shown below, in
    // #io-reading, so only ever one is open and the structure above it never moves.
    function ioMsgEl(m, sessionId) {
        var row = document.createElement("div"); row.className = "trm " + m.cls;
        var sum = document.createElement("div"); sum.className = "trm-sum";
        var dir = document.createElement("span"); dir.className = "trm-dir"; dir.textContent = m.dir;
        var txt = document.createElement("span"); txt.className = "trm-txt"; txt.textContent = m.summary;
        sum.appendChild(dir); sum.appendChild(txt);
        row.appendChild(sum);
        sum.addEventListener("click", function () { ioReadMessage(row, m, sessionId); });
        return row;
    }

    // Shows one message in the reading pane below the list.
    function ioReadMessage(row, m, sessionId) {
        document.querySelectorAll("#io-sessions .trm.selected").forEach(function (el) {
            el.classList.remove("selected");
        });
        row.classList.add("selected");
        var head = document.getElementById("io-reading-head");
        var body = document.getElementById("io-reading-body");
        head.textContent = m.dir + "  ·  " + m.part;
        head.title = head.textContent;
        body.textContent = "";
        if (m.id < 0) {
            body.textContent = "(no source entry)";
            return;
        }
        var pending = document.createElement("div"); pending.textContent = "loading…";
        body.appendChild(pending);
        fetch("api/sessions/" + sessionId + "/entry/" + m.id)
            .then(function (r) { return r.json(); })
            .then(function (d) { ioRenderPart(body, d.message || "", m.part); body.scrollTop = 0; })
            .catch(function (err) { body.textContent = "error: " + err.message; });
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
        initProjectWorkflows();
        initProjectWorkflowEditor();
        initJobs();
        restorePerspective(); // before the tree renders, so its highlight matches
        refreshActors();   // the actor dock is visible by default
        ioOnShow();         // Sessions is the default-active right-pane tab
    });
})();

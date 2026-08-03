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

    document.addEventListener("DOMContentLoaded", function () {
        initTabs();
        initActors();
        initDock();
        refreshActors();   // the actor dock is visible by default
    });
})();

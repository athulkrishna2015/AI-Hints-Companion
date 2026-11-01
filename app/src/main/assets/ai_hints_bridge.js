/*
 * AI-Hints Companion — reviewer bridge
 *
 * Installed into card templates as:
 *   <script src="http://127.0.0.1:8766/bridge.js"></script>
 *   <script src="http://127.0.0.1:8766/template.js"></script>
 *
 * This file must run BEFORE template.js and must synchronously define
 * window.aiHintsUiConfig, because template.js samples it at parse time.
 * Everything after that is asynchronous: identify the card through
 * AnkiDroid's JavaScript API, ask the companion app for the card state, and
 * then hand it to the unmodified add-on renderer.
 */
(function () {
    "use strict";

    var script = document.currentScript || (function () {
        var tags = document.getElementsByTagName("script");
        for (var i = tags.length - 1; i >= 0; i--) {
            if (tags[i].src && tags[i].src.indexOf("/bridge.js") !== -1) return tags[i];
        }
        return null;
    })();
    var BASE = script && script.src
        ? script.src.replace(/\/bridge\.js(?:[?#].*)?$/, "")
        : "http://127.0.0.1:8766";

    var CARD = { nid: null, id: null, ord: 0, isAnswer: false };
    var events = null;
    var booted = false;
    var lastSignature = "";
    var banner = null;

    // Last successful /state payload. The floating action panel is rendered
    // from this, so it only appears once the bridge has actually answered.
    var lastState = null;
    var lastHints = null;
    var isGenerating = false;
    var toolbar = null;
    var panel = null;
    var panelOpen = false;
    var modelSheet = null;
    var toolbarButtons = {};

    // ---------------------------------------------------------------- ui config
    // Defaults mirror _get_ui_config() in reviewer_hooks.py. template.js reads
    // this synchronously, so every key it touches must exist here.
    window.aiHintsUiConfig = window.aiHintsUiConfig || {
        show_on_card: true,
        auto_reveal: false,
        auto_show_hints: false,
        auto_show_options: true,
        auto_show_hints_answer: true,
        auto_show_options_answer: true,
        options_before_hints: true,
        generation_enabled: true,
        rate_good_on_correct: false,
        rate_again_on_wrong: false,
        rate_good_delay_ms: 0,
        rate_again_delay_ms: 1000,
        manual_show_hints: true,
        manual_show_options: false,
        mathjax_format: "delimiters",
        fix_latex: false,
        review_token: "",
        is_generating: false,
        is_pregenerating: false,
        is_lingering: false,
        is_answer_side: undefined,
        hints_font_size: "",
        answer_display_position: "between",
        has_data: false,
        model_choices: [],
        shortcuts: {}
    };

    // ------------------------------------------------------------------ pycmd
    var nativePycmd = typeof window.pycmd === "function" ? window.pycmd : null;

    window.pycmd = function (command) {
        try {
            if (command === "ans") {
                revealAnswer();
                return;
            }
            if (command === "ai_hints_rate_good") {
                answerEase(3);
                return;
            }
            if (command === "ai_hints_rate_again") {
                answerEase(1);
                return;
            }

            var payload = null;
            if (typeof command === "string" && command.charAt(0) === "{") {
                try { payload = JSON.parse(command); } catch (e) { payload = null; }
            }
            var action = payload && payload.action ? payload.action : command;

            if (typeof action !== "string" || action.indexOf("ai_hints_") !== 0) {
                if (nativePycmd) nativePycmd(command);
                return;
            }
            if (action === "ai_hints_model_picker") {
                // Desktop-only hotmouse pause signal; nothing to do on touch.
                return;
            }

            var mapped;
            switch (action) {
                case "ai_hints_generate": mapped = { action: "generate" }; break;
                case "ai_hints_cancel": mapped = { action: "cancel" }; break;
                case "ai_hints_clear": mapped = { action: "clear" }; break;
                case "ai_hints_skip": mapped = { action: "skip" }; break;
                case "ai_hints_refresh": mapped = { action: "refresh", isAnswer: CARD.isAnswer }; break;
                case "ai_hints_edit_item":
                    mapped = {
                        action: "edit_item",
                        type: payload.type,
                        index: payload.index,
                        value: payload.value
                    };
                    break;
                case "ai_hints_add_item":
                    mapped = { action: "add_item", type: payload.type, value: payload.value };
                    break;
                case "ai_hints_generate_override":
                    mapped = {
                        action: "generate_override",
                        provider: payload.provider,
                        model: payload.model
                    };
                    break;
                default:
                    return;
            }
            sendCommand(mapped);
        } catch (e) {
            console.error("AI-Hints bridge: pycmd failed", e);
        }
    };

    // ------------------------------------------------------------- AnkiDroid JS
    var api = null;
    var apiTried = false;

    function getApi() {
        if (api && api !== null) return api;
        if (typeof window.AnkiDroidJS !== "undefined") {
            var versions = ["0.0.3", "0.0.2"];
            for (var v = 0; v < versions.length; v++) {
                try {
                    api = new window.AnkiDroidJS({
                        version: versions[v],
                        developer: "ai-hints-companion"
                    });
                    return api;
                } catch (e) {
                    api = null;
                }
            }
        }
        // Fallback: old synchronous API
        if (typeof window.ankiGetCardNid === "function") {
            api = { ankiGetCardNid: function () { return window.ankiGetCardNid(); } };
            return api;
        }
        return null;
    }

    async function apiCall(name) {
        var bridge = getApi();
        if (!bridge || typeof bridge[name] !== "function") return null;
        try {
            var result = await bridge[name]();
            if (result && typeof result === "object" && Object.prototype.hasOwnProperty.call(result, "value")) {
                return result.success === false ? null : result.value;
            }
            return result;
        } catch (e) {
            return null;
        }
    }

    // -------------------------------------------------------------- card identity
    function detectOrd() {
        var match = (document.body && document.body.className || "").match(/\bcard(\d+)\b/);
        if (match) return Math.max(0, parseInt(match[1], 10) - 1);
        var qa = document.getElementById("qa_box");
        if (qa) {
            match = (qa.className || "").match(/\bcard(\d+)\b/);
            if (match) return Math.max(0, parseInt(match[1], 10) - 1);
        }
        var cloze = document.querySelector(".cloze");
        if (cloze) {
            match = (cloze.className || "").match(/\bc(\d+)\b/);
            if (match) return Math.max(0, parseInt(match[1], 10) - 1);
        }
        return 0;
    }

    function detectAnswerDom() {
        if (document.getElementById("answer")) return true;
        if (document.body && document.body.classList && document.body.classList.contains("answer")) return true;
        if (document.querySelector("#qa_box.answer")) return true;
        return false;
    }

    async function detectAnswer() {
        var fromApi = await apiCall("ankiIsDisplayingAnswer");
        if (typeof fromApi === "boolean") return fromApi;
        return detectAnswerDom();
    }

    // ------------------------------------------------------------------- bridge IO
    function toast(message) {
        if (typeof window.ankiShowToast === "function") {
            try { window.ankiShowToast(String(message)); return; } catch (e) { /* fall through */ }
        }
        showBanner(message);
    }

    function showBanner(message) {
        if (banner && banner.parentNode) banner.parentNode.removeChild(banner);
        banner = document.createElement("div");
        banner.id = "ai-hints-bridge-banner";
        banner.textContent = message;
        banner.style.cssText = [
            "display:block", "margin:8px 0", "padding:10px 12px", "border-radius:10px",
            "font:13px/1.35 sans-serif", "background:rgba(180,83,9,.95)", "color:#fff",
            "box-shadow:0 4px 14px rgba(0,0,0,.25)"
        ].join(";");
        banner.addEventListener("click", function () {
            if (banner && banner.parentNode) banner.parentNode.removeChild(banner);
            banner = null;
        });
        var host = document.getElementById("qa") || document.body || document.documentElement;
        host.appendChild(banner);
    }

    function hideBanner() {
        if (banner && banner.parentNode) banner.parentNode.removeChild(banner);
        banner = null;
    }

    async function postJson(path, payload) {
        var response = await fetch(BASE + path, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        if (!response.ok) throw new Error("HTTP " + response.status);
        return await response.json();
    }

    function sendCommand(payload) {
        if (CARD.nid === null) {
            showBanner("AI-Hints: cannot identify this card. Disable 'New study screen' in AnkiDroid settings and enable the JavaScript API.");
            return Promise.resolve(null);
        }
        return postJson("/cmd", {
            nid: CARD.nid,
            ord: CARD.ord,
            command: payload
        }).then(function (result) {
            if (result && result.ok === false && result.error) {
                toast("AI-Hints: " + result.error);
            }
            return result;
        }).catch(function () {
            showBanner("AI-Hints companion app is not running. Start it, then reload the card.");
            if (window.aiHintsSetGenerating) {
                window.aiHintsSetGenerating(false, "Offline", "Companion app not running", CARD.id, false);
            }
            return null;
        });
    }

    // ------------------------------------------------------------------- state
    function applyState(payload) {
        if (!payload || !payload.ok) return;
        var card = payload.card || { id: String(CARD.nid), ord: CARD.ord };
        CARD.id = card.id;
        lastState = payload;
        lastHints = payload.hints === undefined ? null : payload.hints;

        var config = payload.uiConfig || window.aiHintsUiConfig;
        config.is_answer_side = CARD.isAnswer;
        isGenerating = !!config.is_generating || !!config.is_lingering;

        if (typeof window.aiHintsSetup === "function") {
            window.aiHintsSetup(card, lastHints, config);
        }
        ensureToolbar();
        updateToolbar();
        try {
            injectInlineButtons();
        } catch (e) {
        }
        hideBanner();
    }

    function refreshHintsState(data) {
        lastHints = data === undefined ? null : data;
        if (lastState) lastState.hints = lastHints;
        updateToolbar();
    }

    async function refreshState() {
        if (CARD.nid === null) return;
        CARD.isAnswer = await detectAnswer();
        CARD.ord = detectOrd();
        var signature = CARD.nid + ":" + CARD.ord + ":" + (CARD.isAnswer ? "a" : "q");
        if (signature === lastSignature) return;
        lastSignature = signature;

        try {
            var payload = await postJson("/state", {
                nid: CARD.nid,
                ord: CARD.ord,
                isAnswer: CARD.isAnswer
            });
            if (!payload || !payload.ok) {
                await explainFailure(payload);
                return;
            }
            applyState(payload);
            connectEvents();
        } catch (e) {
            showBanner("AI-Hints companion app is not running. Start it, then reload the card.");
        }
    }

    async function explainFailure(payload) {
        var message = payload && payload.error ? String(payload.error) : "unknown error";
        try {
            var health = await (await fetch(BASE + "/health")).json();
            if (health && health.backend && !health.backend.available && health.backend.reason) {
                message = health.backend.reason;
            }
        } catch (e) { /* keep the original message */ }
        showBanner("AI-Hints: " + message);
    }

    // ------------------------------------------------------------------- events
    function connectEvents() {
        if (events || CARD.nid === null) return;
        if (typeof window.EventSource !== "function") return;
        try {
            events = new EventSource(BASE + "/events?nid=" + encodeURIComponent(CARD.nid));
        } catch (e) {
            events = null;
            return;
        }
        events.onmessage = function (message) {
            var event;
            try { event = JSON.parse(message.data); } catch (e) { return; }
            handleEvent(event);
        };
        events.onerror = function () {
            // EventSource reconnects on its own; nothing to do.
        };
    }

    function handleEvent(event) {
        if (!event || !event.type) return;
        switch (event.type) {
            case "generating":
                isGenerating = !!event.active;
                updateToolbar();
                if (typeof window.aiHintsSetGenerating === "function") {
                    window.aiHintsSetGenerating(
                        !!event.active,
                        event.status || null,
                        event.error || null,
                        event.cardId || null,
                        !!event.isPregen
                    );
                }
                break;
            case "data":
                refreshHintsState(event.data);
                if (typeof window.aiHintsUpdateData === "function") {
                    window.aiHintsUpdateData(event.data, !!event.isManual);
                }
                setTimeout(updateToolbar, 0);
                setTimeout(injectInlineButtons, 0);
                break;
            case "clear":
                refreshHintsState(null);
                if (typeof window.aiHintsClearData === "function") window.aiHintsClearData();
                setTimeout(updateToolbar, 0);
                setTimeout(injectInlineButtons, 0);
                break;
            case "cancelled":
                isGenerating = false;
                updateToolbar();
                break;
            case "toast":
                toast(event.message || "");
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------- answer actions
    function revealAnswer() {
        if (typeof window.showAnswer === "function") {
            try { window.showAnswer(); return; } catch (e) { /* fall through */ }
        }
        if (typeof window.AnkiDroidJS !== "undefined") {
            try { var b = new window.AnkiDroidJS({ version: "0.0.3", developer: "ai-hints-companion" }); b.ankiShowAnswer(); return; } catch (e) { /* fall through */ }
        }
    }

    function answerEase(ease) {
        if (typeof window.AnkiDroidJS !== "undefined") {
            var name = "ankiAnswerEase" + ease;
            try { var b = new window.AnkiDroidJS({ version: "0.0.3", developer: "ai-hints-companion" }); if (typeof b[name] === "function") { b[name](); return; } } catch (e) { /* fall through */ }
        }
        var legacy = window["buttonAnswerEase" + ease];
        if (typeof legacy === "function") {
            try { legacy(); return; } catch (e) { /* fall through */ }
        }
        toast("AI-Hints: this AnkiDroid version cannot answer cards from JavaScript.");
    }

    // ------------------------------------------------------- reviewer action panel
    // Desktop exposes an "AI Hints" button in the reviewer bottom bar with a menu
    // (Show Hint, Show Options, Refresh, Regenerate, Skip, Settings). AnkiDroid
    // offers no third-party toolbar API, so the same controls live in a floating
    // panel inside the card. It is only created after /state succeeds, which is
    // what "the bridge is active" means here.
    function ensureToolbar() {
        if (toolbar) return;

        panel = document.createElement("div");
        panel.id = "ai-hints-action-panel";
        panel.style.cssText = [
            "position:fixed", "right:10px", "bottom:58px", "z-index:99997",
            "display:none", "grid-template-columns:1fr 1fr", "gap:6px",
            "width:min(78vw,280px)", "padding:8px", "border-radius:14px",
            "background:rgba(255,255,255,.97)", "color:#111",
            "box-shadow:0 8px 26px rgba(0,0,0,.28)", "font:13px/1.2 sans-serif"
        ].join(";");

        toolbarButtons.generate = panelButton("Generate", "Generate hints and options", function () {
            if (isGenerating) {
                isGenerating = false;
                updateToolbar();
                sendCommand({ action: "cancel" });
                return;
            }
            isGenerating = true;
            updateToolbar();
            sendCommand({ action: "generate" }).then(function (result) {
                if (result && result.ok === false) {
                    isGenerating = false;
                    updateToolbar();
                }
            });
        });
        toolbarButtons.model = panelButton("Model\u2026", "Choose a provider and model for this generation", function () {
            openModelSheet();
        });
        toolbarButtons.hints = panelButton("Hints", "Show or hide the hints", function () {
            clickRendererControl("hints");
        });
        toolbarButtons.options = panelButton("Options", "Show or hide the options", function () {
            clickRendererControl("options");
        });
        toolbarButtons.refresh = panelButton("Refresh", "Re-read this card from the companion app", function () {
            lastSignature = "";
            refreshState();
        });
        toolbarButtons.skip = panelButton("Skip", "Skip AI generation for this card", function () {
            if (lastHints && lastHints._skipped) {
                sendCommand({ action: "unskip" });
            } else {
                askConfirm("Skip AI generation for this card?", function () {
                    sendCommand({ action: "skip" });
                });
            }
        });
        toolbarButtons.clear = panelButton("Clear", "Permanently delete AI-Hints data from this card", function () {
            askConfirm("Permanently delete hints from this card?", function () {
                sendCommand({ action: "clear" });
            });
        });
        toolbarButtons.undo = panelButton("\u21b6 Undo", "Undo the last AI-Hints change", function () {
            sendCommand({ action: "undo" });
        });
        toolbarButtons.redo = panelButton("\u21b7 Redo", "Redo the last AI-Hints change", function () {
            sendCommand({ action: "redo" });
        });
        toolbarButtons.app = panelButton("App\u2026", "Open the AI-Hints companion app", function () {
            sendCommand({ action: "open_app", tab: "settings" });
        });

        [
            toolbarButtons.generate, toolbarButtons.model,
            toolbarButtons.hints, toolbarButtons.options,
            toolbarButtons.refresh, toolbarButtons.skip,
            toolbarButtons.clear, toolbarButtons.undo,
            toolbarButtons.redo, toolbarButtons.app
        ].forEach(function (button) { panel.appendChild(button); });

        toolbar = document.createElement("button");
        toolbar.type = "button";
        toolbar.id = "ai-hints-fab";
        toolbar.textContent = "\u2728";
        toolbar.title = "AI-Hints";
        toolbar.setAttribute("aria-label", "AI-Hints actions");
        toolbar.style.cssText = [
            "position:fixed", "right:10px", "bottom:10px", "z-index:99997",
            "width:42px", "height:42px", "border-radius:21px",
            "border:1px solid rgba(0,0,0,.18)", "background:rgba(255,255,255,.95)",
            "color:#111", "font-size:20px", "line-height:1",
            "box-shadow:0 3px 12px rgba(0,0,0,.22)"
        ].join(";");
        toolbar.addEventListener("click", function (event) {
            event.preventDefault();
            event.stopPropagation();
            panelOpen = !panelOpen;
            panel.style.display = panelOpen ? "grid" : "none";
        });

        var host = document.body || document.documentElement;
        host.appendChild(panel);
        host.appendChild(toolbar);
    }

    function panelButton(label, title, handler) {
        var button = document.createElement("button");
        button.type = "button";
        button.textContent = label;
        button.title = title;
        button.setAttribute("aria-label", title);
        button.style.cssText = [
            "min-height:36px", "padding:7px 8px", "border-radius:9px",
            "border:1px solid rgba(0,0,0,.14)", "background:#fff", "color:#111",
            "font:600 12px/1.15 sans-serif", "text-align:center"
        ].join(";");
        button.addEventListener("click", function (event) {
            event.preventDefault();
            event.stopPropagation();
            handler();
        });
        return button;
    }

    function updateToolbar() {
        if (!toolbar) return;
        var hints = lastHints && Array.isArray(lastHints.hints) ? lastHints.hints : [];
        var options = lastHints && Array.isArray(lastHints.options) ? lastHints.options : [];
        var skipped = !!(lastHints && lastHints._skipped);
        var hasAny = hints.length > 0 || options.length > 0;

        toolbarButtons.generate.textContent = isGenerating
            ? "\u23f9 Stop"
            : (hasAny ? "\u21bb Regenerate" : "\u2728 Generate");
        toolbarButtons.generate.title = isGenerating
            ? "Cancel the running generation"
            : "Generate hints and options for this card";

        toolbarButtons.hints.style.display = hints.length ? "" : "none";
        toolbarButtons.options.style.display = options.length ? "" : "none";
        toolbarButtons.hints.textContent = rendererLabel("hints", "Hints");
        toolbarButtons.options.textContent = rendererLabel("options", "Options");

        toolbarButtons.skip.textContent = skipped ? "Unskip" : "Skip";
        toolbarButtons.skip.title = skipped
            ? "Allow AI generation for this card again"
            : "Skip AI generation for this card";
        toolbarButtons.clear.disabled = !lastHints;
        toolbarButtons.clear.style.opacity = lastHints ? "1" : ".45";
        toolbarButtons.model.disabled = false;
        toolbarButtons.model.style.opacity = "1";
        toolbarButtons.refresh.disabled = false;
        toolbarButtons.refresh.style.opacity = "1";
    }

    function rendererLabel(kind, fallback) {
        var button = findRendererControl(kind);
        if (!button) return fallback;
        var label = (button.textContent || "").trim();
        return label || fallback;
    }

    function findRendererControl(kind) {
        var buttons = document.querySelectorAll(".ai-hints-container .ai-hints-btn");
        var pattern = kind === "hints" ? /hints?/i : /options?/i;
        for (var i = 0; i < buttons.length; i++) {
            var text = (buttons[i].textContent || "").trim();
            if (pattern.test(text) && !/generate|regenerate|clear|skip|refresh|json/i.test(text)) {
                return buttons[i];
            }
        }
        return null;
    }

    function clickRendererControl(kind) {
        var button = findRendererControl(kind);
        if (!button) {
            toast("AI-Hints: nothing to show here yet.");
            return;
        }
        try {
            button.click();
        } catch (e) {
            toast("AI-Hints: could not toggle " + kind + ".");
        }
        setTimeout(updateToolbar, 0);
    }

    // ------------------------------------------------------------- model picker
    function openModelSheet() {
        closeModelSheet();
        var choices = (lastState && lastState.uiConfig && lastState.uiConfig.model_choices) || [];
        if (!choices.length) {
            toast("AI-Hints: no models configured. Open the app to add an API key.");
            return;
        }

        modelSheet = document.createElement("div");
        modelSheet.id = "ai-hints-model-sheet";
        modelSheet.style.cssText = [
            "position:fixed", "inset:0", "z-index:99998", "background:rgba(0,0,0,.45)",
            "display:flex", "align-items:flex-end", "justify-content:center"
        ].join(";");

        var sheet = document.createElement("div");
        sheet.style.cssText = [
            "width:100%", "max-width:520px", "max-height:72vh", "overflow:auto",
            "background:#fff", "color:#111", "border-radius:16px 16px 0 0",
            "padding:14px", "font:13px/1.3 sans-serif", "box-shadow:0 -6px 30px rgba(0,0,0,.3)"
        ].join(";");

        var title = document.createElement("div");
        title.textContent = "Generate with\u2026";
        title.style.cssText = "font-weight:700;font-size:15px;margin-bottom:10px";
        sheet.appendChild(title);

        choices.forEach(function (choice) {
            var provider = String(choice.provider || "");
            var models = Array.isArray(choice.models) ? choice.models : [];
            if (!provider || !models.length) return;
            var heading = document.createElement("div");
            heading.textContent = provider + (choice.enabled === false ? " (no key)" : "");
            heading.style.cssText = "margin:10px 0 6px;font-weight:600;color:" +
                (choice.enabled === false ? "#9a3412" : "#334155");
            sheet.appendChild(heading);
            models.forEach(function (model) {
                var button = document.createElement("button");
                button.type = "button";
                button.textContent = model;
                button.style.cssText = [
                    "display:block", "width:100%", "margin:4px 0", "padding:10px",
                    "border-radius:10px", "border:1px solid rgba(0,0,0,.12)",
                    "background:#f8fafc", "color:#111", "font:500 13px/1.2 sans-serif",
                    "text-align:left"
                ].join(";");
                button.addEventListener("click", function (event) {
                    event.preventDefault();
                    event.stopPropagation();
                    closeModelSheet();
                    isGenerating = true;
                    updateToolbar();
                    sendCommand({
                        action: "generate_override",
                        provider: provider,
                        model: model
                    });
                });
                sheet.appendChild(button);
            });
        });

        var cancel = document.createElement("button");
        cancel.type = "button";
        cancel.textContent = "Cancel";
        cancel.style.cssText = [
            "margin-top:12px", "width:100%", "padding:10px", "border-radius:10px",
            "border:1px solid rgba(0,0,0,.12)", "background:#e2e8f0", "color:#111",
            "font:600 13px/1.2 sans-serif"
        ].join(";");
        cancel.addEventListener("click", function (event) {
            event.preventDefault();
            closeModelSheet();
        });
        sheet.appendChild(cancel);

        modelSheet.appendChild(sheet);
        modelSheet.addEventListener("click", function (event) {
            if (event.target === modelSheet) closeModelSheet();
        });
        (document.body || document.documentElement).appendChild(modelSheet);
    }

    function closeModelSheet() {
        if (modelSheet && modelSheet.parentNode) modelSheet.parentNode.removeChild(modelSheet);
        modelSheet = null;
    }

    // ---------------------------------------------------------------- confirm
    // AnkiDroid's WebView does not always surface window.confirm(), so the
    // panel asks in-page instead of depending on it.
    function askConfirm(message, onYes) {
        var overlay = document.createElement("div");
        overlay.style.cssText = [
            "position:fixed", "inset:0", "z-index:99999", "background:rgba(0,0,0,.45)",
            "display:flex", "align-items:center", "justify-content:center", "padding:20px"
        ].join(";");

        var box = document.createElement("div");
        box.style.cssText = [
            "width:100%", "max-width:340px", "padding:16px", "border-radius:14px",
            "background:#fff", "color:#111", "font:14px/1.4 sans-serif",
            "box-shadow:0 10px 34px rgba(0,0,0,.35)"
        ].join(";");

        var text = document.createElement("div");
        text.textContent = message;
        text.style.cssText = "margin-bottom:14px";
        box.appendChild(text);

        var row = document.createElement("div");
        row.style.cssText = "display:flex;gap:8px;justify-content:flex-end";

        function close() {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
        }

        var cancel = panelButton("Cancel", "Close without changing anything", close);
        var ok = panelButton("Continue", "Apply this action", function () {
            close();
            onYes();
        });
        row.appendChild(cancel);
        row.appendChild(ok);
        box.appendChild(row);
        overlay.appendChild(box);
        overlay.addEventListener("click", function (event) {
            if (event.target === overlay) close();
        });
        (document.body || document.documentElement).appendChild(overlay);
    }

    // ------------------------------------------------------- inline extra buttons
    // The renderer rebuilds its button row on every card; re-inject the desktop
    // parity controls (model picker, undo/redo, app shortcut) after each render
    // so they survive AnkiDroid's card reloads even if the floating FAB does not.
    function injectInlineButtons() {
        var boxes = document.querySelectorAll(".ai-hints-container .ai-hints-btn-box");
        if (!boxes.length) return;
        var box = boxes[boxes.length - 1];
        if (box.querySelector(".ai-hints-inline-extra")) return;

        function add(label, title, handler) {
            var button = document.createElement("button");
            button.type = "button";
            button.className = "ai-hints-btn ai-hints-inline-extra";
            button.textContent = label;
            button.title = title;
            button.setAttribute("aria-label", title);
            button.addEventListener("click", function (event) {
                event.preventDefault();
                event.stopPropagation();
                handler();
            });
            box.appendChild(button);
        }

        add("Model\u2026", "Choose a provider and model for this generation", openModelSheet);
        add("\u21b6 Undo", "Undo the last AI-Hints change", function () {
            sendCommand({ action: "undo" });
        });
        add("\u21b7 Redo", "Redo the last AI-Hints change", function () {
            sendCommand({ action: "redo" });
        });
        add("App\u2026", "Open the AI-Hints companion app", function () {
            sendCommand({ action: "open_app", tab: "settings" });
        });
    }

    // The renderer's inline edit / add / model picker are desktop mouse gestures
    // (Ctrl+click, Alt+click). On touch, a long press synthesizes them.
    var pressTimer = null;
    var pressTarget = null;
    var swallowClickUntil = 0;
    var syntheticClick = false;

    function clearPress() {
        if (pressTimer) clearTimeout(pressTimer);
        pressTimer = null;
        pressTarget = null;
    }

    document.addEventListener("touchstart", function (event) {
        if (!event.touches || event.touches.length !== 1) return;
        var touch = event.touches[0];
        pressTarget = { x: touch.clientX, y: touch.clientY };
        pressTimer = setTimeout(function () {
            var x = pressTarget ? pressTarget.x : 0;
            var y = pressTarget ? pressTarget.y : 0;
            pressTarget = null;
            pressTimer = null;
            var element = document.elementFromPoint(x, y);
            if (!element) return;
            var alt = !!(element.closest && element.closest(".ai-hints-btn"));
            swallowClickUntil = Date.now() + 600;
            if (navigator.vibrate) { try { navigator.vibrate(12); } catch (e) {} }
            var synthetic = new MouseEvent("click", {
                bubbles: true,
                cancelable: true,
                view: window,
                ctrlKey: !alt,
                metaKey: false,
                altKey: alt
            });
            syntheticClick = true;
            try {
                element.dispatchEvent(synthetic);
            } finally {
                syntheticClick = false;
            }
        }, 480);
    }, { passive: true });

    ["touchend", "touchcancel", "touchmove", "scroll"].forEach(function (name) {
        document.addEventListener(name, clearPress, { passive: true });
    });

    document.addEventListener("click", function (event) {
        if (syntheticClick) return;
        if (Date.now() < swallowClickUntil) {
            event.preventDefault();
            event.stopPropagation();
            swallowClickUntil = 0;
        }
    }, true);

    // ------------------------------------------------------------------- boot
    async function boot() {
        if (booted) return;
        booted = true;

        // Diagnostic: check what's available
        var _diag = "AnkiDroidJS=" + typeof window.AnkiDroidJS +
            " ankiGetCardNid=" + typeof window.ankiGetCardNid +
            " pycmd=" + typeof window.pycmd +
            " nativePycmd=" + typeof nativePycmd;
        var _d = document.createElement("div");
        _d.style.cssText = "position:fixed;bottom:0;left:0;right:0;z-index:999999;background:#00f;color:#fff;padding:4px;font:11px monospace;";
        _d.textContent = _diag;
        (document.getElementById("qa") || document.body || document.documentElement).appendChild(_d);

        var apiReady = false;
        for (var _a = 0; _a < 20 && !apiReady; _a++) {
            if (getApi()) { apiReady = true; break; }
            await new Promise(function (resolve) { setTimeout(resolve, 500); });
        }
        if (!apiReady) {
            var _nid = null;
            try { _nid = (document.title || "").match(/\d{10,}/); _nid = _nid ? _nid[0] : null; } catch (e) {}
            if (!_nid) {
                try { _nid = (window.location.href || "").match(/nid=(\d+)/); _nid = _nid ? _nid[1] : null; } catch (e) {}
            }
            if (_nid) {
                CARD.nid = parseInt(_nid, 10);
                CARD.ord = detectOrd();
                CARD.isAnswer = await detectAnswer();
                lastSignature = "";
                await refreshState();
                return;
            }
            var _d = document.getElementById("ai-hints-diag");
            if (_d) _d.textContent = "NO AnkiDroidJS after retries";
            showBanner("AI-Hints: disable New study screen in AnkiDroid settings and enable the JavaScript API.");
            return;
        }

        var nid = null;
        for (var _b = 0; _b < 20; _b++) {
            nid = await apiCall("ankiGetCardNid");
            if (!(nid === null || nid === undefined || String(nid) === "-1" || String(nid) === "unknown")) break;
            await new Promise(function (resolve) { setTimeout(resolve, 500); });
        }
        if (nid === null || nid === undefined || String(nid) === "-1" || String(nid) === "unknown") {
            showBanner("AI-Hints: this AnkiDroid version did not expose the note id. Update AnkiDroid.");
            return;
        }
        CARD.nid = parseInt(nid, 10);
        CARD.ord = detectOrd();
        CARD.isAnswer = await detectAnswer();
        lastSignature = "";
        await refreshState();

        // Safety net for reviewers that reveal the answer without reloading.
        var lastDomAnswer = detectAnswerDom();
        var lastOrd = CARD.ord;
        setInterval(function () {
            if (!booted || CARD.nid === null) return;
            // AnkiDroid can replace the card body in place without re-running
            // this script; re-attach the floating panel if that happened.
            if (toolbar && !document.documentElement.contains(toolbar)) {
                toolbar = null;
                panel = null;
                toolbarButtons = {};
                panelOpen = false;
                ensureToolbar();
                updateToolbar();
                injectInlineButtons();
                lastSignature = "";
                refreshState();
            }
            injectInlineButtons();
            var answer = detectAnswerDom();
            var ord = detectOrd();
            if (answer !== lastDomAnswer || ord !== lastOrd) {
                lastDomAnswer = answer;
                lastOrd = ord;
                lastSignature = "";
                refreshState();
            }
        }, 700);
    }

    window.aiHintsBoot = function () { if (!booted) boot(); };
    // Poll for AnkiDroidJS to be defined, then boot.
    (function waitForApi() {
        if (typeof window.AnkiDroidJS !== "undefined") {
            setTimeout(function () { if (!booted) boot(); }, 500);
            return;
        }
        setTimeout(waitForApi, 300);
    })();
    window.addEventListener("load", function () { if (!booted) boot(); });
})();

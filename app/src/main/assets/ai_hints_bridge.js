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
        if (apiTried) return api;
        apiTried = true;
        if (typeof window.AnkiDroidJS === "undefined") return null;
        try {
            // v0.0.3 is the 2.18+ contract. Older AnkiDroid ignores the field.
            api = new window.AnkiDroidJS({
                version: "0.0.3",
                developer: "ai-hints-companion"
            });
        } catch (e) {
            api = null;
        }
        return api;
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
            "position:fixed", "left:8px", "right:8px", "bottom:8px", "z-index:99997",
            "padding:10px 12px", "border-radius:10px", "font:13px/1.35 sans-serif",
            "background:rgba(180,83,9,.95)", "color:#fff", "box-shadow:0 4px 14px rgba(0,0,0,.25)"
        ].join(";");
        banner.addEventListener("click", function () {
            if (banner && banner.parentNode) banner.parentNode.removeChild(banner);
            banner = null;
        });
        (document.body || document.documentElement).appendChild(banner);
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
            showBanner("AI-Hints: cannot identify this card. Enable the AnkiDroid JavaScript API.");
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

        var config = payload.uiConfig || window.aiHintsUiConfig;
        config.is_answer_side = CARD.isAnswer;

        if (typeof window.aiHintsSetup === "function") {
            window.aiHintsSetup(card, payload.hints === undefined ? null : payload.hints, config);
        }
        ensureToolbar();
        hideBanner();
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
                if (typeof window.aiHintsUpdateData === "function") {
                    window.aiHintsUpdateData(event.data, !!event.isManual);
                }
                break;
            case "clear":
                if (typeof window.aiHintsClearData === "function") window.aiHintsClearData();
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
        var bridge = getApi();
        if (bridge && typeof bridge.ankiShowAnswer === "function") {
            try { bridge.ankiShowAnswer(); return; } catch (e) { /* fall through */ }
        }
    }

    function answerEase(ease) {
        var bridge = getApi();
        var name = "ankiAnswerEase" + ease;
        if (bridge && typeof bridge[name] === "function") {
            try { bridge[name](); return; } catch (e) { /* fall through */ }
        }
        var legacy = window["buttonAnswerEase" + ease];
        if (typeof legacy === "function") {
            try { legacy(); return; } catch (e) { /* fall through */ }
        }
        toast("AI-Hints: this AnkiDroid version cannot answer cards from JavaScript.");
    }

    // -------------------------------------------------------------- touch toolbar
    function ensureToolbar() {
        if (document.getElementById("ai-hints-touchbar")) return;
        var bar = document.createElement("div");
        bar.id = "ai-hints-touchbar";
        bar.style.cssText = [
            "position:fixed", "right:10px", "bottom:10px", "z-index:99996",
            "display:flex", "gap:6px", "opacity:.72"
        ].join(";");

        bar.appendChild(touchButton("\u21b6", "Undo last AI-Hints change", function () {
            sendCommand({ action: "undo" });
        }));
        bar.appendChild(touchButton("\u21b7", "Redo last AI-Hints change", function () {
            sendCommand({ action: "redo" });
        }));
        (document.body || document.documentElement).appendChild(bar);
    }

    function touchButton(label, title, handler) {
        var button = document.createElement("button");
        button.type = "button";
        button.textContent = label;
        button.title = title;
        button.setAttribute("aria-label", title);
        button.style.cssText = [
            "width:38px", "height:38px", "border-radius:19px", "border:1px solid rgba(0,0,0,.15)",
            "background:rgba(255,255,255,.92)", "color:#111", "font-size:19px", "line-height:1",
            "box-shadow:0 2px 8px rgba(0,0,0,.18)"
        ].join(";");
        button.addEventListener("click", function (e) {
            e.preventDefault();
            e.stopPropagation();
            handler();
        });
        return button;
    }

    // ------------------------------------------------- long-press → ctrl/alt click
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

        if (!getApi()) {
            showBanner(
                "AI-Hints: enable AnkiDroid's JavaScript API (Settings \u2192 Advanced) and reload this card."
            );
            return;
        }

        var nid = await apiCall("ankiGetCardNid");
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

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", boot);
    } else {
        boot();
    }
})();

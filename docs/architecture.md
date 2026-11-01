# Architecture

AI-Hints Companion is a second Android app, not a modified AnkiDroid. It runs
the add-on's Python core under [Chaquopy](https://chaquo.com/chaquopy/) and
serves the add-on's reviewer JavaScript to AnkiDroid's card WebView over a
loopback HTTP bridge.

```text
┌─────────────────────────── AnkiDroid process ────────────────────────────┐
│  Reviewer WebView                                                        │
│    <script src="http://127.0.0.1:8766/bridge.js">                        │
│    <script src="http://127.0.0.1:8766/template.js">                      │
│    pycmd() ──► bridge.js ──► fetch POST /cmd                             │
│    EventSource /events ◄── SSE ──┐                                       │
│    AnkiDroidJS (answer ease, show answer, card id)                       │
└──────────────────────────────────┼───────────────────────────────────────┘
                                   │ 127.0.0.1:8766
┌──────────────────────────────────┼───────────────────────────────────────┐
│  AI-Hints Companion process      ▼                                       │
│    BridgeServer (NanoHTTPD) ── EventHub (per-note SSE queues)            │
│           │                                                              │
│           ▼                                                              │
│    PyEngine ── aihints_android (app layer)                               │
│                     │                                                    │
│                     ▼                                                    │
│                ai_hints_core (add-on, generated at build time)           │
│                     │                                                    │
│                     ▼                                                    │
│    BackendManager ── ContentProviderPort ──► AnkiDroid provider         │
│                  └── AnkiConnectPort ──────► AnkiConnect (optional)      │
│                                                                          │
│    Foreground BridgeService keeps the process alive during review        │
└──────────────────────────────────────────────────────────────────────────┘
```

## Why a bridge instead of installing JavaScript into the collection

The desktop add-on writes `_ai_hints_template.js` into the collection's media
folder and injects it with a template hook. Android has no add-on hooks, and
media files are synced through AnkiWeb.

Instead:

- `app/src/main/assets/ai_hints_bridge.js` is hand-written glue, served at
  `/bridge.js`.
- `_ai_hints_template.js` is the add-on renderer, served verbatim at
  `/template.js`.
- The note type only contains two `<script src>` tags pointing at loopback.

Consequences:

- A renderer change ships with a new APK and does **not** require a media sync.
- The renderer can call the Python core directly over HTTP/SSE.
- The note type change itself is still a schema change: AnkiWeb will request a
  full sync after install/remove, just like the desktop add-on.

`bridge.js` must run first. It synchronously defines a placeholder
`window.aiHintsUiConfig`, because `template.js` samples that global while
parsing to decide whether the add-on is active. The real config arrives with the
first `/state` response.

## Card identity

AnkiDroid's WebView does not tell JavaScript which note or template ordinal it is
rendering. `bridge.js` derives them:

- **Note id**: `AnkiDroidJS.ankiGetCardNid()` (async on AnkiDroid 2.18+).
- **Template ordinal**: the `cardN` class on `<body>` / `#qa_box`, or `cN` on a
  cloze span, minus one.
- **Answer side**: `AnkiDroidJS.ankiIsDisplayingAnswer()`, with DOM fallbacks
  (`#answer`, `.answer`, `#qa_box.answer`) and a 700 ms poll for reviewers that
  reveal the answer without reloading.

## Data access

`BackendManager` probes backends lazily (the collection closes when AnkiDroid is
backgrounded, so a cached "connected" result goes stale):

1. **ContentProviderPort** — `content://com.ichi2.anki.flashcards`:
   `notes`, `models`, `models/<id>/templates/<ord>`, `cards`, `schedule`.
   Availability probe: `models/current`.
2. **AnkiConnectPort** — note-level only (`findNotes`, `notesInfo`,
   `updateNoteFields`). No templates, tags or scheduler.

`models/<id>/templates` exists only in recent AnkiDroid builds. The port
feature-detects it and surfaces a clear error instead of writing a partial
template.

Field values travel as `\x1f`-separated `FLDS`; tags are read/write `TAGS`.
Template writes require `MODEL_ID, ORD, NAME, QUESTION_FORMAT, ANSWER_FORMAT,
BROWSER_QUESTION_FORMAT, BROWSER_ANSWER_FORMAT` and use a 0-based `ord` URI
segment.

## Generation flow

1. `bridge.js` posts `{nid, ord, command:{action:"generate"}}` to `/cmd`.
2. `api.command` spawns generation on a worker thread and returns immediately.
3. `Engine` reads the note, builds front/back with the add-on's `CardParser`,
   and calls `AIClient` with the stored config.
4. Progress is emitted as `generating` events; the final payload is written back
   to the note (fields and, when configured, tags) and emitted as `data`.
5. A terminal `generating:false` event clears the spinner. On failure the event
   carries `status:"Failed"` and `error`; no second terminal event is sent, so
   the error toast is not wiped.

A per-note lock prevents two generations for the same note. The `EventHub`
fans events out to any SSE subscriber for that note id.

## Undo and redo

AnkiDroid exposes no collection-level undo to third-party apps. The app keeps
its own stack of field/tag snapshots (limit 40) in `aihints_android/store.py`:

- Every write path (`generate`, `edit_item`, `add_item`, `skip`, `unskip`,
  `clear`) pushes a snapshot before modifying the note.
- Undo/redo re-applies a snapshot through the backend and is per note.
- The ✨ panel's Undo/Redo buttons call `undo` / `redo` commands.

This stack is app-local and does not affect AnkiDroid's own undo history.

## Desktop parity

The companion reproduces the desktop surfaces that make sense on a phone:

| Desktop | Companion |
| --- | --- |
| Reviewer panel (generate, hints, options, refresh, JSON, clear, skip) | Renderer in the card, plus the floating ✨ panel |
| AI Hints bottom-bar menu | Floating ✨ panel |
| Alt+click model picker | **Model…** in the panel |
| Browser/deck menu: batch, skip, unskip, clear | **Cards** tab (search + actions) and **Batch** tab |
| Add-on config dialog (all tabs) | **Settings** tab (schema + raw JSON) |
| Provider test, fetch models | **Settings → Provider** |
| Logs tab | **Logs** tab |
| Stop All | **Status → Stop all AI work** |
| Install/remove templates | **Status → Card templates**, per note type or all |

## Storage

Everything lives in the app's private data directory:

```text
files/ai_hints/
  config.json     merged add-on configuration (contains API keys)
  ai_hints.log    rotating add-on log
```

API keys are never logged, never written to the APK, and never leave the device
except in requests to the provider you configured.

## Security model

- NanoHTTPD binds to `127.0.0.1`, so the bridge is not reachable from the LAN.
- `POST` and `GET /events` are rejected when the `Origin` header is not a
  loopback origin. CORS headers are echoed only for loopback origins. The bind
  is the real boundary; the Origin check is defence in depth.
- The bridge service is not exported; AnkiDroid reaches it over loopback HTTP,
  not through an Android component.
- `usesCleartextTraffic` is enabled because loopback HTTP is cleartext. Provider
  calls go to HTTPS endpoints.

## Build-time materialisation

`vendor/AI-Hints` is a git submodule. On every build, `syncAddonCore` runs
`tools/materialize_core.py`, which copies the whitelisted add-on files into
`build/generated/aihints/`:

```text
build/generated/aihints/
  python/ai_hints_core/     ai_client, card_parser, logger, config_io,
                            mobile_sync, model_updater, config.json,
                            json_repair/, latex_fixer/, web/
  assets/_ai_hints_template.js
  assets/config_schema.json
  MANIFEST.json
```

The generated tree is a Chaquopy source directory and an asset directory. It is
never committed. See [Updating the add-on](updating-the-addon.md).

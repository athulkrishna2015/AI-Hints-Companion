# Bridge protocol

The bridge is an HTTP server on `http://127.0.0.1:8766` (port configurable in
**Settings**). It exists for the reviewer WebView; it is not a public API.

All bodies are JSON. Responses are JSON except the two JavaScript assets and the
SSE stream.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Liveness, add-on version, backend status |
| `GET` | `/bridge.js` | Glue script (this app) |
| `GET` | `/template.js` | AI-Hints renderer (add-on, verbatim) |
| `POST` | `/state` | Card identity + stored hints + UI config |
| `POST` | `/cmd` | Reviewer command |
| `GET` | `/events?nid=<id>` | Server-sent events for one note |
| `OPTIONS` | any | CORS preflight |

`POST` and `/events` require a loopback `Origin` (`http://127.0.0.1`,
`http://localhost`, `http://[::1]`). Everything is bound to loopback anyway.

### `GET /health`

```json
{
  "ok": true,
  "port": 8766,
  "addonVersion": "8.6.9",
  "pythonReady": true,
  "backend": {
    "id": "provider",
    "label": "AnkiDroid API",
    "available": true,
    "reason": ""
  }
}
```

`backend.id` is `provider`, `ankiconnect` or `none`. `reason` explains an
unavailable backend.

### `POST /state`

Request:

```json
{ "nid": 1234567890, "ord": 0, "isAnswer": false }
```

Response:

```json
{
  "ok": true,
  "card": { "id": "1234567890", "nid": 1234567890, "ord": 0, "model": "Basic" },
  "hints": { "hints": ["..."], "options": ["..."] },
  "uiConfig": { "...": "renderer options" },
  "flags": { "hasAnyBlock": true, "isSkipped": false, "tags": ["..."] }
}
```

`hints` is `null` when the card has no stored AI-Hints data. A skipped card is
`{ "_skipped": true }`. `uiConfig` is the add-on's renderer configuration for
this card side.

### `POST /cmd`

Request (the `command` object is optional; a flat body works too):

```json
{ "nid": 1234567890, "ord": 0, "command": { "action": "generate" } }
```

Generation actions return immediately and stream results over `/events`; all
other actions return their result synchronously.

| Action | Extra fields | Effect |
| --- | --- | --- |
| `generate` | `isPregen?` | Generate hints/options for the card |
| `generate_override` | `provider`, `model` | Generate with a one-off provider/model |
| `cancel` | — | Cancel an in-flight generation |
| `clear` | — | Remove AI-Hints data from the note |
| `skip` | — | Mark skipped (adds the skip tag when enabled) |
| `unskip` | — | Clear the skip marker |
| `refresh` | `isAnswer` | Re-read state without changing anything |
| `edit_item` | `type` (`hints`/`options`), `index`, `value` | Replace one item |
| `add_item` | `type`, `value` | Append one item |
| `remove_warning` | — | Remove the add-on's warning block |
| `undo` | — | Restore the previous app-level snapshot |
| `redo` | — | Re-apply the undone snapshot |
| `rate_good` | — | Return `clientAnswer: 3` (the actual ease is sent by `bridge.js`) |
| `rate_again` | — | Return `clientAnswer: 1` |
| `open_app` | `tab` (`status`/`cards`/`batch`/`settings`/`logs`) | Handled by the Kotlin bridge: brings the companion app to the front on that tab |

Responses are `{ "ok": true, ... }` or `{ "ok": false, "error": "..." }`.

The floating **✨** reviewer panel calls these actions directly. It is created
only after `/state` returns successfully, so its presence means the bridge is
active for the current card.

### `GET /events?nid=<id>`

Server-sent events. The stream starts with `retry: 3000` and sends a `: ping`
comment every 15 seconds while idle.

```text
retry: 3000

data: {"type":"generating","active":true,"status":"Contacting provider…","cardId":"123"}

data: {"type":"data","data":{"hints":["..."],"options":["..."]},"isManual":false,"elapsedMs":4210}

data: {"type":"generating","active":false,"status":null,"error":null,"cardId":"123"}
```

Event types:

| `type` | Meaning |
| --- | --- |
| `generating` | Spinner state. `active`, `status`, `error`, `cardId`, `isPregen` |
| `data` | New hints payload; `isManual` marks edits made in the reviewer |
| `clear` | Remove the panel contents |
| `cancelled` | Generation cancelled |
| `toast` | One-line message (`message`) |

Event order for a successful generation is `data` then `generating:false`. A
failure sends `generating:false` with `status:"Failed"` and `error`; there is no
second terminal event.

## JavaScript → app (`pycmd`)

The add-on renderer calls `pycmd("ai_hints_…")`. `bridge.js` wraps the native
`pycmd` and maps those names to `/cmd` actions:

| `pycmd` string | Action |
| --- | --- |
| `ai_hints_generate` | `generate` |
| `ai_hints_cancel` | `cancel` |
| `ai_hints_clear` | `clear` |
| `ai_hints_skip` | `skip` |
| `ai_hints_refresh` | `refresh` |
| `ai_hints_edit_item` | `edit_item` (JSON payload) |
| `ai_hints_add_item` | `add_item` (JSON payload) |
| `ai_hints_generate_override` | `generate_override` (JSON payload) |
| `ai_hints_model_picker` | Opens the in-card provider/model picker |
| `ai_hints_rate_good` | `AnkiDroidJS.ankiAnswerEase3()` |
| `ai_hints_rate_again` | `AnkiDroidJS.ankiAnswerEase1()` |
| `ans` | `AnkiDroidJS.ankiShowAnswer()` |

Answering is handled entirely in JavaScript so it uses AnkiDroid's own
scheduler. The Python side only reports which ease a UI button represents.

The companion's ✨ panel translates taps straight into `/cmd` actions, so it
does not depend on the renderer's DOM. Hint/option toggles click the renderer's
existing control, keeping the two interfaces in sync.

## App → JavaScript

`bridge.js` exposes the globals the add-on renderer expects:

| Function | Purpose |
| --- | --- |
| `aiHintsSetup(card, data, config)` | Initial render |
| `aiHintsUpdateData(data, isManual)` | New hints payload |
| `aiHintsSetGenerating(active, status, error, cardId, isPregen)` | Spinner |
| `aiHintsClearData()` | Clear the panel |

It also defines `window.aiHintsUiConfig` before `template.js` loads, so the
renderer's parse-time "is the add-on active?" check succeeds.

## CORS

Preflight returns the allowed methods/headers and echoes `Access-Control-Allow-
Origin` only for loopback origins. The SSE response includes
`Cache-Control: no-cache` and `X-Accel-Buffering: no`.

# Troubleshooting

Start with **Status** in the app: it shows the bridge state, backend, add-on
version and the last error. **Logs** has the add-on's own log.

On the device, `http://127.0.0.1:8766/health` in a browser is a quick check that
the service is alive.

## The card shows "AI-Hints companion app is not running"

The bridge service is not reachable from AnkiDroid.

- Open AI-Hints Companion and make sure the bridge is enabled.
- Check the ongoing notification exists. If not, start the service from
  **Status**.
- Exempt the app from battery optimisation on aggressive OEM builds; Android may
  otherwise kill the service while AnkiDroid is in front.
- If you changed the port in Settings, reinstall the templates so the script URLs
  match (or keep the default 8766).
- Reload the card after starting the service.

## "Enable AnkiDroid's JavaScript API" / "cannot identify this card"

AnkiDroid's JavaScript API is off or too old.

- AnkiDroid → **Settings → Advanced** → enable the JavaScript API and the
  external API.
- Update AnkiDroid. `ankiGetCardNid` must exist; older builds do not expose the
  note id.
- Reload the card after changing the setting.

## "AnkiDroid API unavailable"

- Enable **Settings → Advanced → Allow external API access** in AnkiDroid.
- Grant the runtime permission from **Status → Grant API access**.
- AnkiDroid closes its collection when backgrounded; the app re-probes on the
  next action, so return to AnkiDroid and try again.
- If you use the AnkiConnect fallback, check the URL (default
  `http://127.0.0.1:8765`) and that AnkiConnectAndroid is running.

## Template install says the API is unsupported

The `models/<id>/templates` endpoint only exists in recent AnkiDroid builds.
Update AnkiDroid. Until then you can install the two script tags manually in the
note type's question/answer templates:

```html
<script src="http://127.0.0.1:8766/bridge.js"></script>
<script src="http://127.0.0.1:8766/template.js"></script>
```

## Generation fails or returns nothing

- **Test provider** in Settings. A bad key, wrong model name or provider outage
  is reported verbatim there and in Logs.
- Check the card's front side is not empty; the add-on refuses to send nothing.
- `local`/`ollama` need a reachable endpoint configured in the add-on keys.
- Long generations can be cancelled from the panel; the spinner clears on
  `cancelled`.
- If the error toast disappears immediately, check Logs for the underlying
  message. The bridge emits exactly one terminal event per generation.

## Hints exist on desktop but not on Android

- The data is stored in the note fields by the add-on. Confirm the note type and
  field names match the add-on's configured hint fields.
- Check the note's tags: a skipped note is stored as `{"_skipped": true}` and
  renders no hints until unskipped.
- If you changed the add-on's field mapping on desktop, import the new
  `meta.json` so the app uses the same mapping.

## Import says "no config object found"

The file is not an Anki `meta.json` or a bare `config.json`. A `meta.json` must
contain a `config` object; a bare config must be a non-empty JSON object. Export
the config from the desktop add-on and retry.

## Keys disappeared after "Reset defaults"

Reset replaces the configuration with the add-on defaults, which have empty API
keys. Re-import `meta.json` or re-enter the keys.

## AnkiWeb asks for a full sync after installing templates

Expected. Editing question/answer templates is a schema change; AnkiWeb requires
a one-way full sync, exactly as with the desktop add-on. The same happens when
you remove the templates.

## Panel renders but buttons do nothing

- Long-press is the touch equivalent of Ctrl/Alt+click; a normal tap may do
  nothing on controls that require a modifier.
- Undo/redo are the floating ↶ / ↷ buttons.
- If `pycmd` is not defined, `bridge.js` did not load; verify the script tags and
  `/bridge.js` in a browser.
- If the panel is visible but stale, use **Refresh** or reload the card.

## Diagnosing with adb

```bash
adb logcat -s AiHints BridgeServer Python
adb shell am start -n dev.aihints.companion/.MainActivity
```

The app logs Python startup, bridge binds, backend probes and rejected origins.
Never paste `config.json` into a bug report — it contains API keys.

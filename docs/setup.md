# Setup

## Requirements

- Android 7.0 (API 24) or newer, `arm64-v8a` or `x86_64`.
- AnkiDroid with the **external API** (the "AnkiDroid API alpha"). The app uses
  AnkiDroid's `ContentProvider`, not a fork.
- An AI provider key (Gemini, OpenAI, Anthropic, OpenRouter, Ollama, …) — either
  entered in **Settings** or imported from your desktop add-on.

## 1. Enable AnkiDroid's external API

In AnkiDroid:

1. **Settings → Advanced**.
2. Enable **Allow external API access** (wording varies by version; it may be
   called "AnkiDroid API").
3. Leave AnkiDroid installed; do not replace it with a fork.

Without this switch, AnkiDroid does not expose its provider and the app reports
`AnkiDroid API unavailable`.

## 2. Install the companion app

From a checkout:

```bash
just build
just install          # adb install -r of the debug APK
```

Or install `app/build/outputs/apk/debug/app-debug.apk` manually.

## 3. Grant the database permission

Open **AI-Hints Companion → Status → Grant API access** and accept the system
dialog for AnkiDroid database access. The permission is
`com.ichi2.anki.permission.READ_WRITE_DATABASE`, a dangerous permission granted
at runtime.

On Android 13+, also allow notifications so the foreground bridge service can
show its ongoing notification.

## 4. Import your desktop configuration (optional but recommended)

The app accepts the add-on's live `meta.json` (the file that contains your
providers, keys and all 72 settings):

1. **Settings → Import meta.json**, or share/open the JSON file with the app.
2. Pick `addons/AI-Hints/addon/meta.json` from your desktop checkout.
3. The app merges it over its defaults and reports how many values changed and
   which providers now have keys.

A bare `config.json` is accepted too. See [Configuration](configuration.md) for
formats and precedence.

## 5. Install the reviewer scripts into your note types

Open **Status → Install / repair templates**. The app adds two script tags to the
question and answer templates of the selected note types:

```html
<script src="http://127.0.0.1:8766/bridge.js"></script>
<script src="http://127.0.0.1:8766/template.js"></script>
```

Notes:

- This edits the note type, which AnkiWeb treats as a schema change and triggers
  a **full sync** — exactly like the desktop add-on.
- Use **Remove templates** to restore the original question/answer formats.
- If AnkiDroid's API does not expose `models/<id>/templates`, template install
  is unavailable; update AnkiDroid to a build that supports it.

## 6. Review

Open a card in AnkiDroid. The AI-Hints panel appears on the question side; tap
**Generate** to create hints/options. On the answer side the stored data is
shown.

Touch equivalents for desktop shortcuts:

| Desktop | Touch |
| --- | --- |
| Ctrl+click an item | Long-press the item (inline edit) |
| Ctrl+click “add” | Long-press the add control |
| Alt+click the model button | Long-press the AI-Hints button |
| Ctrl+Alt+Z / Ctrl+Alt+Y | Floating ↶ / ↷ buttons |

## 7. Keep the bridge alive

The bridge runs as a foreground service while enabled. On aggressive OEM
battery managers, exempt the app from battery optimisation, otherwise Android
may stop the service and AnkiDroid will show the "Companion app not running"
banner.

## Optional: AnkiConnect fallback

If the AnkiDroid API is not available (or you run AnkiDroid desktop-side via
AnkiConnectAndroid), set **Settings → Backend → AnkiConnect** and the URL
(default `http://127.0.0.1:8765`). The fallback can read notes, write fields and
generate hints, but templates, tags and in-app answering still require the
AnkiDroid API.

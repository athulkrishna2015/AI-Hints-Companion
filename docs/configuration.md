# Configuration

The app stores the add-on's configuration in its private data directory:

```text
files/ai_hints/config.json
```

It is the same 72-key schema as the desktop add-on's `config.json`. Defaults come
from the add-on's shipped `config.json` (materialised from the submodule), so a
new add-on release adds new keys with their defaults automatically.

## Where values come from

1. **Defaults** — `ai_hints_core/config.json` from the add-on.
2. **Saved config** — `files/ai_hints/config.json`, deep-merged over the
   defaults.
3. **Edits** — the Settings screen patches individual keys, or replaces the
   whole object from the Raw JSON tab.

Deep merge means unknown keys are preserved and missing keys fall back to the
add-on default.

## Importing from the desktop add-on

**Settings → Provider → Import meta.json** (or share/open a JSON file with the
app).

Accepted formats:

- **Anki add-on `meta.json`** — the file at
  `addons/AI-Hints/addon/meta.json`. The live configuration is under the
  `config` key; everything else in the file (note types, decks, cached data) is
  ignored.
- **Bare `config.json`** — the add-on's exported configuration object.

Import behaviour:

- The incoming object is merged over the defaults and saved.
- Unknown keys are kept, so newer add-on settings survive.
- The result reports how many keys were imported, which keys changed, and which
  providers now have non-empty keys.
- **Values are never logged**, including on failure.

After import, check **Settings → Provider** shows the expected provider and
model, and use **Test provider** to make one small request.

> The imported file contains real API keys. Keep it off shared storage if
> possible, and remember that the app has `allowBackup=false`, so the keys are
> not copied into Android cloud backups.

## Settings screen

| Tab | Use |
| --- | --- |
| **Essentials** | Provider, model, API keys and the settings most people change |
| **All keys** | Every key, grouped by the add-on's documentation groups |
| **Raw JSON** | Edit the whole config object; **Apply** replaces it, **Revert** discards |

**Reset defaults** restores the add-on defaults (this clears imported keys).

Settings are written immediately by **Save settings**. The Python engine reloads
the config on the next command; provider/model choice caches are invalidated.

## API keys

Keys live under `api_keys` as a provider → key mapping:

```json
{
  "api_keys": {
    "gemini": "…",
    "openai": "…",
    "openrouter": "…"
  }
}
```

The add-on's key-rotation lists (if you use them) are imported verbatim. An empty
string means "no key configured" and is reported as such by the import result.

## Providers and models

- **Provider** selects the add-on provider (`gemini`, `openai`, `anthropic`,
  `openrouter`, `ollama`, `local`, …).
- **Model** is the provider-specific model name. **Fetch models** queries the
  provider when it supports listing; otherwise type the name.
- **Test provider** sends a minimal request and reports the error verbatim.
- `local`/`ollama` endpoints are configured by their usual add-on keys.

## Batch generation

**Batch** runs the add-on's batch pipeline over a search query:

- Search query (Anki syntax), limit, optional provider/model override, and
  "skip notes that already have hints".
- Progress is shown live; **Pause**, **Resume** and **Cancel** are supported.
- Results are written through the active backend, so notes must be writable by
  AnkiDroid (or AnkiConnect).
- Tagging follows the add-on settings (`tag_hinted_notes`, `tag_skipped_notes`,
  `hint_tag`, `skip_tag`).

Batch runs need the bridge process alive; keep the app in the foreground or rely
on the foreground service.

## Logs

**Logs** shows the tail of `files/ai_hints/ai_hints.log` (the add-on logger).
Provider errors, parse failures and generation timings are recorded there. The
log never contains API keys.

**Export config** writes the current configuration as JSON. Treat the exported
file as a secret.

# AI-Hints Companion

An Android companion app that runs the [AI-Hints](https://github.com/athulkrishna2015/AI-Hints)
reviewer experience alongside AnkiDroid — without forking either app.

AnkiDroid renders the card; the companion app serves the add-on's own renderer
and AI engine over a loopback HTTP bridge and talks to AnkiDroid through its
external ContentProvider API.

## How it fits together

```
AnkiDroid card WebView
  ├─ <script src="http://127.0.0.1:8766/bridge.js">    (hand-written glue)
  ├─ <script src="http://127.0.0.1:8766/template.js">  (add-on renderer, verbatim)
  └─ fetch/POST /state, /cmd, /events                   (loopback only)

AI-Hints Companion (foreground service)
  ├─ NanoHTTPD on 127.0.0.1:8766
  ├─ Chaquopy → aihints_android (app layer) → ai_hints_core (add-on core)
  └─ ContentProvider → AnkiDroid notes / models / templates / scheduler
```

- The renderer and core are **never forked**. They are materialised from the
  `vendor/AI-Hints` git submodule at build time into `build/generated/aihints/`.
- The renderer is delivered by the bridge rather than installed into collection
  media, so a renderer change only needs a new APK — no AnkiWeb media sync.
- Card templates get two script tags. Installing them changes the note type,
  which triggers a full AnkiWeb sync, exactly like the desktop add-on.

## Documentation

| Document | Contents |
| --- | --- |
| [Setup](docs/setup.md) | AnkiDroid API, install, permissions, first card |
| [Architecture](docs/architecture.md) | Components, data flow, security, undo |
| [Bridge protocol](docs/bridge-protocol.md) | HTTP/SSE endpoints, commands, JS API |
| [Configuration](docs/configuration.md) | `meta.json` import, API keys, batch |
| [Updating the add-on](docs/updating-the-addon.md) | Submodule bump, verify, CI |
| [Troubleshooting](docs/troubleshooting.md) | Symptoms and fixes |
| [Development](docs/development.md) | Toolchain, layout, adding commands |

## Requirements

- JDK 17–21 (`mise` supplies 21 here)
- Android SDK platform 34 + build-tools 34.0.0
- CPython 3.8–3.13 for Chaquopy (`uv python find 3.11`)
- `just` (optional but used below)

## First checkout

```bash
git clone --recurse-submodules <this repo>
# or, if already cloned:
just submodule          # git submodule update --init --recursive

just build              # ./gradlew :app:assembleDebug
just install            # adb install of the debug build
```

`vendor/AI-Hints` has a nested submodule (`addon/latex_fixer`); a non-recursive
checkout fails the build with an explicit message.

## Updating the add-on

```bash
git -C vendor/AI-Hints fetch --tags
git -C vendor/AI-Hints checkout v8.7.0     # or any commit
git add vendor/AI-Hints                    # record the new gitlink
just sync                                  # optional: refresh IDE view
just verify                                # run the add-on's core test suites
just build
```

That is the whole update loop. `app/build.gradle.kts` runs
`tools/materialize_core.py` on every build, so the APK always contains the
submodule's exact `ai_client.py`, `card_parser.py`, renderer, providers and
defaults. `just sync-check` fails if the generated tree is stale.

## Using the app

1. Install and open AnkiDroid. In **Settings → Advanced**, enable
   **Allow external API access** (the AnkiDroid API alpha).
2. Open AI-Hints Companion → **Status → Grant API access**, then allow the
   AnkiDroid database permission.
3. **Install / repair** templates for the note types you want.
4. Import your desktop configuration: **Import meta.json** and pick
   `addons/AI-Hints/addon/meta.json` (or share the file to the app). API keys
   and all 72 add-on settings are imported; values never leave the device.
5. Start the bridge (it runs as a foreground service) and review in AnkiDroid.

If the AnkiDroid API is unavailable, the app falls back to AnkiConnectAndroid
for generation and note-field writes. Templates, tags and in-app answering need
the AnkiDroid API.

### Touch gestures

The desktop add-on uses Ctrl+click to edit/add items and Alt+click to open the
model picker. On touch, **long-press** synthesizes those gestures. The floating
↶ / ↷ buttons replace Ctrl+Alt+Z for undo/redo.

## Project layout

```
app/src/main/java/dev/aihints/companion/   Kotlin: bridge, backends, service, Compose UI
app/src/main/python/aihints_android/       Python app layer (engine, templates, store)
app/src/main/assets/ai_hints_bridge.js     Reviewer glue (hand-written)
build/generated/aihints/                   core, renderer, schema (never committed)
tools/materialize_core.py                  submodule → build/generated/aihints
tools/gen_schema.py                        config.json + docs → settings schema
tools/verify_core.py                       add-on test suites against the generated copy
vendor/AI-Hints/                           git submodule (single source of truth)
```

## Security notes

- The bridge binds to `127.0.0.1` only and rejects requests from non-loopback
  `Origin`s. The bind is the real boundary; the Origin check is defence in depth.
- API keys live in the app's private data directory and are never written to
  logs. They are not bundled in the APK.
- The bridge is a foreground service so AnkiDroid can reach it while reviewing.

## License

MIT. The AI-Hints core and reviewer renderer are MIT-licensed and are used
verbatim from the `vendor/AI-Hints` submodule; see [LICENSE](LICENSE) and
[THIRD-PARTY.md](THIRD-PARTY.md).

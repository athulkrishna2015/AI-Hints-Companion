# Development

## Toolchain

| Tool | Version used here |
| --- | --- |
| JDK | 21 (`mise` supplies it; JDK 17 works) |
| Gradle | 8.11.1 (wrapper) |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| Chaquopy | 16.1.0 (device Python 3.11) |
| Android SDK | platform 34, build-tools 34.0.0 |
| Build Python | CPython 3.8–3.13 (`uv python find 3.11`) |

Create `local.properties` with your SDK path (it is gitignored):

```properties
sdk.dir=/home/you/Android/Sdk
```

`app/build.gradle.kts` finds the build interpreter from
`CHAQUOPY_BUILD_PYTHON`, then `/usr/bin/python3.{13,12,11,10}`. The `justfile`
sets it from `uv` and sets `JAVA_HOME` from `mise`, so `just build` works without
exporting anything.

## Common commands

```bash
just submodule      # git submodule update --init --recursive
just sync           # materialise vendor/AI-Hints into build/generated/aihints
just sync-check     # fail if the generated tree is stale
just verify         # add-on's 20 core suites against the generated copy
just schema         # regenerate the settings schema only
just build          # assembleDebug
just install        # adb install -r
just run            # install + launch MainActivity
just release        # assembleRelease (unsigned)
just apk            # print the debug APK path
```

Raw Gradle:

```bash
JAVA_HOME=... CHAQUOPY_BUILD_PYTHON=... ./gradlew :app:assembleDebug
```

## Layout

```text
app/src/main/java/dev/aihints/companion/
  AiHintsApp.kt            Application, manual DI graph, service start
  MainActivity.kt          permissions, SAF import, JSON VIEW intent
  anki/                    AnkiPort, ContentProviderPort, AnkiConnectPort,
                           BackendManager (probe + fallback)
  bridge/                  BridgeServer (NanoHTTPD), EventHub (SSE queues)
  python/                  PyEngine (Chaquopy), PyPort (Java callback)
  service/                 BridgeService (foreground)
  ui/                      Compose: AppRoot, Settings, Batch, Logs, components
  core/                    Prefs, AppLog, Json

app/src/main/python/aihints_android/
  api.py                   facade called from Kotlin (state, command, config,
                           templates, batch, logs)
  engine.py                generation, writes, snapshots, undo/redo
  store.py                 config store + undo stack
  templates.py             template install/remove/status
  batch.py                 batch runner
  proxies.py               note/model/card proxies over AnkiPort

app/src/main/assets/ai_hints_bridge.js   reviewer glue (hand-written)
tools/materialize_core.py                submodule → generated tree
tools/gen_schema.py                      config.json + docs → schema
tools/verify_core.py                     upstream tests against generated tree
vendor/AI-Hints/                         submodule (do not edit)
```

## Adding a reviewer command

1. Map the `ai_hints_*` string in `app/src/main/assets/ai_hints_bridge.js`
   (`pycmd` switch).
2. Add the action in `aihints_android/api.py::command`.
3. Implement it in `engine.py`; push an undo snapshot before writing fields.
4. Document it in `docs/bridge-protocol.md`.
5. If the renderer calls it, confirm the renderer's `pycmd` name matches.

## Adding a setting

Most settings need no code:

1. Add the key and default to the add-on (`vendor/AI-Hints/addon/config.json`).
2. Add a description to the add-on's `docs/config-reference.md`.
3. Rebuild. `config_schema.json` is generated, so the key appears under its
   documented group with the right widget.

Only keys that need special UI handling (secret fields, provider/model pickers)
need changes in `SettingsScreen.kt` / `gen_schema.py`.

## Python development without a device

The Python layer runs on the host CPython:

```bash
just sync
PYTHONPATH=build/generated/aihints/python:app/src/main/python \
  python3 - <<'PY'
from aihints_android import api
...
PY
```

`verify_core.py` builds a throwaway root with an `aqt` stub and the generated
core, then runs the add-on's suites in script mode. It is the fastest way to
catch a bad submodule bump.

## Style and invariants

- Never edit `vendor/AI-Hints`. Fix the adapter or bump the submodule.
- Never commit generated files (`build/generated/aihints/`, `config_schema.json`,
  `_ai_hints_template.js`).
- API keys must never be logged or committed.
- The bridge stays loopback-only; do not relax the Origin check.
- Emit exactly one terminal `generating:false` per generation.
- Template writes are schema changes: warn the user about the full sync.

## Release builds

`just release` produces an unsigned APK. Add a signing config in
`app/build.gradle.kts` (or sign with `apksigner`) before distributing. The
package id is `dev.aihints.companion`; changing it orphans app data (including
imported keys).

## CI sketch

```yaml
- uses: actions/checkout@v4
  with: { submodules: recursive }
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: 21 }
- run: just sync-check
- run: just verify
- run: just build
```

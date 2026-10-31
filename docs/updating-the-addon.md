# Updating the add-on

`vendor/AI-Hints` is a git submodule pinned to a commit. The app never contains a
copy of the add-on: every build materialises the pinned commit into
`build/generated/aihints/`.

## Update loop

```bash
git -C vendor/AI-Hints fetch --tags
git -C vendor/AI-Hints checkout v8.7.0        # tag, branch or commit
git add vendor/AI-Hints                       # record the new gitlink

just sync        # optional: refresh the generated tree for IDE indexing
just verify      # run the add-on's 20 core suites against the generated copy
just build       # APK now contains the new add-on
```

Commit the updated gitlink:

```bash
git commit -m "vendor: AI-Hints v8.7.0"
```

Reviewers can see the exact add-on version in the app's Status screen and in the
startup log (`addon 8.6.9 commit 338d612`).

## Nested submodule

The add-on has its own submodule at `addon/latex_fixer`
(`https://github.com/athulkrishna2015/ai-latex-fixer.git`). A non-recursive
checkout leaves it empty, which breaks math normalisation and two upstream test
suites. Always initialise recursively:

```bash
git submodule update --init --recursive
```

The Gradle task fails early with that exact instruction if the directory is
empty.

## What is materialised

`tools/materialize_core.py` has an explicit whitelist. If a new add-on release
introduces a module the Android app needs, add it there:

```python
FILES = ["ai_client.py", "card_parser.py", "logger.py", "config_io.py",
         "mobile_sync.py", "model_updater.py", "config.json", "VERSION"]

DIRS = ["json_repair", "latex_fixer", "web"]

ASSET_FILES = {"web/template.js": "_ai_hints_template.js"}
```

Generated output:

| Path | Consumer |
| --- | --- |
| `python/ai_hints_core/` | Chaquopy source dir; imported by `aihints_android` |
| `assets/_ai_hints_template.js` | Served at `/template.js` |
| `assets/config_schema.json` | Settings screen |
| `MANIFEST.json` | Version/commit/hashes for diagnostics |

`__init__.py` and `config_ui/__init__.py` are generated shims: the real add-on
`__init__` imports `aqt`, and `config_ui` imports Qt widgets. The shims keep the
core importable under Chaquopy.

## Keeping the app layer in sync

The app layer (`app/src/main/python/aihints_android/`) calls into the core:

- `ai_hints_core.ai_client.AIClient`
- `ai_hints_core.card_parser.CardParser`
- `ai_hints_core.logger.logger` / `log_context`
- `ai_hints_core.mobile_sync`
- `ai_hints_core.config_io`

If upstream renames or changes the signature of one of these, `just verify`
usually fails first (it runs the add-on's own tests). Then fix the thin adapter
in `aihints_android/engine.py` or `templates.py` — do not patch the submodule.

Renderer changes need no app change: the APK serves `web/template.js` verbatim.
Template install markers live in `aihints_android/templates.py`; if upstream
changes the `AI-HINTS-BEGIN`/`END` markers, update the installer there.

## Drift checks

```bash
just sync-check      # generated tree must match the submodule byte-for-byte
just verify          # upstream test suites must pass
```

Both are cheap enough for CI. A typical job:

```yaml
- run: git submodule update --init --recursive
- run: just sync-check
- run: just verify
- run: just build
```

`sync-check` reports stale files and prints the command to regenerate them. It is
not part of `assembleDebug` (the build regenerates automatically); it exists to
catch generated files that were committed or hand-edited.

## Pinning policy

- Pin to a **release tag** for shipped builds.
- Tracking `master` is fine for local experiments but makes APKs
  non-reproducible.
- The submodule URL is the public GitHub remote, so a fresh clone can fetch it
  without your local checkout.

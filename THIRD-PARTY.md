# Third-party notices

## AI-Hints

- Source: https://github.com/athulkrishna2015/AI-Hints
- License: MIT
- Used as: git submodule at `vendor/AI-Hints`

The app materialises the following from the submodule at build time and does not
modify them:

- `addon/ai_client.py`, `card_parser.py`, `logger.py`, `config_io.py`,
  `mobile_sync.py`, `model_updater.py`, `config.json`, `VERSION`
- `addon/json_repair/`, `addon/latex_fixer/` (itself a submodule:
  https://github.com/athulkrishna2015/ai-latex-fixer, MIT)
- `addon/web/template.js` (served as `_ai_hints_template.js`)

The Android app layer (`app/src/main/python/aihints_android/`), the Kotlin code,
and `app/src/main/assets/ai_hints_bridge.js` are original to this project.

## Libraries

| Library | License |
| --- | --- |
| Chaquopy | MIT |
| NanoHTTPD | BSD-3-Clause |
| AndroidX / Jetpack Compose | Apache-2.0 |
| Kotlin standard library | Apache-2.0 |
| OkHttp | Apache-2.0 |

See each dependency's `LICENSE`/`NOTICE` in the Gradle cache or upstream
repository for the full text.

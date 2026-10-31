# AI-Hints Companion documentation

AI-Hints Companion brings the [AI-Hints](https://github.com/athulkrishna2015/AI-Hints)
Anki add-on to Android. It runs next to **unmodified AnkiDroid** and reuses the
add-on's own AI client, card parser and reviewer renderer.

| Document | Read it when |
| --- | --- |
| [Setup](setup.md) | Installing the app and getting the first card working |
| [Architecture](architecture.md) | Understanding how the pieces fit together |
| [Bridge protocol](bridge-protocol.md) | Working on the renderer, bridge or automation |
| [Configuration](configuration.md) | Importing `meta.json`, API keys, batch runs |
| [Updating the add-on](updating-the-addon.md) | Porting a new AI-Hints release into the app |
| [Troubleshooting](troubleshooting.md) | Something is unavailable, empty or not rendering |
| [Development](development.md) | Building, testing and changing the app |

## The short version

```text
AnkiDroid card WebView
  ├─ bridge.js      (this app, hand-written glue)
  ├─ template.js    (AI-Hints renderer, verbatim)
  └─ HTTP/SSE → 127.0.0.1:8766

AI-Hints Companion foreground service
  ├─ NanoHTTPD bridge
  ├─ Chaquopy: aihints_android → ai_hints_core
  └─ AnkiDroid ContentProvider (or AnkiConnect fallback)
```

- The add-on is a git submodule at `vendor/AI-Hints`. Nothing from it is copied
  into this repository; Gradle materialises it into `build/generated/aihints/`.
- AnkiDroid stays stock. The app only needs its external API permission.
- The renderer is served over loopback, so renderer fixes ship with an APK and
  do not require an AnkiWeb media sync.

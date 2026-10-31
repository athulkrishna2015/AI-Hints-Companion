#!/usr/bin/env python3
"""Build the settings-screen schema from the add-on's config + docs.

The app renders every add-on key, so the settings UI never falls behind a
config addition: defaults come from ``config.json`` (authoritative), labels and
descriptions from ``docs/config-reference.md`` when available.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ADDON = REPO_ROOT / "vendor" / "AI-Hints"
OUT = REPO_ROOT / "build" / "generated" / "aihints" / "assets" / "config_schema.json"

WIDGET_OVERRIDES = {
    "api_keys": "secret_map",
    "models": "string_map",
    "model_fallbacks": "list_map",
    "disabled_fallback_models": "list_map",
    "thinking_levels": "string_map",
    "custom_providers": "json",
    "shortcuts": "string_map",
    "system_prompt": "multiline",
    "additional_system_instructions": "multiline",
    "provider_priority": "list",
    "disabled_providers": "list",
    "disabled_global_model_priority": "list",
    "global_model_priority": "list",
}

SECRET_KEYS = {"api_keys", "custom_providers"}

ROW_RE = re.compile(r"^\|\s*`([^`]+)`\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|\s*$")


def parse_docs(path: Path) -> dict:
    meta: dict = {}
    if not path.is_file():
        return meta
    section = "General"
    group = "General"
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("## "):
            section = line[3:].strip()
            group = section
            continue
        if line.startswith("### "):
            group = line[4:].strip()
            continue
        match = ROW_RE.match(line)
        if not match:
            continue
        key, default, purpose = match.groups()
        if key.lower() in {"key", "type", "name"}:
            continue
        meta[key] = {
            "section": section,
            "group": group,
            "defaultDoc": default,
            "description": purpose,
        }
    return meta


def widget_for(key: str, value) -> str:
    if key in WIDGET_OVERRIDES:
        return WIDGET_OVERRIDES[key]
    if isinstance(value, bool):
        return "switch"
    if isinstance(value, int):
        return "number"
    if isinstance(value, float):
        return "decimal"
    if isinstance(value, list):
        return "list"
    if isinstance(value, dict):
        return "json"
    if isinstance(value, str) and (len(value) > 120 or "\n" in value):
        return "multiline"
    return "text"


def build(addon: Path, out: Path) -> dict:
    """Generate the settings schema for ``addon`` into ``out``."""
    addon = Path(addon).resolve()
    out = Path(out)
    config = json.loads((addon / "addon" / "config.json").read_text(encoding="utf-8"))
    docs = parse_docs(addon / "docs" / "config-reference.md")

    entries = []
    for key in sorted(config, key=lambda k: (docs.get(k, {}).get("section", "~"), k)):
        value = config[key]
        info = docs.get(key, {})
        entries.append(
            {
                "key": key,
                "type": type(value).__name__,
                "widget": widget_for(key, value),
                "default": value,
                "section": info.get("section", "Other"),
                "group": info.get("group", "Other"),
                "description": info.get("description", ""),
                "secret": key in SECRET_KEYS,
            }
        )

    payload = {
        "addonVersion": (addon / "addon" / "VERSION").read_text(encoding="utf-8").strip(),
        "keys": entries,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return payload


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--addon", type=Path, default=DEFAULT_ADDON)
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()
    addon = args.addon.expanduser().resolve()
    out = args.out.expanduser().resolve()
    payload = build(addon, out)
    entries = payload["keys"]
    try:
        shown = out.relative_to(REPO_ROOT)
    except ValueError:
        shown = out
    print(f"wrote {len(entries)} keys -> {shown}")
    missing = [e["key"] for e in entries if not e["description"]]
    if missing:
        print(f"  {len(missing)} keys without a doc description: {', '.join(missing[:8])}...")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

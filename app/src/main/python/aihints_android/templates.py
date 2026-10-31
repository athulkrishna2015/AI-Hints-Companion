"""Install/remove the AI-Hints card-template block through the Anki backend.

The block itself is produced by the add-on's own ``mobile_sync`` builders, so
placement on the front/back, cloze handling and the field-injection rules stay
byte-for-byte identical to desktop. The only addition is one script tag that
loads the bridge from this app before the upstream renderer runs.
"""

from __future__ import annotations

import json
import re
from typing import Any, Dict, List, Optional

from ai_hints_core import mobile_sync

MARKER_RE = re.compile(r"<!-- AI-HINTS-BEGIN -->.*?<!-- AI-HINTS-END -->", re.DOTALL)
MEDIA_SCRIPT = "<script src='_ai_hints_template.js'></script>"


def _config_js(config: Dict[str, Any]) -> str:
    """Mirror mobile_sync.auto_update_mobile_setup's inline config exactly."""

    def flag(key: str, default: bool) -> str:
        return "true" if config.get(key, default) else "false"

    shortcuts = json.dumps(config.get("shortcuts", {}) or {})
    return (
        "window.aiHintsMobileConfig = { "
        f"useEmojis: {flag('mobile_use_emojis', False)}, "
        f"showExtraButtons: {flag('mobile_show_extra_buttons', False)}, "
        f"autoShowHints: {flag('auto_show_hints', True)}, "
        f"autoShowOptions: {flag('auto_show_options', True)}, "
        f"autoShowHintsAnswer: {flag('auto_show_hints_answer', True)}, "
        f"autoShowOptionsAnswer: {flag('auto_show_options_answer', True)}, "
        f"optionsBeforeHints: {flag('options_before_hints', True)}, "
        f"shortcuts: {shortcuts} "
        "};"
    )


def bridge_tag(port: int) -> str:
    return f'<script src="http://127.0.0.1:{port}/bridge.js"></script>'


def renderer_tag(port: int) -> str:
    return f'<script src="http://127.0.0.1:{port}/template.js"></script>'


def build_block(
    field_name: str,
    template_html: str,
    config: Dict[str, Any],
    *,
    is_cloze: bool,
    is_front: bool,
    port: int,
) -> str:
    block = mobile_sync._get_full_template_block(
        field_name,
        template_html,
        _config_js(config),
        is_cloze=is_cloze,
        is_front=is_front,
    )
    # Both scripts come from the companion's local bridge, so no media sync is
    # needed and the renderer always matches the bundled add-on core. Order
    # matters: bridge.js installs window.aiHintsUiConfig (which flips
    # template.js into add-on mode) before the renderer parses.
    block = block.replace(
        MEDIA_SCRIPT, f"{bridge_tag(port)}\n{renderer_tag(port)}"
    )
    return block


def strip_block(html: str, trim: bool = True) -> str:
    result = MARKER_RE.sub("", html or "")
    return result.strip() if trim else result


def has_block(html: str) -> bool:
    return "<!-- AI-HINTS-BEGIN -->" in (html or "")


def install_model(model: Dict[str, Any], config: Dict[str, Any], port: int) -> bool:
    """Rewrite every template of one note type. Returns True if anything changed."""
    fields = model.get("flds") or []
    if not fields:
        return False
    field_name = str(fields[0].get("name", ""))
    is_cloze = int(model.get("type") or 0) == 1
    changed = False
    for tmpl in model.get("tmpls") or []:
        for side in ("qfmt", "afmt"):
            old_html = tmpl.get(side) or ""
            clean = strip_block(old_html)
            block = build_block(
                field_name,
                clean,
                config,
                is_cloze=is_cloze,
                is_front=(side == "qfmt"),
                port=port,
            )
            new_html = mobile_sync._insert_template_block(
                clean, block, is_cloze=is_cloze, is_front=(side == "qfmt")
            )
            if new_html != old_html:
                tmpl[side] = new_html
                changed = True
    return changed


def remove_model(model: Dict[str, Any]) -> bool:
    changed = False
    for tmpl in model.get("tmpls") or []:
        for side in ("qfmt", "afmt"):
            old_html = tmpl.get(side) or ""
            new_html = strip_block(old_html, trim=False)
            if new_html != old_html:
                tmpl[side] = new_html
                changed = True
    return changed


def block_state(model: Dict[str, Any], port: int) -> List[Dict[str, Any]]:
    out = []
    for tmpl in model.get("tmpls") or []:
        for side in ("qfmt", "afmt"):
            html = tmpl.get(side) or ""
            present = has_block(html)
            out.append(
                {
                    "ord": tmpl.get("ord"),
                    "name": tmpl.get("name"),
                    "side": "front" if side == "qfmt" else "back",
                    "installed": present,
                    "bridge": bridge_tag(port) in html
                    and renderer_tag(port) in html,
                }
            )
    return out

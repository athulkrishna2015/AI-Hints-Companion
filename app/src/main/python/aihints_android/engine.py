"""Android orchestration: read a card, call the add-on's AI core, write it back.

Every behaviour that can be shared with the add-on (parsing, block layout,
cloze scoping, stale detection, provider fallback) comes from the vendored
``ai_hints_core`` package. This module only supplies the pieces desktop gets
from Anki/Qt: note access, persistence, UI state and event delivery.
"""

from __future__ import annotations

import copy
import json
import re
import threading
import time
import traceback
from typing import Any, Dict, List, Optional

from ai_hints_core.ai_client import AIClient
from ai_hints_core.card_parser import CardParser
from ai_hints_core.logger import log_context, logger

from .proxies import CardProxy, NoteProxy, note_from_json
from .store import ConfigStore, UndoStack
from . import templates as template_ops

try:  # pragma: no cover - only unavailable on very old AnkiDroid builds
    from ai_hints_core.ai_client import PROVIDER_ORDER
except Exception:  # pragma: no cover
    PROVIDER_ORDER = []


class GenerationCancelled(Exception):
    pass


class Engine:
    def __init__(self, store: ConfigStore, port: Any):
        self.store = store
        self.port = port
        self.undo = UndoStack()
        self._lock = threading.RLock()
        self._note_locks: Dict[int, threading.RLock] = {}
        self._generating: Dict[int, threading.Event] = {}
        self._choices_cache: Dict[str, List[Dict[str, Any]]] = {}
        self._review_token = str(int(time.time() * 1000))
        self._last_error: Optional[str] = None

    # ------------------------------------------------------------------ util
    def config(self) -> Dict[str, Any]:
        return self.store.get()

    def _parser(self) -> CardParser:
        config = self.config()
        return CardParser(
            mathjax_format=config.get("mathjax_format", "delimiters"),
            fix_latex=config.get("fix_latex", False),
        )

    def _note_lock(self, nid: int) -> threading.RLock:
        with self._lock:
            lock = self._note_locks.get(nid)
            if lock is None:
                lock = threading.RLock()
                self._note_locks[nid] = lock
            return lock

    def _emit(self, nid: int, event: Dict[str, Any]) -> None:
        event = dict(event)
        event["nid"] = int(nid)
        try:
            self.port.emit(json.dumps(event, ensure_ascii=False))
        except Exception as exc:  # pragma: no cover - bridge gone
            logger.debug(f"AI-Hints: event emit failed: {exc}")

    def _toast(self, nid: int, message: str) -> None:
        self._emit(nid, {"type": "toast", "message": message})

    # ----------------------------------------------------------------- state
    def read_note(self, nid: int) -> Optional[NoteProxy]:
        raw = self.port.readNote(int(nid))
        return note_from_json(raw or "")

    def payload_for_card(
        self, parser: CardParser, note: NoteProxy, card: CardProxy
    ) -> Optional[Dict[str, Any]]:
        block = None
        try:
            block = parser.find_hints_block(note, card)
        except Exception as exc:
            logger.debug(f"AI-Hints: find_hints_block failed: {exc}")

        if not block:
            # Stale blocks are intentionally hidden by find_hints_block; the
            # editor/undo paths still need their data.
            block = self._find_any_block(parser, note, card)
        if not block:
            return None

        try:
            inner = block[block.index(">") + 1 :]
            inner = re.sub(r"(</div>\s*)+$", "", inner)
            parsed = parser._parse_json_payload(inner)
        except Exception as exc:
            logger.debug(f"AI-Hints: payload parse failed: {exc}")
            return None
        if not isinstance(parsed, dict):
            return None

        key = f"c{card.ord + 1}"
        if parser._is_keyed_payload(parsed) and key in parsed and "hints" not in parsed:
            data = parsed.get(key)
        else:
            data = parsed
        return data if isinstance(data, dict) and data else None

    def _find_any_block(
        self, parser: CardParser, note: NoteProxy, card: CardProxy
    ) -> Optional[str]:
        try:
            from ai_hints_core.card_parser import _iter_hint_blocks
        except Exception:
            return None
        for value in note.values():
            if not isinstance(value, str):
                continue
            try:
                matches = list(_iter_hint_blocks(value))
            except Exception:
                continue
            for match in matches:
                block = match.group(0)
                try:
                    if parser._block_matches_card(block, card):
                        return block
                except Exception:
                    continue
        return None

    def has_any_block(self, note: NoteProxy) -> bool:
        for value in note.values():
            if isinstance(value, str) and "ai-hints-json" in value:
                return True
        return False

    def is_skipped(self, parser: CardParser, note: NoteProxy, card: CardProxy) -> bool:
        data = self.payload_for_card(parser, note, card)
        return bool(data and data.get("_skipped"))

    # -------------------------------------------------------------- ui config
    def _model_choices(self, config: Dict[str, Any]) -> List[Dict[str, Any]]:
        fingerprint = json.dumps(
            [
                config.get("ai_provider"),
                config.get("provider_priority"),
                config.get("model_fallbacks"),
                config.get("disabled_fallback_models"),
                config.get("disabled_providers"),
                config.get("custom_providers"),
                config.get("models"),
            ],
            sort_keys=True,
            default=str,
        )
        cached = self._choices_cache.get(fingerprint)
        if cached is not None:
            return cached
        try:
            client = AIClient(config)
            primary = config.get("ai_provider", "openai")
            ready_candidates = client._candidate_providers(primary)
            custom = config.get("custom_providers") or {}
            if not isinstance(custom, dict):
                custom = {}
            configured_fallbacks = config.get("model_fallbacks") or {}
            if not isinstance(configured_fallbacks, dict):
                configured_fallbacks = {}
            disabled_map = config.get("disabled_fallback_models") or {}
            if not isinstance(disabled_map, dict):
                disabled_map = {}
            disabled_providers = config.get("disabled_providers") or []
            if not isinstance(disabled_providers, list):
                disabled_providers = []

            priority = config.get("provider_priority")
            if not isinstance(priority, list) or not priority:
                priority = list(PROVIDER_ORDER) + [
                    p for p in custom.keys() if isinstance(p, str)
                ]
            universe: List[str] = []
            for provider in [primary] + list(priority) + [
                p for p in custom.keys() if isinstance(p, str)
            ]:
                if provider and provider not in universe:
                    universe.append(provider)

            choices: List[Dict[str, Any]] = []
            for provider in universe:
                custom_entry = custom.get(provider)
                primary_model = ""
                extra_fallbacks = None
                if isinstance(custom_entry, dict):
                    primary_model = str(custom_entry.get("model") or "")
                    extra_fallbacks = custom_entry.get("model_fallbacks") or []
                enabled_fb = (
                    client._enabled_fallback_models(provider)
                    if provider in custom
                    else []
                )
                candidates = [
                    *enabled_fb,
                    primary_model or client._get_model(provider),
                    *client._model_list(extra_fallbacks),
                    *client._model_list(configured_fallbacks.get(provider, [])),
                ]
                disabled_set = disabled_map.get(provider, [])
                if not isinstance(disabled_set, (list, set)):
                    disabled_set = []
                enabled_models: List[str] = []
                disabled_models: List[str] = []
                seen = set()
                for candidate in candidates:
                    model = client._normalize_model(provider, candidate)
                    if not model or model in seen:
                        continue
                    seen.add(model)
                    if model in disabled_set:
                        disabled_models.append(model)
                    else:
                        enabled_models.append(model)
                if not enabled_models and not disabled_models:
                    continue
                blacklisted = [
                    m for m in enabled_models if client._is_model_failed(provider, m)
                ]
                choices.append(
                    {
                        "provider": provider,
                        "enabled": provider in ready_candidates
                        and provider not in disabled_providers,
                        "models": enabled_models,
                        "disabled_models": disabled_models,
                        "blacklisted": blacklisted,
                    }
                )
            choices.sort(key=lambda c: 0 if c["enabled"] else 1)
            self._choices_cache.clear()
            self._choices_cache[fingerprint] = choices
            return choices
        except Exception as exc:
            logger.debug(f"AI-Hints: model choices failed: {exc}")
            return []

    def ui_config(
        self,
        nid: int,
        ord_: int,
        *,
        is_answer: bool,
        has_data: bool,
        auto_reveal: bool = False,
    ) -> Dict[str, Any]:
        config = self.config()
        with self._lock:
            generating = nid in self._generating
            preg = any(cid != nid for cid in self._generating)
        return {
            "show_on_card": config.get("show_on_card", True),
            "auto_reveal": auto_reveal,
            "auto_show_hints": config.get("auto_show_hints", True),
            "auto_show_options": config.get("auto_show_options", True),
            "auto_show_hints_answer": config.get("auto_show_hints_answer", True),
            "auto_show_options_answer": config.get("auto_show_options_answer", True),
            "options_before_hints": config.get("options_before_hints", True),
            "generation_enabled": bool(config.get("generate_hints_enabled", True))
            or bool(config.get("generate_options_enabled", True)),
            "rate_good_on_correct": config.get("rate_good_on_correct", False),
            "rate_again_on_wrong": config.get("rate_again_on_wrong", False),
            "rate_good_delay_ms": config.get("rate_good_delay_ms", 0),
            "rate_again_delay_ms": config.get("rate_again_delay_ms", 1000),
            "manual_show_hints": config.get("manual_show_hints", True),
            "manual_show_options": config.get("manual_show_options", False),
            "mathjax_format": config.get("mathjax_format", "delimiters"),
            "fix_latex": config.get("fix_latex", False),
            "review_token": self._review_token,
            "is_generating": generating,
            "is_pregenerating": preg,
            "is_lingering": False,
            "shortcuts": config.get("shortcuts", {}),
            "is_answer_side": bool(is_answer),
            "hints_font_size": config.get("hints_font_size", ""),
            "answer_display_position": config.get("answer_display_position", "between"),
            "has_data": bool(has_data),
            "model_choices": self._model_choices(config),
        }

    def state(self, nid: int, ord_: int, is_answer: bool) -> Dict[str, Any]:
        note = self.read_note(nid)
        if note is None:
            return {"ok": False, "error": "note-not-found", "nid": nid}
        parser = self._parser()
        card = CardProxy(nid, ord_)
        data = self.payload_for_card(parser, note, card)
        model = note.model() or {}
        return {
            "ok": True,
            "card": {
                "id": str(nid),
                "nid": nid,
                "ord": ord_,
                "model": model.get("name", ""),
            },
            "hints": data,
            "uiConfig": self.ui_config(
                nid, ord_, is_answer=is_answer, has_data=data is not None
            ),
            "flags": {
                "hasAnyBlock": self.has_any_block(note),
                "isSkipped": bool(data and data.get("_skipped")),
                "tags": note.tags,
            },
        }

    # ----------------------------------------------------------------- writes
    def _write_note(
        self,
        note: NoteProxy,
        snapshot: Optional[Dict[str, Any]],
        label: str,
    ) -> bool:
        changed = note.changed_fields()
        tags_changed = note.changed_tags()
        if not changed and not tags_changed:
            return False
        if snapshot is not None:
            self.undo.push(note.nid, snapshot, label)
        if changed:
            self.port.updateFields(
                note.nid, json.dumps(changed, ensure_ascii=False)
            )
        if tags_changed:
            self.port.updateTags(note.nid, json.dumps(note.tags, ensure_ascii=False))
        return True

    def _apply_tags(
        self, note: NoteProxy, data: Dict[str, Any], config: Dict[str, Any]
    ) -> None:
        skipped = bool(data.get("_skipped"))
        has_hints = bool(data.get("hints") or data.get("options"))
        if config.get("tag_hinted_notes", False):
            self._set_tag(note, config.get("hint_tag", "ai-hints") or "ai-hints", has_hints and not skipped)
        if config.get("tag_skipped_notes", True):
            self._set_tag(
                note,
                config.get("skip_tag", "ai-hints::skipped") or "ai-hints::skipped",
                skipped,
            )

    @staticmethod
    def _set_tag(note: NoteProxy, tag: str, add: bool) -> None:
        if not tag:
            return
        has = any(t.lower() == tag.lower() for t in note.tags)
        if add and not has:
            note.tags.append(tag)
        elif not add and has:
            note.tags = [t for t in note.tags if t.lower() != tag.lower()]

    # ------------------------------------------------------------- generation
    def generate(
        self,
        nid: int,
        ord_: int,
        *,
        override_provider: Optional[str] = None,
        override_model: Optional[str] = None,
        is_pregen: bool = False,
        is_manual: bool = True,
    ) -> Dict[str, Any]:
        cancel = threading.Event()
        with self._lock:
            if nid in self._generating:
                return {"ok": False, "error": "already-generating"}
            self._generating[nid] = cancel

        lock = self._note_lock(nid)
        started = time.time()
        try:
            with lock:
                self._emit(
                    nid,
                    {
                        "type": "generating",
                        "active": True,
                        "status": None,
                        "error": None,
                        "cardId": str(nid),
                        "isPregen": is_pregen,
                    },
                )
                note = self.read_note(nid)
                if note is None:
                    raise RuntimeError("Note not found (was it deleted?)")
                config = self.config()
                parser = self._parser()
                card = CardProxy(nid, ord_)

                front, back = parser.get_note_content(note, card)
                if not front.strip():
                    raise RuntimeError(
                        "Nothing to send: this card's front side is empty."
                    )

                client = AIClient(config, is_pregen=is_pregen)
                client.status_cb = lambda status: self._emit(
                    nid,
                    {
                        "type": "generating",
                        "active": True,
                        "status": status,
                        "error": None,
                        "cardId": str(nid),
                        "isPregen": is_pregen,
                    },
                )
                log_context.source = "pregen" if is_pregen else "reviewer"
                try:
                    data = client.generate_options(
                        front,
                        back,
                        override_provider=override_provider,
                        override_model=override_model,
                    )
                finally:
                    log_context.source = None

                if cancel.is_set():
                    raise GenerationCancelled()

                if not isinstance(data, dict) or (
                    not data.get("hints")
                    and not data.get("options")
                    and not data.get("_skipped")
                ):
                    raise RuntimeError(
                        self._last_error
                        or "The AI returned no usable hints. Check the provider settings."
                    )

                snapshot = note.snapshot()
                toggles = {
                    "show_hints_button": config.get("show_hints_button", True),
                    "show_options_button": config.get("show_options_button", True),
                }
                if not parser.update_note_with_hints(note, data, toggles, card):
                    raise RuntimeError("Could not write the hints block to this note.")
                self._apply_tags(note, data, config)
                self._write_note(note, snapshot, "generate")
                self._last_error = None

                written = self.payload_for_card(parser, note, card) or data
                self._emit(
                    nid,
                    {
                        "type": "data",
                        "data": written,
                        "isManual": is_manual,
                        "elapsedMs": int((time.time() - started) * 1000),
                    },
                )
                logger.info(
                    f"AI-Hints: generated for note {nid} ord {ord_} "
                    f"in {time.time() - started:.1f}s"
                )
                self._emit(
                    nid,
                    {
                        "type": "generating",
                        "active": False,
                        "status": None,
                        "error": None,
                        "cardId": str(nid),
                        "isPregen": is_pregen,
                    },
                )
                return {"ok": True, "data": written}
        except GenerationCancelled:
            logger.info(f"AI-Hints: generation cancelled for note {nid}")
            self._emit(nid, {"type": "cancelled"})
            self._emit(
                nid,
                {
                    "type": "generating",
                    "active": False,
                    "status": None,
                    "error": None,
                    "cardId": str(nid),
                    "isPregen": is_pregen,
                },
            )
            return {"ok": False, "error": "cancelled"}
        except Exception as exc:
            message = str(exc) or exc.__class__.__name__
            self._last_error = message
            logger.error(f"AI-Hints: generation failed for note {nid}: {message}")
            logger.debug(traceback.format_exc())
            self._emit(
                nid,
                {
                    "type": "generating",
                    "active": False,
                    "status": "Failed",
                    "error": message,
                    "cardId": str(nid),
                    "isPregen": is_pregen,
                },
            )
            return {"ok": False, "error": message}
        finally:
            with self._lock:
                self._generating.pop(nid, None)

    def cancel(self, nid: Optional[int] = None) -> Dict[str, Any]:
        with self._lock:
            if nid is None:
                events = list(self._generating.values())
            else:
                event = self._generating.get(int(nid))
                events = [event] if event else []
        for event in events:
            event.set()
        return {"ok": True, "cancelled": len(events)}

    # -------------------------------------------------------------- mutations
    def _mutate(
        self,
        nid: int,
        ord_: int,
        label: str,
        fn,
        *,
        emit_clear: bool = False,
    ) -> Dict[str, Any]:
        lock = self._note_lock(nid)
        with lock:
            note = self.read_note(nid)
            if note is None:
                return {"ok": False, "error": "note-not-found"}
            parser = self._parser()
            card = CardProxy(nid, ord_)
            snapshot = note.snapshot()
            config = self.config()
            try:
                changed = fn(note, parser, card, config)
            except Exception as exc:
                logger.error(f"AI-Hints: {label} failed for note {nid}: {exc}")
                return {"ok": False, "error": str(exc) or exc.__class__.__name__}
            if not changed:
                return {"ok": True, "changed": False}
            self._write_note(note, snapshot, label)
            data = self.payload_for_card(parser, note, card)
            if emit_clear or data is None:
                self._emit(nid, {"type": "clear"})
            else:
                self._emit(nid, {"type": "data", "data": data, "isManual": True})
            return {"ok": True, "changed": True, "data": data}

    def clear(self, nid: int, ord_: int) -> Dict[str, Any]:
        def run(note, parser, card, config):
            changed = parser.clear_hints_from_note(note, card)
            if config.get("tag_hinted_notes", False):
                self._set_tag(note, config.get("hint_tag", "ai-hints"), False)
            if config.get("tag_skipped_notes", True):
                self._set_tag(note, config.get("skip_tag", "ai-hints::skipped"), False)
            return changed

        return self._mutate(nid, ord_, "clear", run, emit_clear=True)

    def skip(self, nid: int, ord_: int) -> Dict[str, Any]:
        def run(note, parser, card, config):
            data = {"hints": [], "options": [], "_skipped": True}
            toggles = {
                "show_hints_button": config.get("show_hints_button", True),
                "show_options_button": config.get("show_options_button", True),
            }
            if not parser.update_note_with_hints(note, data, toggles, card):
                return False
            self._apply_tags(note, data, config)
            return True

        return self._mutate(nid, ord_, "skip", run)

    def unskip(self, nid: int, ord_: int) -> Dict[str, Any]:
        def run(note, parser, card, config):
            changed = parser.unskip_hints_from_note(note, card)
            if config.get("tag_skipped_notes", True):
                self._set_tag(note, config.get("skip_tag", "ai-hints::skipped"), False)
            return changed

        return self._mutate(nid, ord_, "unskip", run)

    def bulk_action(
        self,
        query: str,
        action: str,
        limit: int = 200,
    ) -> Dict[str, Any]:
        """Apply a collection-wide action to cards matching an Anki search.

        Mirrors the add-on's browser context menu (skip / unskip / clear) without
        requiring a desktop browser selection. Generation is handled by the
        batch runner instead because it needs progress and pause control.
        """
        if action not in ("skip", "unskip", "clear", "tag_hinted"):
            return {"ok": False, "error": f"unknown bulk action: {action}"}
        try:
            raw = self.port.findCards(query or "", int(limit))
            cards = json.loads(raw or "[]")
        except Exception as exc:
            return {"ok": False, "error": str(exc) or exc.__class__.__name__}

        seen = set()
        total = 0
        changed = 0
        failed = 0
        errors: List[str] = []
        for entry in cards:
            nid = int(entry.get("nid") or 0)
            ord_ = int(entry.get("ord") or 0)
            if not nid or (nid, ord_) in seen:
                continue
            seen.add((nid, ord_))
            total += 1
            try:
                if action == "skip":
                    result = self.skip(nid, ord_)
                elif action == "unskip":
                    result = self.unskip(nid, ord_)
                elif action == "tag_hinted":
                    result = self._tag_hinted_note(nid)
                else:
                    result = self.clear(nid, ord_)
            except Exception as exc:
                result = {"ok": False, "error": str(exc) or exc.__class__.__name__}
            if result.get("ok"):
                if result.get("changed"):
                    changed += 1
            else:
                failed += 1
                if len(errors) < 5:
                    errors.append(str(result.get("error") or "failed"))
        return {
            "ok": True,
            "action": action,
            "query": query,
            "total": total,
            "changed": changed,
            "failed": failed,
            "errors": errors,
        }

    def _tag_hinted_note(self, nid: int, ord_: int) -> Dict[str, Any]:
        """Add the configured hint tag to a note that already has hints."""
        lock = self._note_lock(nid)
        with lock:
            note = self.read_note(nid)
            if note is None:
                return {"ok": False, "error": "note-not-found"}
            parser = self._parser()
            data = self.payload_for_card(parser, note, CardProxy(nid, ord_))
            if not data or data.get("_skipped") or not (
                data.get("hints") or data.get("options")
            ):
                return {"ok": True, "changed": False}
            config = self.config()
            tag = config.get("hint_tag", "ai-hints") or "ai-hints"
            if any(t.lower() == tag.lower() for t in note.tags):
                return {"ok": True, "changed": False}
            snapshot = note.snapshot()
            self._set_tag(note, tag, True)
            self._write_note(note, snapshot, "tag-hinted")
            return {"ok": True, "changed": True}

    def edit_item(
        self, nid: int, ord_: int, item_type: str, index: int, value: str
    ) -> Dict[str, Any]:
        def run(note, parser, card, config):
            data = self.payload_for_card(parser, note, card)
            if data is None:
                data = {"hints": [], "options": []}
            data = copy.deepcopy(data)
            key = "hints" if item_type == "hints" else "options"
            items = list(data.get(key) or [])
            text = (value or "").strip()
            if index < 0 or index >= len(items):
                if not text:
                    return False
                items.append(text)
            elif text:
                items[index] = text
            else:
                items.pop(index)
            data[key] = items
            data.pop("_skipped", None)
            toggles = {
                "show_hints_button": config.get("show_hints_button", True),
                "show_options_button": config.get("show_options_button", True),
            }
            if not parser.update_note_with_hints(note, data, toggles, card):
                return False
            self._apply_tags(note, data, config)
            return True

        return self._mutate(nid, ord_, "edit-item", run)

    def add_item(self, nid: int, ord_: int, item_type: str, value: str) -> Dict[str, Any]:
        return self.edit_item(nid, ord_, item_type, -1, value)

    def remove_warning(self, nid: int, ord_: int) -> Dict[str, Any]:
        def run(note, parser, card, config):
            return parser.remove_warning_hint_from_note(note, card)

        return self._mutate(nid, ord_, "remove-warning", run)

    def undo_last(self, nid: int, ord_: int) -> Dict[str, Any]:
        lock = self._note_lock(nid)
        with lock:
            note = self.read_note(nid)
            if note is None:
                return {"ok": False, "error": "note-not-found"}
            entry = self.undo.commit_undo(nid, note.current())
            if entry is None:
                return {"ok": False, "error": "nothing-to-undo"}
            note.restore(entry["snapshot"])
            self._write_note(note, None, "")
            parser = self._parser()
            data = self.payload_for_card(parser, note, CardProxy(nid, ord_))
            if data is None:
                self._emit(nid, {"type": "clear"})
            else:
                self._emit(nid, {"type": "data", "data": data, "isManual": True})
            return {"ok": True, "label": entry.get("label")}

    def redo_last(self, nid: int, ord_: int) -> Dict[str, Any]:
        lock = self._note_lock(nid)
        with lock:
            note = self.read_note(nid)
            if note is None:
                return {"ok": False, "error": "note-not-found"}
            entry = self.undo.commit_redo(nid, note.current())
            if entry is None:
                return {"ok": False, "error": "nothing-to-redo"}
            note.restore(entry["snapshot"])
            self._write_note(note, None, "")
            parser = self._parser()
            data = self.payload_for_card(parser, note, CardProxy(nid, ord_))
            if data is None:
                self._emit(nid, {"type": "clear"})
            else:
                self._emit(nid, {"type": "data", "data": data, "isManual": True})
            return {"ok": True, "label": entry.get("label")}

    # -------------------------------------------------------------- templates
    def install_templates(self, port: int, model_id: Optional[int] = None) -> Dict[str, Any]:
        models = json.loads(self.port.listModels() or "[]")
        config = self.config()
        changed_models = 0
        details = []
        for model in models:
            if model_id is not None and int(model.get("id") or 0) != int(model_id):
                continue
            try:
                changed = template_ops.install_model(model, config, port)
            except Exception as exc:
                details.append(
                    {"model": model.get("name"), "error": str(exc)}
                )
                continue
            if changed:
                try:
                    self.port.updateModel(json.dumps(model, ensure_ascii=False))
                except Exception as exc:
                    logger.warning(
                        f"AI-Hints: failed to persist model {model.get('name')}: {exc}"
                    )
                    details.append(
                        {
                            "model": model.get("name"),
                            "changed": True,
                            "persisted": False,
                            "error": str(exc),
                        }
                    )
                    continue
                changed_models += 1
            details.append(
                {
                    "model": model.get("name"),
                    "changed": changed,
                    "templates": template_ops.block_state(model, port),
                }
            )
        return {"ok": True, "changedModels": changed_models, "details": details}

    def remove_templates(self, model_id: Optional[int] = None) -> Dict[str, Any]:
        models = json.loads(self.port.listModels() or "[]")
        changed_models = 0
        for model in models:
            if model_id is not None and int(model.get("id") or 0) != int(model_id):
                continue
            if template_ops.remove_model(model):
                try:
                    self.port.updateModel(json.dumps(model, ensure_ascii=False))
                except Exception as exc:
                    logger.warning(
                        f"AI-Hints: failed to persist removal for model {model.get('name')}: {exc}"
                    )
                    continue
                changed_models += 1
        return {"ok": True, "changedModels": changed_models}

    def template_status(self, port: int) -> Dict[str, Any]:
        models = json.loads(self.port.listModels() or "[]")
        out = []
        for model in models:
            states = template_ops.block_state(model, port)
            out.append(
                {
                    "id": model.get("id"),
                    "name": model.get("name"),
                    "isCloze": int(model.get("type") or 0) == 1,
                    "installed": any(s["installed"] for s in states),
                    "bridge": all(s["bridge"] for s in states if s["installed"]),
                    "templates": states,
                }
            )
        return {"ok": True, "models": out}

    # ------------------------------------------------------------ model tests
    def test_provider(self, provider: str, model: str = "") -> Dict[str, Any]:
        config = self.config()
        try:
            client = AIClient(config)
            front = "The capital of France is ___."
            back = "Paris"
            log_context.source = "model_test"
            try:
                data = client.generate_options(
                    front, back, override_provider=provider, only_this_provider=True,
                    override_model=model or None,
                )
            finally:
                log_context.source = None
            ok = bool(data.get("hints") or data.get("options"))
            return {"ok": ok, "data": data, "error": None if ok else "empty response"}
        except Exception as exc:
            return {"ok": False, "error": str(exc) or exc.__class__.__name__}

    def fetch_models(self, provider: str) -> Dict[str, Any]:
        try:
            client = AIClient(self.config())
            return {"ok": True, "models": client.fetch_models(provider)}
        except Exception as exc:
            return {"ok": False, "error": str(exc) or exc.__class__.__name__}

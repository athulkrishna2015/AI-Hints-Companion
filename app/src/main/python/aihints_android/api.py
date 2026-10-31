"""Chaquopy entry points.

Kotlin owns one process-wide instance of this module. Everything crosses the
boundary as JSON strings so the JNI surface stays tiny and versionable.
"""

from __future__ import annotations

import json
import os
import threading
import traceback
from typing import Any, Dict, Optional

import ai_hints_core
from ai_hints_core import logger as _logger_module
from ai_hints_core.logger import logger

from .batch import BatchRunner
from .engine import Engine
from .store import ConfigStore

_lock = threading.RLock()
_engine: Optional[Engine] = None
_batch: Optional[BatchRunner] = None
_store: Optional[ConfigStore] = None
_port = None
_logger_bound = False


def _json(payload: Any) -> str:
    return json.dumps(payload, ensure_ascii=False, default=str)


def start(config_json: str, data_dir: str, port: Any) -> str:
    """Initialise once per process. Safe to call again after a service restart."""
    global _engine, _batch, _store, _port, _logger_bound
    with _lock:
        _port = port
        _store = ConfigStore(data_dir)
        if config_json:
            try:
                incoming = json.loads(config_json)
                if isinstance(incoming, dict) and incoming:
                    _store.replace(incoming)
            except Exception as exc:
                logger.warning(f"AI-Hints: ignoring invalid startup config: {exc}")
        if not _logger_bound:
            try:
                _logger_module.rebind_file_logging()
            except Exception as exc:  # pragma: no cover
                logger.warning(f"AI-Hints: file logging unavailable: {exc}")
            _logger_bound = True
        _engine = Engine(_store, _port)
        _batch = BatchRunner(_engine)
        return _json(
            {
                "ok": True,
                "addonVersion": ai_hints_core.ADDON_VERSION,
                "addonCommit": ai_hints_core.ADDON_COMMIT,
                "dataDir": data_dir,
                "config": _store.get(),
                "defaults": _store.defaults,
            }
        )


def is_ready() -> bool:
    return _engine is not None


def _require() -> Engine:
    if _engine is None:
        raise RuntimeError("engine not started")
    return _engine


def addon_version() -> str:
    return ai_hints_core.ADDON_VERSION


# --------------------------------------------------------------------- config
def get_config() -> str:
    return _json({"ok": True, "config": _store.get() if _store else {}})


def get_defaults() -> str:
    return _json({"ok": True, "config": _store.defaults if _store else {}})


def set_config(config_json: str) -> str:
    try:
        incoming = json.loads(config_json)
        if not isinstance(incoming, dict):
            raise ValueError("config must be a JSON object")
        saved = _store.replace(incoming)
        if _engine is not None:
            _engine._choices_cache.clear()
        return _json({"ok": True, "config": saved})
    except Exception as exc:
        return _json({"ok": False, "error": str(exc) or exc.__class__.__name__})


def patch_config(patch_json: str) -> str:
    try:
        patch = json.loads(patch_json)
        if not isinstance(patch, dict):
            raise ValueError("patch must be a JSON object")
        saved = _store.update(patch)
        if _engine is not None:
            _engine._choices_cache.clear()
        return _json({"ok": True, "config": saved})
    except Exception as exc:
        return _json({"ok": False, "error": str(exc) or exc.__class__.__name__})


def reset_config() -> str:
    saved = _store.reset()
    if _engine is not None:
        _engine._choices_cache.clear()
    return _json({"ok": True, "config": saved})


def import_config(text: str) -> str:
    """Import an Anki add-on config.

    Accepts either an Anki ``meta.json`` (whose live config lives under the
    ``config`` key) or a bare ``config.json``. Unknown keys are preserved; the
    result is merged over the shipped defaults so new add-on keys get defaults.
    Values are never logged.
    """
    try:
        payload = json.loads(text or "")
    except Exception as exc:
        return _json({"ok": False, "error": f"not valid JSON: {exc}"})
    if not isinstance(payload, dict):
        return _json({"ok": False, "error": "expected a JSON object"})
    incoming = payload.get("config") if isinstance(payload.get("config"), dict) else payload
    if not isinstance(incoming, dict) or not incoming:
        return _json({"ok": False, "error": "no config object found"})

    before = _store.get()
    saved = _store.replace(incoming)
    if _engine is not None:
        _engine._choices_cache.clear()

    changed = sorted(
        key for key in set(before) | set(saved) if before.get(key) != saved.get(key)
    )
    keys = saved.get("api_keys") if isinstance(saved.get("api_keys"), dict) else {}
    providers_with_keys = sorted(
        str(name) for name, value in keys.items() if str(value or "").strip()
    )
    return _json(
        {
            "ok": True,
            "imported": len(incoming),
            "changed": changed,
            "providersWithKeys": providers_with_keys,
            "config": saved,
        }
    )


def export_config() -> str:
    return _json({"ok": True, "config": _store.get() if _store else {}})


# ---------------------------------------------------------------------- cards
def state(nid: int, ord_: int, is_answer: bool) -> str:
    try:
        return _json(_require().state(int(nid), int(ord_), bool(is_answer)))
    except Exception as exc:
        logger.error(f"AI-Hints: state failed: {exc}")
        logger.debug(traceback.format_exc())
        return _json({"ok": False, "error": str(exc) or exc.__class__.__name__})


def command(nid: int, ord_: int, command_json: str) -> str:
    """Dispatch one reviewer command. Generation runs off-thread."""
    try:
        payload = json.loads(command_json or "{}")
        if isinstance(payload, str):
            payload = {"action": payload}
        if not isinstance(payload, dict):
            raise ValueError("command must be an object")
    except Exception as exc:
        return _json({"ok": False, "error": f"bad command: {exc}"})

    action = str(payload.get("action") or "")
    engine = _require()
    nid = int(nid)
    ord_ = int(ord_)

    if action == "generate":
        return _spawn_generate(engine, nid, ord_, payload)
    if action == "generate_override":
        return _spawn_generate(
            engine,
            nid,
            ord_,
            payload,
            provider=payload.get("provider"),
            model=payload.get("model"),
        )
    if action == "cancel":
        return _json(engine.cancel(nid))
    if action == "clear":
        return _json(engine.clear(nid, ord_))
    if action == "skip":
        return _json(engine.skip(nid, ord_))
    if action == "unskip":
        return _json(engine.unskip(nid, ord_))
    if action == "refresh":
        return _json(engine.state(nid, ord_, bool(payload.get("isAnswer"))))
    if action == "edit_item":
        return _json(
            engine.edit_item(
                nid,
                ord_,
                str(payload.get("type") or "hints"),
                int(payload.get("index") or 0),
                str(payload.get("value") or ""),
            )
        )
    if action == "add_item":
        return _json(
            engine.add_item(
                nid,
                ord_,
                str(payload.get("type") or "hints"),
                str(payload.get("value") or ""),
            )
        )
    if action == "remove_warning":
        return _json(engine.remove_warning(nid, ord_))
    if action == "undo":
        return _json(engine.undo_last(nid, ord_))
    if action == "redo":
        return _json(engine.redo_last(nid, ord_))
    if action == "rate_good":
        return _json({"ok": True, "clientAnswer": 3})
    if action == "rate_again":
        return _json({"ok": True, "clientAnswer": 1})
    return _json({"ok": False, "error": f"unknown action: {action}"})


def _spawn_generate(
    engine: Engine,
    nid: int,
    ord_: int,
    payload: Dict[str, Any],
    provider: Optional[str] = None,
    model: Optional[str] = None,
) -> str:
    if provider is None:
        provider = payload.get("provider")
    if model is None:
        model = payload.get("model")
    thread = threading.Thread(
        target=engine.generate,
        kwargs={
            "nid": nid,
            "ord_": ord_,
            "override_provider": provider or None,
            "override_model": model or None,
            "is_pregen": bool(payload.get("isPregen")),
            "is_manual": True,
        },
        name=f"aihints-gen-{nid}",
        daemon=True,
    )
    thread.start()
    return _json({"ok": True, "started": True})


# ------------------------------------------------------------------ templates
def install_templates(port: int, model_id: int = -1) -> str:
    try:
        return _json(
            _require().install_templates(int(port), None if int(model_id) < 0 else int(model_id))
        )
    except Exception as exc:
        logger.error(f"AI-Hints: install templates failed: {exc}")
        return _json({"ok": False, "error": str(exc) or exc.__class__.__name__})


def remove_templates(model_id: int = -1) -> str:
    try:
        return _json(_require().remove_templates(None if int(model_id) < 0 else int(model_id)))
    except Exception as exc:
        return _json({"ok": False, "error": str(exc) or exc.__class__.__name__})


def template_status(port: int) -> str:
    try:
        return _json(_require().template_status(int(port)))
    except Exception as exc:
        return _json({"ok": False, "error": str(exc) or exc.__class__.__name__})


# ---------------------------------------------------------------- model tests
def test_provider(provider: str, model: str = "") -> str:
    try:
        return _json(_require().test_provider(provider, model))
    except Exception as exc:
        return _json({"ok": False, "error": str(exc) or exc.__class__.__name__})


def fetch_models(provider: str) -> str:
    try:
        return _json(_require().fetch_models(provider))
    except Exception as exc:
        return _json({"ok": False, "error": str(exc) or exc.__class__.__name__})


# ---------------------------------------------------------------------- batch
def batch_start(payload_json: str) -> str:
    try:
        payload = json.loads(payload_json or "{}")
        if not isinstance(payload, dict):
            payload = {}
        return _json(
            _batch.start(
                str(payload.get("query") or ""),
                int(payload.get("limit") or 50),
                str(payload.get("provider") or ""),
                str(payload.get("model") or ""),
                bool(payload.get("skipExisting", True)),
            )
        )
    except Exception as exc:
        return _json({"ok": False, "error": str(exc) or exc.__class__.__name__})


def batch_pause() -> str:
    return _json(_batch.pause() if _batch else {"ok": False})


def batch_resume() -> str:
    return _json(_batch.resume() if _batch else {"ok": False})


def batch_cancel() -> str:
    return _json(_batch.cancel() if _batch else {"ok": False})


def batch_status() -> str:
    return _json({"ok": True, "state": _batch.status() if _batch else {}})


# ----------------------------------------------------------------------- logs
def _log_path() -> str:
    if _store is not None:
        return os.path.join(_store.data_dir, "ai_hints.log")
    return ""


def logs_tail(lines: int = 400) -> str:
    path = _log_path()
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            content = handle.readlines()
        return _json({"ok": True, "path": path, "lines": content[-int(lines) :]})
    except FileNotFoundError:
        return _json({"ok": True, "path": path, "lines": []})
    except Exception as exc:
        return _json({"ok": False, "error": str(exc), "lines": []})


def clear_logs() -> str:
    path = _log_path()
    try:
        if path and os.path.exists(path):
            open(path, "w", encoding="utf-8").close()
        return _json({"ok": True})
    except Exception as exc:
        return _json({"ok": False, "error": str(exc)})


def shutdown() -> str:
    try:
        if _engine is not None:
            _engine.cancel()
        if _batch is not None:
            _batch.cancel()
    except Exception:
        pass
    return _json({"ok": True})

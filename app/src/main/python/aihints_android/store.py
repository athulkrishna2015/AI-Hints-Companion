"""Configuration, data directory and per-card undo history."""

from __future__ import annotations

import copy
import importlib.util
import json
import os
import threading
from typing import Any, Dict, List, Optional


def _core_dir() -> str:
    """Locate the core package without assuming a checkout layout.

    In the APK the core is merged next to this package; during development it
    lives in Gradle's generated tree. ``find_spec`` handles both.
    """
    spec = importlib.util.find_spec("ai_hints_core")
    if spec and spec.origin:
        return os.path.dirname(os.path.abspath(spec.origin))
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "ai_hints_core")


CORE_DIR = _core_dir()
DEFAULT_CONFIG_PATH = os.path.join(CORE_DIR, "config.json")


def _deep_merge(base: Dict[str, Any], override: Dict[str, Any]) -> Dict[str, Any]:
    out = copy.deepcopy(base)
    for key, value in (override or {}).items():
        if isinstance(value, dict) and isinstance(out.get(key), dict):
            out[key] = _deep_merge(out[key], value)
        else:
            out[key] = copy.deepcopy(value)
    return out


def load_defaults() -> Dict[str, Any]:
    try:
        with open(DEFAULT_CONFIG_PATH, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


class ConfigStore:
    """Add-on config semantics, stored in the app's private data dir.

    The vendored core reads ``AIHINTS_DATA_DIR`` (see ``config_io``), so setting
    that env var here keeps logger/config paths consistent with the add-on.
    """

    def __init__(self, data_dir: str):
        self.data_dir = data_dir
        os.makedirs(data_dir, exist_ok=True)
        os.environ["AIHINTS_DATA_DIR"] = data_dir
        self.path = os.path.join(data_dir, "config.json")
        self._lock = threading.RLock()
        self._defaults = load_defaults()
        self._config = self._read()

    def _read(self) -> Dict[str, Any]:
        try:
            with open(self.path, "r", encoding="utf-8") as handle:
                saved = json.load(handle)
        except Exception:
            saved = {}
        if not isinstance(saved, dict):
            saved = {}
        return _deep_merge(self._defaults, saved)

    @property
    def defaults(self) -> Dict[str, Any]:
        return copy.deepcopy(self._defaults)

    def get(self) -> Dict[str, Any]:
        with self._lock:
            return copy.deepcopy(self._config)

    def replace(self, config: Dict[str, Any]) -> Dict[str, Any]:
        """Store a full config object (already merged/validated by the caller)."""
        with self._lock:
            merged = _deep_merge(self._defaults, config or {})
            self._config = merged
            self._write(merged)
            return copy.deepcopy(merged)

    def update(self, patch: Dict[str, Any]) -> Dict[str, Any]:
        with self._lock:
            self._config = _deep_merge(self._config, patch or {})
            self._write(self._config)
            return copy.deepcopy(self._config)

    def set_value(self, key: str, value: Any) -> Dict[str, Any]:
        return self.update({key: value})

    def reset(self) -> Dict[str, Any]:
        with self._lock:
            self._config = copy.deepcopy(self._defaults)
            self._write(self._config)
            return copy.deepcopy(self._config)

    def _write(self, config: Dict[str, Any]) -> None:
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(config, handle, indent=2, ensure_ascii=False, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, self.path)


class UndoStack:
    """Per-note field snapshots. AnkiDroid exposes no collection undo to us."""

    LIMIT = 40

    def __init__(self):
        self._lock = threading.RLock()
        self._undo: Dict[int, List[Dict[str, Any]]] = {}
        self._redo: Dict[int, List[Dict[str, Any]]] = {}

    def push(self, nid: int, snapshot: Dict[str, Any], label: str = "") -> None:
        with self._lock:
            stack = self._undo.setdefault(int(nid), [])
            stack.append({"snapshot": snapshot, "label": label})
            if len(stack) > self.LIMIT:
                del stack[0]
            self._redo[int(nid)] = []

    def pop(self, nid: int) -> Optional[Dict[str, Any]]:
        with self._lock:
            stack = self._undo.get(int(nid)) or []
            if not stack:
                return None
            return stack[-1]

    def commit_undo(self, nid: int, current: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        with self._lock:
            stack = self._undo.get(int(nid)) or []
            if not stack:
                return None
            entry = stack.pop()
            self._redo.setdefault(int(nid), []).append(
                {"snapshot": current, "label": entry.get("label", "")}
            )
            return entry

    def commit_redo(self, nid: int, current: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        with self._lock:
            stack = self._redo.get(int(nid)) or []
            if not stack:
                return None
            entry = stack.pop()
            self._undo.setdefault(int(nid), []).append(
                {"snapshot": current, "label": entry.get("label", "")}
            )
            return entry

    def forget(self, nid: int) -> None:
        with self._lock:
            self._undo.pop(int(nid), None)
            self._redo.pop(int(nid), None)

    def depth(self, nid: int) -> tuple[int, int]:
        with self._lock:
            return (
                len(self._undo.get(int(nid)) or []),
                len(self._redo.get(int(nid)) or []),
            )

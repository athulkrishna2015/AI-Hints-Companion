"""Lightweight stand-ins for Anki's ``Note``/``Card`` objects.

``CardParser`` only relies on a tiny slice of the Anki object model:

* ``note.keys() / items() / values() / __getitem__ / __setitem__``
* ``note.model()`` returning ``{name, type, flds, tmpls}``
* ``note.fields`` (list of values)
* ``card.ord`` / ``card.id`` / ``card.nid``

The Kotlin side hands us a snapshot as JSON, we mutate the snapshot, and the
engine pushes back only the fields that actually changed.
"""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional


class NoteProxy:
    def __init__(self, payload: Dict[str, Any]):
        self.nid = int(payload["nid"])
        self.mid = int(payload.get("mid") or 0)
        self._model: Dict[str, Any] = payload.get("model") or {}
        self._fields: List[List[str]] = [
            [str(f.get("name", "")), str(f.get("value", ""))]
            for f in (payload.get("fields") or [])
        ]
        self._original = {name: value for name, value in self._fields}
        self.tags: List[str] = [str(t) for t in (payload.get("tags") or [])]
        self._original_tags = list(self.tags)

    # -- mapping protocol -------------------------------------------------
    def keys(self) -> List[str]:
        return [name for name, _ in self._fields]

    def values(self) -> List[str]:
        return [value for _, value in self._fields]

    def items(self) -> List[tuple[str, str]]:
        return [(name, value) for name, value in self._fields]

    def __len__(self) -> int:
        return len(self._fields)

    def __contains__(self, key: object) -> bool:
        return any(name == key for name, _ in self._fields)

    def __getitem__(self, key: str) -> str:
        for name, value in self._fields:
            if name == key:
                return value
        raise KeyError(key)

    def __setitem__(self, key: str, value: Any) -> None:
        text = "" if value is None else str(value)
        for pair in self._fields:
            if pair[0] == key:
                pair[1] = text
                return
        raise KeyError(key)

    def get(self, key: str, default: Any = None) -> Any:
        try:
            return self[key]
        except KeyError:
            return default

    @property
    def fields(self) -> List[str]:
        return self.values()

    def model(self) -> Dict[str, Any]:
        return self._model

    # -- change tracking --------------------------------------------------
    def changed_fields(self) -> Dict[str, str]:
        return {
            name: value
            for name, value in self._fields
            if self._original.get(name) != value
        }

    def changed_tags(self) -> bool:
        return self.tags != self._original_tags

    def snapshot(self) -> Dict[str, Any]:
        return {"fields": dict(self._original), "tags": list(self._original_tags)}

    def current(self) -> Dict[str, Any]:
        return {
            "fields": {name: value for name, value in self._fields},
            "tags": list(self.tags),
        }

    def restore(self, snapshot: Dict[str, Any]) -> None:
        fields = snapshot.get("fields") or {}
        for pair in self._fields:
            if pair[0] in fields:
                pair[1] = fields[pair[0]]
        self.tags = list(snapshot.get("tags") or [])


class CardProxy:
    def __init__(self, nid: int, ord_: int):
        self.nid = int(nid)
        self.id = int(nid)
        self.ord = int(ord_)

    def note(self) -> "NoteProxy":  # pragma: no cover - defensive compatibility
        raise RuntimeError("CardProxy has no live note; use the engine's note handle")


def note_from_json(raw: str) -> Optional[NoteProxy]:
    try:
        payload = json.loads(raw)
    except Exception:
        return None
    if not isinstance(payload, dict) or "nid" not in payload:
        return None
    try:
        return NoteProxy(payload)
    except Exception:
        return None

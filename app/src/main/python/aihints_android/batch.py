"""Batch generation over an Anki search query.

Mirrors the user-visible behaviour of the add-on's batch tab: pick a search,
generate for every card that does not already have hints, report progress,
and stay pausable/cancellable. The add-on's persistent resume queue is not
reproduced here (Android can be killed at any time); progress is reported
live instead.
"""

from __future__ import annotations

import json
import threading
import time
from typing import Any, Dict, List, Optional

from ai_hints_core.logger import logger


class BatchRunner:
    GLOBAL_NID = 0

    def __init__(self, engine):
        self.engine = engine
        self._lock = threading.RLock()
        self._thread: Optional[threading.Thread] = None
        self._pause = threading.Event()
        self._cancel = threading.Event()
        self._state: Dict[str, Any] = {
            "running": False,
            "paused": False,
            "done": 0,
            "total": 0,
            "ok": 0,
            "failed": 0,
            "skipped": 0,
            "current": None,
            "error": None,
            "startedAt": None,
            "finishedAt": None,
            "query": "",
        }

    # ------------------------------------------------------------------ state
    def status(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._state)

    def _emit(self) -> None:
        with self._lock:
            payload = dict(self._state)
        self.engine._emit(self.GLOBAL_NID, {"type": "batch", "state": payload})

    def _update(self, **patch) -> None:
        with self._lock:
            self._state.update(patch)
        self._emit()

    # ----------------------------------------------------------------- control
    def start(
        self,
        query: str,
        limit: int = 50,
        provider: str = "",
        model: str = "",
        skip_existing: bool = True,
    ) -> Dict[str, Any]:
        with self._lock:
            if self._state["running"]:
                return {"ok": False, "error": "already-running"}
            self._pause.clear()
            self._cancel.clear()
            self._state = {
                "running": True,
                "paused": False,
                "done": 0,
                "total": 0,
                "ok": 0,
                "failed": 0,
                "skipped": 0,
                "current": None,
                "error": None,
                "startedAt": time.time(),
                "finishedAt": None,
                "query": query,
            }
        self._emit()
        self._thread = threading.Thread(
            target=self._run,
            args=(query, int(limit), provider, model, bool(skip_existing)),
            name="aihints-batch",
            daemon=True,
        )
        self._thread.start()
        return {"ok": True}

    def pause(self) -> Dict[str, Any]:
        self._pause.set()
        self._update(paused=True)
        return {"ok": True}

    def resume(self) -> Dict[str, Any]:
        self._pause.clear()
        self._update(paused=False)
        return {"ok": True}

    def cancel(self) -> Dict[str, Any]:
        self._cancel.set()
        self._pause.clear()
        self.engine.cancel()
        return {"ok": True}

    # -------------------------------------------------------------------- work
    def _run(
        self,
        query: str,
        limit: int,
        provider: str,
        model: str,
        skip_existing: bool,
    ) -> None:
        config = self.engine.config()
        if config.get("batch_full_scan", False):
            limit = 0
        try:
            raw = self.engine.port.findCards(query or "", int(limit))
            cards: List[Dict[str, Any]] = json.loads(raw or "[]")
        except Exception as exc:
            logger.error(f"AI-Hints: batch search failed: {exc}")
            self._update(running=False, error=str(exc), finishedAt=time.time())
            return

        if limit and len(cards) > limit:
            cards = cards[:limit]
        self._update(total=len(cards))

        for card in cards:
            if self._cancel.is_set():
                break
            while self._pause.is_set() and not self._cancel.is_set():
                time.sleep(0.2)
            if self._cancel.is_set():
                break

            nid = int(card.get("nid") or 0)
            ord_ = int(card.get("ord") or 0)
            if not nid:
                continue
            self._update(current={"nid": nid, "ord": ord_})

            if skip_existing:
                try:
                    state = self.engine.state(nid, ord_, False)
                    data = state.get("hints") if state.get("ok") else None
                    if data and (data.get("hints") or data.get("options") or data.get("_skipped")):
                        with self._lock:
                            self._state["skipped"] += 1
                            self._state["done"] += 1
                        self._emit()
                        continue
                except Exception:
                    pass

            result = self.engine.generate(
                nid,
                ord_,
                override_provider=provider or None,
                override_model=model or None,
                is_pregen=False,
                is_manual=False,
            )
            with self._lock:
                self._state["done"] += 1
                if result.get("ok"):
                    self._state["ok"] += 1
                else:
                    self._state["failed"] += 1
                    self._state["error"] = result.get("error")
            self._emit()

        self._update(
            running=False,
            paused=False,
            current=None,
            finishedAt=time.time(),
            cancelled=self._cancel.is_set(),
        )
        logger.info(
            f"AI-Hints: batch finished — ok={self._state['ok']} "
            f"failed={self._state['failed']} skipped={self._state['skipped']}"
        )

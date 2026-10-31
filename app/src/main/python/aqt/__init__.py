"""Minimal stand-in for the add-on's ``aqt`` dependency.

The vendored core modules import ``aqt`` at module scope (``mobile_sync``) or
lazily inside ``try`` blocks (``logger``, ``config_io``). On Android there is no
Anki/Qt, so ``mw`` is always ``None`` and every code path that actually needs a
collection is routed through ``dev.aihints.companion.anki.AnkiPort`` instead.
"""

mw = None

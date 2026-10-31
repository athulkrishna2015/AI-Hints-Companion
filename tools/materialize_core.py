#!/usr/bin/env python3
"""Materialise the AI-Hints core from the ``vendor/AI-Hints`` submodule.

The app never forks the add-on's AI/parsing code. This script copies the exact
files the add-on ships into a *generated* source tree that Gradle feeds to
Chaquopy and to the asset merger. Nothing is committed: the submodule is the
single source of truth, so updating the add-on is just

    git -C vendor/AI-Hints fetch && git -C vendor/AI-Hints checkout <tag>
    git add vendor/AI-Hints

Usage:
    python3 tools/materialize_core.py                     # build/ layout
    python3 tools/materialize_core.py --check             # verify only
    python3 tools/materialize_core.py --addon PATH        # override submodule
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ADDON = REPO_ROOT / "vendor" / "AI-Hints"
BUILD_DIR = REPO_ROOT / "build" / "generated" / "aihints"

# Files copied verbatim. Keep this list explicit: adding a file is a deliberate
# decision that the Android app depends on it.
FILES = [
    "ai_client.py",
    "card_parser.py",
    "logger.py",
    "config_io.py",
    "mobile_sync.py",
    "model_updater.py",
    "config.json",
    "VERSION",
]

DIRS = [
    "json_repair",
    "latex_fixer",
    # The renderer ships inside the vendored core too: upstream regression tests
    # read addon/web/template.js, and keeping it here lets those tests run.
    "web",
]

ASSET_FILES = {
    "web/template.js": "_ai_hints_template.js",
}

IGNORED_DIRS = {"__pycache__", ".git", ".pytest_cache"}
IGNORED_SUFFIXES = {".pyc", ".pyo"}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 128), b""):
            digest.update(chunk)
    return digest.hexdigest()


def iter_payload_files(addon: Path):
    for name in FILES:
        yield addon / "addon" / name
    for name in DIRS:
        root = addon / "addon" / name
        for path in sorted(root.rglob("*")):
            if not path.is_file():
                continue
            if any(part in IGNORED_DIRS for part in path.relative_to(root).parts):
                continue
            if path.suffix in IGNORED_SUFFIXES:
                continue
            yield path


def git_rev(addon: Path) -> dict:
    def run(*args: str) -> str:
        try:
            out = subprocess.run(
                ["git", "-C", str(addon), *args],
                capture_output=True,
                text=True,
                check=True,
            )
            return out.stdout.strip()
        except Exception:
            return ""

    return {
        "commit": run("rev-parse", "HEAD"),
        "commit_short": run("rev-parse", "--short", "HEAD"),
        "branch": run("rev-parse", "--abbrev-ref", "HEAD"),
        "describe": run("describe", "--tags", "--always"),
    }


def build_plan(addon: Path, dest: Path, assets: Path) -> list[tuple[Path, Path]]:
    plan = []
    for src in iter_payload_files(addon):
        plan.append((src, dest / Path(src).relative_to(addon / "addon")))
    for src_rel, dst_name in ASSET_FILES.items():
        plan.append((addon / "addon" / src_rel, assets / dst_name))
    return plan


def write_generated_files(dest: Path, version: str, rev: dict) -> list[tuple[Path, Path]]:
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "config_ui").mkdir(parents=True, exist_ok=True)

    init = dest / "__init__.py"
    init.write_text(
        '"""Generated AI-Hints core. Do not edit; the source is vendor/AI-Hints."""\n'
        'ADDON_PACKAGE = "ai_hints"\n'
        f'ADDON_VERSION = "{version}"\n'
        f'ADDON_COMMIT = "{rev.get("commit_short", "")}"\n',
        encoding="utf-8",
    )

    config_ui = dest / "config_ui" / "__init__.py"
    config_ui.write_text(
        '"""Shim: the add-on\'s config_ui package imports Qt, which does not exist here."""\n'
        'ADDON_PACKAGE = "ai_hints"\n',
        encoding="utf-8",
    )

    return [(init, init), (config_ui, config_ui)]


def materialize(addon: Path, dest: Path, assets: Path, manifest: Path, check: bool) -> int:
    if not (addon / "addon").is_dir():
        print(
            f"error: {addon}/addon not found.\n"
            "       Run: git submodule update --init --recursive",
            file=sys.stderr,
        )
        return 2

    version = (addon / "addon" / "VERSION").read_text(encoding="utf-8").strip()
    rev = git_rev(addon)
    plan = build_plan(addon, dest, assets)

    missing = [str(src) for src, _ in plan if not src.is_file()]
    if missing:
        print("error: add-on files missing:\n  " + "\n  ".join(missing), file=sys.stderr)
        return 2

    changed, added, same = [], [], []
    entries = []
    for src, dst in plan:
        digest = sha256(src)
        entries.append(
            {
                "source": str(src.relative_to(addon)),
                "target": str(dst),
                "sha256": digest,
            }
        )
        if not dst.exists():
            added.append(dst)
        elif sha256(dst) != digest:
            changed.append(dst)
        else:
            same.append(dst)

    if check:
        drift = added + changed
        if drift:
            print("generated core is stale:")
            for path in drift:
                print(f"  - {path}")
            print("\nrun: python3 tools/materialize_core.py")
            return 1
        print(f"generated core matches add-on v{version} ({rev.get('commit_short', '?')})")
        return 0

    for src, dst in plan:
        dst.parent.mkdir(parents=True, exist_ok=True)
        if not dst.exists() or sha256(dst) != sha256(src):
            shutil.copy2(src, dst)

    for src, dst in write_generated_files(dest, version, rev):
        entries.append(
            {
                "source": f"<generated:{src.name}>",
                "target": str(dst),
                "sha256": sha256(dst),
            }
        )

    # Settings screen schema, generated from the same submodule checkout.
    sys.path.insert(0, str(REPO_ROOT / "tools"))
    import gen_schema  # noqa: E402  (local tool module)

    gen_schema.build(addon, assets / "config_schema.json")

    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(
        json.dumps(
            {
                "addon_version": version,
                "addon_git": rev,
                "files": sorted(entries, key=lambda e: e["target"]),
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    print(f"materialised add-on v{version} ({rev.get('commit_short', '?')})")
    print(f"  {len(added)} added, {len(changed)} updated, {len(same)} unchanged")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--addon", type=Path, default=DEFAULT_ADDON)
    parser.add_argument("--dest", type=Path, default=BUILD_DIR / "python" / "ai_hints_core")
    parser.add_argument("--assets", type=Path, default=BUILD_DIR / "assets")
    parser.add_argument("--manifest", type=Path, default=BUILD_DIR / "MANIFEST.json")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    addon = args.addon.expanduser().resolve()
    dest = args.dest.expanduser().resolve()
    assets = args.assets.expanduser().resolve()
    manifest = args.manifest.expanduser().resolve()

    return materialize(addon, dest, assets, manifest, args.check)


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Run the add-on's own unit tests against the generated Android core.

This is the drift alarm: if an upstream change breaks behaviour the Android app
relies on, this fails before a build ships. The tested tree is exactly the one
Gradle materialises from ``vendor/AI-Hints`` (see ``tools/materialize_core.py``).

The add-on tests import ``addon.*`` and hardcode their repo root, so we build a
throwaway root that exposes the generated copy under that name.

Usage:
    python3 tools/verify_core.py
    python3 tools/verify_core.py -v
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CORE = REPO_ROOT / "build" / "generated" / "aihints" / "python" / "ai_hints_core"
PY_ROOT = REPO_ROOT / "app" / "src" / "main" / "python"

# Tests that only touch the portable core (ai_client / card_parser / logger /
# config_io / mobile_sync / model_updater / vendored helpers). Anything that
# imports aqt widgets or reviewer_hooks is intentionally excluded.
UNITTEST_SUITES = [
    "test_card_parser",
    "test_cloze_scenarios",
    "test_generation_toggles",
    "test_key_rotation",
    "test_latex_fixer",
    "test_logger_rebind",
    "test_malayalam_cloze",
    "test_migration",
    "test_mobile_script_sync",
    "test_model_filtering",
    "test_ollama_unicode",
    "test_provider_fallbacks",
    "test_race_condition_fix",
    "test_reasoning_parse",
    "test_template_compatibility",
]

# Real-thread timing tests: these can lose a race on a loaded CI box. One retry
# is enough to distinguish flakiness from an actual behavioural change.
FLAKY_SUITES = [
    "test_linger_on_timeout",
]

# Upstream keeps these as plain scripts (``python tests/x.py``) rather than
# unittest suites; they still assert and exit non-zero on failure.
SCRIPT_SUITES = [
    "test_generation_cycle",
    "test_json_repair_integration",
    "test_raw_local",
    "test_sanitization_regex",
]

TESTS = UNITTEST_SUITES + FLAKY_SUITES + SCRIPT_SUITES


def default_addon_dir() -> Path:
    env = os.environ.get("AIHINTS_ADDON_DIR")
    if env:
        return Path(env).expanduser().resolve()
    return (REPO_ROOT / "vendor" / "AI-Hints").resolve()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--addon", type=Path, default=None)
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    addon = (args.addon or default_addon_dir()).expanduser().resolve()
    tests_dir = addon / "tests"
    if not tests_dir.is_dir():
        print(f"error: {tests_dir} not found", file=sys.stderr)
        return 2
    if not CORE.is_dir():
        print(
            "error: generated core missing; run tools/materialize_core.py "
            "or ./gradlew syncAddonCore",
            file=sys.stderr,
        )
        return 2

    with tempfile.TemporaryDirectory(prefix="aihints-verify-") as tmp:
        root = Path(tmp)
        (root / "addon").symlink_to(CORE, target_is_directory=True)
        (root / "aqt").symlink_to(PY_ROOT / "aqt", target_is_directory=True)
        (root / "tests").mkdir()
        for name in TESTS:
            src = tests_dir / f"{name}.py"
            if not src.is_file():
                print(f"  ! missing upstream test {name}.py")
                continue
            shutil.copy2(src, root / "tests" / src.name)
        helper = tests_dir / "blacklist_helpers.py"
        if helper.is_file():
            shutil.copy2(helper, root / "tests" / helper.name)

        env = dict(os.environ)
        env["AIHINTS_DATA_DIR"] = str(root / "state")
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        env["PYTHONPATH"] = str(root)

        failures = []
        ran = 0
        for name in TESTS:
            path = root / "tests" / f"{name}.py"
            if not path.is_file():
                continue
            ran += 1
            if name in SCRIPT_SUITES:
                cmd = [sys.executable, f"tests/{name}.py"]
            else:
                cmd = [sys.executable, "-m", "unittest", f"tests.{name}"]
            attempts = 2 if name in FLAKY_SUITES else 1
            for attempt in range(attempts):
                proc = subprocess.run(
                    cmd,
                    cwd=root,
                    env=env,
                    capture_output=True,
                    text=True,
                )
                if proc.returncode == 0:
                    break
            status = "ok" if proc.returncode == 0 else "FAIL"
            if attempt:
                status = "ok*" if proc.returncode == 0 else status
            print(f"  [{status:>4}] {name}")
            if proc.returncode != 0:
                failures.append((name, proc))
                if args.verbose:
                    print(proc.stdout)
                    print(proc.stderr)

        print()
        if failures:
            print(f"{len(failures)} of {ran} core suites failed")
            for name, proc in failures:
                tail = (proc.stderr or proc.stdout).strip().splitlines()
                print(f"\n--- {name} ---")
                print("\n".join(tail[-25:]))
            return 1
        print(f"all {ran} core suites passed against the generated copy")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())

set shell := ["bash", "-uc"]

# Chaquopy needs a CPython 3.8-3.13 to build against; uv supplies one.
export CHAQUOPY_BUILD_PYTHON := `uv python find 3.11 2>/dev/null || echo "${CHAQUOPY_BUILD_PYTHON:-}"`
export JAVA_HOME := `mise where java 2>/dev/null || echo "${JAVA_HOME:-}"`

default:
    @just --list

# Initialise or update the add-on submodule (includes the nested latex_fixer).
submodule:
    git submodule update --init --recursive

# Materialise vendor/AI-Hints into build/generated/aihints.
# Every Gradle build runs this automatically; use it for IDE indexing.
sync:
    python3 tools/materialize_core.py

# Fail if the generated tree is stale (useful in CI).
sync-check:
    python3 tools/materialize_core.py --check

# Run the add-on's own 20 core test suites against the generated copy.
verify:
    python3 tools/verify_core.py

# Regenerate the settings-screen schema only.
schema:
    python3 tools/gen_schema.py

build:
    ./gradlew :app:assembleDebug

# Build a release APK (unsigned).
release:
    ./gradlew :app:assembleRelease

install:
    ./gradlew :app:installDebug

run: install
    adb shell am start -n dev.aihints.companion/.MainActivity

# Show where the debug APK landed.
apk:
    @ls -lh app/build/outputs/apk/debug/app-debug.apk

clean:
    ./gradlew clean

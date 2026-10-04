#!/usr/bin/env bash
# Starts a packaged VocaBoost launcher with --smoke-test and fails unless it exits with 0 in time. With
# that argument the app opens a new database in a temporary folder (never the user's data), waits until
# its main window is shown, and quits with 0, or prints why it failed and quits with 1. It catches a
# launcher or trimmed runtime that cannot start the app, e.g. a missing Java module. The home folder is
# pointed at a throw-away folder too, which has to stay empty.
#
# Usage: scripts/smoke-test.sh <launcher> [seconds]   (default: 180 seconds)
# Linux without a display: xvfb-run -a scripts/smoke-test.sh target/dist/VocaBoost/bin/VocaBoost
set -euo pipefail

launcher=${1:?usage: scripts/smoke-test.sh <launcher> [seconds]}
seconds=${2:-180}
[ -x "$launcher" ] || { echo "error: $launcher is not an executable launcher" >&2; exit 1; }

home=$(mktemp -d)
pid=
cleanup() {
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill -9 "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    rm -rf "$home"
}
trap cleanup EXIT

# The JVM reads JAVA_TOOL_OPTIONS whatever launches it. Software rendering works on virtual displays.
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$home -Dprism.order=sw" "$launcher" --smoke-test &
pid=$!

waited=0
while kill -0 "$pid" 2>/dev/null; do
    if [ "$waited" -ge "$seconds" ]; then
        echo "Smoke test failed: $launcher --smoke-test was still running after $seconds s." >&2
        exit 1
    fi
    sleep 1
    waited=$((waited + 1))
done
status=0
wait "$pid" || status=$?
pid=
if [ "$status" -ne 0 ]; then
    echo "Smoke test failed: $launcher --smoke-test exited with $status." >&2
    exit 1
fi
if [ -n "$(ls -A "$home")" ]; then
    echo "Smoke test failed: the app wrote to the home folder: $(ls -A "$home" | tr '\n' ' ')" >&2
    exit 1
fi
echo "Smoke test passed: $launcher showed its main window on a new database and exited with 0."

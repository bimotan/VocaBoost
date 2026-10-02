#!/usr/bin/env bash
# Starts a packaged VocaBoost launcher on a fresh, throw-away data folder and fails if the app quits
# within the wait, or logs a SEVERE error (a failed start shows an error dialog but keeps running).
# It catches a launcher or trimmed runtime that cannot start the app, e.g. a missing Java module.
#
# Usage: scripts/smoke-test.sh <launcher> [seconds]   (default: 20 seconds)
# Linux without a display: xvfb-run -a scripts/smoke-test.sh target/dist/VocaBoost/bin/VocaBoost
set -euo pipefail

launcher=${1:?usage: scripts/smoke-test.sh <launcher> [seconds]}
seconds=${2:-20}
[ -x "$launcher" ] || { echo "error: $launcher is not an executable launcher" >&2; exit 1; }

home=$(mktemp -d)
pid=
cleanup() {
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    rm -rf "$home"
}
trap cleanup EXIT

# The JVM reads JAVA_TOOL_OPTIONS whatever launches it, so the app keeps its data in $home.
# Software rendering works on virtual displays.
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$home -Dprism.order=sw" "$launcher" >"$home/console.txt" 2>&1 &
pid=$!

logs=$home/.vocab-trainer/logs
failure=
for _ in $(seq "$seconds"); do
    sleep 1
    if ! kill -0 "$pid" 2>/dev/null; then
        failure="the app quit"
        break
    fi
    if grep -qs SEVERE "$logs"/*.log; then
        failure="the app logged an error"
        break
    fi
done
if [ -z "$failure" ]; then
    if ! grep -qs "VocaBoost starting" "$logs"/*.log; then
        failure="the app logged no startup line in $logs"
    elif [ ! -f "$home/.vocab-trainer/vocab.db" ]; then
        failure="the app created no database"
    fi
fi

if [ -n "$failure" ]; then
    echo "Smoke test failed: $failure." >&2
    echo "--- console output" >&2
    cat "$home/console.txt" >&2 || true
    echo "--- log" >&2
    cat "$logs"/*.log >&2 2>/dev/null || true
    exit 1
fi
echo "Smoke test passed: $launcher ran for $seconds s, created its database and logged no errors."

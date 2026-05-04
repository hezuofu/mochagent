#!/bin/bash
# Graceful shutdown — MochaAgent
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$APP_HOME/mocha.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "MochaAgent is not running (no PID file)"
    exit 0
fi

PID=$(cat "$PID_FILE")

if ! kill -0 "$PID" 2>/dev/null; then
    echo "MochaAgent is not running (stale PID: $PID)"
    rm -f "$PID_FILE"
    exit 0
fi

echo "Stopping MochaAgent (PID: $PID)..."

# Graceful shutdown (SIGTERM)
kill "$PID"

# Wait up to 30s for graceful shutdown
for i in $(seq 1 30); do
    if ! kill -0 "$PID" 2>/dev/null; then
        echo "✓ Stopped gracefully"
        rm -f "$PID_FILE"
        exit 0
    fi
    sleep 1
done

# Force kill if still running
echo "Force killing..."
kill -9 "$PID" 2>/dev/null || true
rm -f "$PID_FILE"
echo "✓ Stopped (forced)"

#!/bin/bash
# Production startup — MochaAgent
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$APP_HOME/mocha.pid"
LOG_DIR="$APP_HOME/logs"
CONF_FILE="${MOCHA_CONF:-$APP_HOME/.mocha/settings.json}"

# ── JVM tuning ──
JAVA_OPTS="${JAVA_OPTS:--Xmx1g -Xms256m}"
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
JAVA_OPTS="$JAVA_OPTS -XX:+ExitOnOutOfMemoryError"
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS -Dlog.dir=$LOG_DIR"

# ── Check if already running ──
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "MochaAgent is already running (PID: $PID)"
        exit 1
    fi
    rm -f "$PID_FILE"
fi

# ── Prepare ──
mkdir -p "$LOG_DIR"

# ── Build if needed ──
JAR="$APP_HOME/target/mochaagents-1.0.0.jar"
if [ ! -f "$JAR" ]; then
    echo "Building..."
    cd "$APP_HOME" && ./mvnw package -DskipTests -q
fi

# ── Start ──
echo "Starting MochaAgent..."
nohup java $JAVA_OPTS -jar "$JAR" "$@" > "$LOG_DIR/stdout.log" 2>&1 &
PID=$!
echo $PID > "$PID_FILE"
echo "MochaAgent started (PID: $PID)"

# ── Health check ──
sleep 3
if kill -0 "$PID" 2>/dev/null; then
    echo "✓ Healthy"
else
    echo "✗ Failed to start — check $LOG_DIR/stdout.log"
    rm -f "$PID_FILE"
    exit 1
fi

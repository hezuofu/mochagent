#!/bin/bash
# MochaAgent startup script
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Build if needed
if [ ! -f "target/mochaagents-1.0.0.jar" ]; then
    echo "Building MochaAgent..."
    ./mvnw clean package -DskipTests -q
fi

# Default JVM options
JAVA_OPTS="${JAVA_OPTS:--Xmx512m -Xms128m}"

# Run
exec java $JAVA_OPTS -cp "target/mochaagents-1.0.0.jar:target/dependency/*" \
    io.sketch.mochaagents.cli.Main "$@"

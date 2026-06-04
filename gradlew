#!/usr/bin/env sh
# Gradle wrapper startup script for Unix
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar" "$@"

#!/bin/sh
set -eu
SERVER_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi
exec "$JAVA" -jar "$SERVER_HOME/benchmark-server.jar" "$SERVER_HOME/server.properties"

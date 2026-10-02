#!/bin/sh
# Runs the benchmark tool with the given arguments, for example:
#   ./bench.sh run quick
#   ./bench.sh report results/before.json results/after.json
# Run it from prototype/benchmark, after building the jar with:
#   mvn -f .. install -DskipTests

JAR=target/benchmarks.jar

if [ ! -f "$JAR" ]; then
    echo "Cannot find $JAR." >&2
    echo "Run this script from prototype/benchmark, after building the jar with: mvn -f .. install -DskipTests" >&2
    exit 1
fi

if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA=java
fi

# The JVM option only hides some warnings of JMH on the new JDKs.
exec "$JAVA" --sun-misc-unsafe-memory-access=allow -jar "$JAR" "$@"

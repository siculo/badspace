# Runs the benchmark tool with the given arguments, for example:
#   .\bench.ps1 run quick
#   .\bench.ps1 report results\before.json results\after.json
# Run it from prototype/benchmark, after building the jar with:
#   mvn -f .. install -DskipTests

$jar = "target/benchmarks.jar"

if (-not (Test-Path $jar -PathType Leaf)) {
    [Console]::Error.WriteLine("Cannot find $jar.")
    [Console]::Error.WriteLine("Run this script from prototype/benchmark, after building the jar with: mvn -f .. install -DskipTests")
    exit 1
}

if ($env:JAVA_HOME) {
    $java = Join-Path $env:JAVA_HOME "bin/java"
} else {
    $java = "java"
}

# The JVM option only hides some warnings of JMH on the new JDKs.
& $java --sun-misc-unsafe-memory-access=allow -jar $jar @args
exit $LASTEXITCODE

#!/bin/bash
# Measures the indices to compare and writes one report with all of them:
#   1. the linear scan, as the reference;
#   2. the uniform grid with cells of side 25, 50, 100, 200 and 400;
#   3. the grid of quadtrees with cells of side 64, 128 and 256.
#
# Usage, from any directory:
#   ./compare-indices.sh [quick|full]
# The profile is quick by default. The quick profile takes more than one hour,
# the full profile many hours. To keep it running after logout:
#   nohup ./compare-indices.sh > compare-indices.log 2>&1 &

set -euo pipefail

PROFILE="${1:-quick}"
if [ "$PROFILE" != "quick" ] && [ "$PROFILE" != "full" ]; then
    echo "Unknown profile: $PROFILE (use quick or full)" >&2
    exit 1
fi

UNIFORM_GRIDS="UNIFORM_GRID_25,UNIFORM_GRID_50,UNIFORM_GRID_100,UNIFORM_GRID_200,UNIFORM_GRID_400"
GRID_QUADTREES="GRID_QUADTREE_64,GRID_QUADTREE_128,GRID_QUADTREE_256"

# bench.sh and the output paths are relative to prototype/benchmark.
cd "$(dirname "$0")"

DATE=$(date +%Y-%m-%d-%H%M)
REFERENCE="results/reference-linear-$PROFILE.json"
GRID_RESULTS="results/$DATE-uniform-grid-$PROFILE.json"
QUADTREE_RESULTS="results/$DATE-grid-quadtree-$PROFILE.json"
REPORT="results/$DATE-index-comparison-$PROFILE.html"

echo "== Building the jar"
mvn -q -f .. install -DskipTests

echo "== $(date +%H:%M) Linear scan -> $REFERENCE"
./bench.sh run "$PROFILE" --index LINEAR_SCAN --output "$REFERENCE"

echo "== $(date +%H:%M) Uniform grid -> $GRID_RESULTS"
./bench.sh run "$PROFILE" --index "$UNIFORM_GRIDS" --output "$GRID_RESULTS"

echo "== $(date +%H:%M) Grid of quadtrees -> $QUADTREE_RESULTS"
./bench.sh run "$PROFILE" --index "$GRID_QUADTREES" --output "$QUADTREE_RESULTS"

echo "== $(date +%H:%M) Report -> $REPORT"
./bench.sh report --output "$REPORT" "$REFERENCE" "$GRID_RESULTS" "$QUADTREE_RESULTS"

echo "== Done. Open $REPORT in a browser."

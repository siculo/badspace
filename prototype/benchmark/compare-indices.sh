#!/bin/bash
# Measures the indices to compare and the linear scan as the reference, and
# writes one report with all of them.
#
# Usage, from any directory:
#   ./compare-indices.sh [quick|full] [--index NAME1,NAME2]
#
# The profile is quick by default. Without --index, the script measures:
#   - the uniform grid with cells of side 25, 50, 100, 200 and 400;
#   - the grid of quadtrees with cells of side 64, 128 and 256.
# With --index, it measures only the given indices, with the names of
# bench.sh, for example:
#   ./compare-indices.sh full --index UNIFORM_GRID_100,GRID_QUADTREE_128
# The linear scan is always measured, so it is not needed in the list.
#
# The quick profile takes about 8 minutes for each index, the full profile
# about 2 hours. To keep it running after logout:
#   nohup ./compare-indices.sh > compare-indices.log 2>&1 &

set -euo pipefail

usage() {
    echo "Usage: $0 [quick|full] [--index NAME1,NAME2]" >&2
    exit 1
}

PROFILE=quick
INDICES=""
while [ $# -gt 0 ]; do
    case "$1" in
        quick|full) PROFILE="$1" ;;
        --index)
            [ $# -ge 2 ] || usage
            INDICES="$2"
            shift
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage
            ;;
    esac
    shift
done

# bench.sh and the output paths are relative to prototype/benchmark.
cd "$(dirname "$0")"

DATE=$(date +%Y-%m-%d-%H%M)
REFERENCE="results/reference-linear-$PROFILE.json"
REPORT="results/$DATE-index-comparison-$PROFILE.html"

# Each run is a pair of a results file and the indices it measures.
RESULTS=()
RUN_INDICES=()
if [ -z "$INDICES" ]; then
    RESULTS+=("results/$DATE-uniform-grid-$PROFILE.json")
    RUN_INDICES+=("UNIFORM_GRID_25,UNIFORM_GRID_50,UNIFORM_GRID_100,UNIFORM_GRID_200,UNIFORM_GRID_400")
    RESULTS+=("results/$DATE-grid-quadtree-$PROFILE.json")
    RUN_INDICES+=("GRID_QUADTREE_64,GRID_QUADTREE_128,GRID_QUADTREE_256")
else
    # The linear scan has its own run, so remove it from the list.
    INDICES=$(echo "$INDICES" | tr ',' '\n' | grep -vx LINEAR_SCAN | paste -sd, -) || true
    if [ -n "$INDICES" ]; then
        RESULTS+=("results/$DATE-$(echo "$INDICES" | tr ',' '+')-$PROFILE.json")
        RUN_INDICES+=("$INDICES")
    fi
fi

echo "== Building the jar"
mvn -q -f .. install -DskipTests

# The linear scan runs last, so a wrong index name stops the script at once.
for i in "${!RESULTS[@]}"; do
    echo "== $(date +%H:%M) ${RUN_INDICES[$i]} -> ${RESULTS[$i]}"
    ./bench.sh run "$PROFILE" --index "${RUN_INDICES[$i]}" --output "${RESULTS[$i]}"
done

echo "== $(date +%H:%M) LINEAR_SCAN -> $REFERENCE"
./bench.sh run "$PROFILE" --index LINEAR_SCAN --output "$REFERENCE"

echo "== $(date +%H:%M) Report -> $REPORT"
./bench.sh report --output "$REPORT" "$REFERENCE" "${RESULTS[@]}"

echo "== Done. Open $REPORT in a browser."

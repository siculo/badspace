# Benchmarks of the partition nodes

*[Italiano](README-it.md)*

This module measures the speed and the memory of the spatial indices of a
partition. It uses [JMH](https://github.com/openjdk/jmh) and works on the
public contract of the node (`PartitionNode2`), so it also measures the cost
of the storage, not only the cost of the index. Only 2D for now.

The linear scan is the reference: every new index is compared with it.

## Quick start

From `prototype/benchmark`:

```
mvn -f .. install -DskipTests
./bench.sh run quick
```

Then open in a browser the report written next to the results, for example
`results/2026-10-01-1430-quick.html`.

On Windows, use `.\bench.ps1` instead of `./bench.sh`.

The first command builds `target/benchmarks.jar`. Build it again after each
change to the code, or the benchmarks run the old code.

The scripts `bench.sh` (Linux and macOS) and `bench.ps1` (Windows) run
`java -jar target/benchmarks.jar` with the given arguments, so all the
commands in this file work with both. Run them from `prototype/benchmark`.
They use the `java` of `JAVA_HOME` when it is set, otherwise the one on the
`PATH`. They also add the JVM option `--sun-misc-unsafe-memory-access=allow`,
which only hides some warnings of JMH on the new JDKs.

## Running the benchmarks

```
./bench.sh run [quick|full] [--index NAME1,NAME2] [--include REGEX] [--param NAME=V1,V2] [--percentiles] [--output FILE]
./bench.sh run --plan FILE
```

| Option | Meaning |
|---|---|
| `quick` | Quick profile (default): about 15 minutes, for a check during development. |
| `full` | Full profile: about 3-4 hours, for the measures to keep. |
| `--index NAME1,NAME2` | The indices to measure, by name (see [Parameters](#parameters)), for example `--index LINEAR_SCAN` or `--index UNIFORM_GRID_50,UNIFORM_GRID_200`. Default: `LINEAR_SCAN,UNIFORM_GRID_100`. It is the same as `--param index=...`. |
| `--include REGEX` | Runs only the benchmarks whose full name contains a match, for example `findNearest`, `insert\|remove` or `Footprint`. |
| `--param NAME=V1,V2` | Changes the values of a parameter, for example `--param size=1000,1000000`. It can be repeated. |
| `--percentiles` | Also measures the latency percentiles (p50, p90, p99). It doubles the time of the run. |
| `--output FILE` | Where to write the results. Default: `results/<date>-<profile>.json`. |
| `--plan FILE` | Does the runs of a plan file, without other options (see [Plans](#plans)). |

At the end of the run, the report of the results is written too, in the same
directory and with the same name as the JSON file, but with the `.html`
extension.

The output path is relative to the working directory, so run the commands
from `prototype/benchmark`.

### Examples

```
# Only the uniform grid, with two cell sizes
./bench.sh run quick --index UNIFORM_GRID_50,UNIFORM_GRID_200

# The grid of quadtrees against the uniform grid
./bench.sh run quick --index UNIFORM_GRID_100,GRID_QUADTREE_128

# Only the k-nearest queries, on small and large partitions
./bench.sh run quick --include findNearest --param size=1000,1000000

# Only the writes, with a single distribution
./bench.sh run quick --include "insert|remove|update" --param distribution=UNIFORM

# Only the memory per entity
./bench.sh run quick --include Footprint

# Measures to keep, with the percentiles
./bench.sh run full --percentiles --output results/reference-linear-full.json
```

### Plans

To do the same measures again without writing all the options, put the runs
in a plan file and run it:

```
./bench.sh run --plan plans/index-comparison-quick.json
```

`--plan` takes no other options. The runs of a plan are in groups: each
group has a profile, its runs and, if it has a `report` name, a report with
the results of all its runs. For example:

```json
{
  "groups": [
    {
      "profile": "quick",
      "report": "index-comparison",
      "runs": [
        { "name": "uniform-grid", "index": ["UNIFORM_GRID_50", "UNIFORM_GRID_100"] },
        // Comments like in Java are allowed.
        { "name": "coincident", "index": "GRID_QUADTREE_256",
          "params": { "distribution": "COINCIDENT", "size": [1000, 100000] },
          "include": "update|insert", "percentiles": true },
        { "name": "linear", "index": "LINEAR_SCAN", "output": "results/reference-linear-quick.json" }
      ]
    },
    { "profile": "full", "runs": [ { "name": "uniform-grid", "index": "UNIFORM_GRID_100" } ] }
  ]
}
```

| Field | Meaning |
|---|---|
| `profile` | `quick` (default) or `full`, for all the runs of the group. |
| `report` | Optional name of the report of the group: `results/<date>-<report>-<profile>.html`. |
| `name` | Name of the run, required: letters, digits and `. _ + -`. |
| `index`, `include`, `params`, `percentiles`, `output` | The same as the options of `run`. A single value can be written without the array, and the numbers with or without quotes. Default output: `results/<date>-<name>-<profile>.json`. |

The date is the start of the plan, the same for all its files. Each run also
writes its own report, as with the options.

Before the first run, the tool checks all the plan: the fields, the names and
values of the parameters, the patterns, and that no two files have the same
path. So an error stops the plan at once, not after hours.

The plans to keep go in `plans/`. `index-comparison-quick.json` and
`index-comparison-full.json` measure the uniform grid (cells 25 to 400), the
grid of quadtrees (cells 64 to 256) and the linear scan, which is the last
run, in one report. The quick one takes about 70 minutes. To keep a plan running
after logout:

```
nohup ./bench.sh run --plan plans/index-comparison-full.json > plan.log 2>&1 &
```

## What is measured

| Benchmark | What it measures | Unit |
|---|---|---|
| `BuildBenchmark.build` | Fill an empty partition with all the entities in a single call. | ms |
| `PartitionBenchmark.insert` | Insert a batch of new entities. | µs per call |
| `PartitionBenchmark.remove` | Remove a batch of entities. | µs per call |
| `PartitionBenchmark.update` | Move a batch of entities. | µs per call |
| `PartitionBenchmark.get` | Read a batch of entities by ID. | µs per call |
| `PartitionBenchmark.findInRegion` | Range query. | µs per call |
| `PartitionBenchmark.findNearest` | k-nearest query. | µs per call |
| `Footprint.bytesPerEntity` | Memory used by the partition, storage and index together. | bytes per entity |

Lower is always better.

## Parameters

| Parameter | Used by | Quick profile | Full profile |
|---|---|---|---|
| `index` | all | `LINEAR_SCAN`, `UNIFORM_GRID_100` | the same |
| `distribution` | all | `UNIFORM`, `CLUSTERS`, `HOTSPOT`, `CORRIDORS`, `COINCIDENT`, `FAR_CLUSTER`, `ORIGIN_CLUSTER` | the same |
| `size` | all | 1000, 100000 | 1000, 10000, 100000, 1000000 |
| `batchSize` | insert, remove, update, get | 1, 100 | 1, 10, 100, 1000 |
| `movement` | update | `LOCAL`, `TELEPORT` | the same |
| `step` | update (`LOCAL`) | 10 | the same |
| `selectivity` | findInRegion | 0.001, 0.01 | 0.0001, 0.001, 0.01, 0.1 |
| `shape` | findInRegion | `BOX` | `BOX`, `CIRCLE` |
| `queryCenter` | findInRegion, findNearest | `UNIFORM`, `DATA` | the same |
| `k` | findNearest | 1, 10 | 1, 10, 100 |

An index name gives the index and its parameters:

- `LINEAR_SCAN` for the linear scan;
- `UNIFORM_GRID_<cell size>` for a uniform grid, for example
  `--index UNIFORM_GRID_50,UNIFORM_GRID_200` to compare two cell sizes;
- `GRID_QUADTREE_<cell size>` or `GRID_QUADTREE_<cell size>_<leaf capacity>`
  for a grid of quadtrees, for example `GRID_QUADTREE_128` (leaf capacity
  16, the default) or `GRID_QUADTREE_128_32`. The cell size must be a power
  of 2.

The world is a square with side 10000, from 0 to 10000 on each axis; the
distributions with one cluster move it. The distributions are:

- `UNIFORM`: every point has the same probability.
- `CLUSTERS`: 16 dense groups with a normal distribution.
- `HOTSPOT`: 90% of the entities in a square that covers 5% of the world.
- `CORRIDORS`: 8 thin horizontal and vertical strips.
- `COINCIDENT`: 1000 positions, each shared by many entities.
- `FAR_CLUSTER`: one dense group with a normal distribution, in the center of
  a world far from the origin (from 1e8 - 5000 to 1e8 + 5000).
- `ORIGIN_CLUSTER`: one dense group with a normal distribution on the origin,
  in the center of a world from -5000 to 5000. The origin is a border of the
  cells for any cell size, so the group is split among the cells around it.
- `EDGE_CLUSTER`: like `FAR_CLUSTER`, but at 200 astronomical units (about
  3e13), the edge of the solar system scenario with 1 unit = 1 meter. There
  the gap between two doubles is about 4 mm. Only the indices with large
  limits accept it (cells of at least 32768), so it is not in the default
  values: ask for it with `--param distribution=EDGE_CLUSTER`.

The other parameters work as follows:

- `movement`: `LOCAL` moves an entity by a short step, like one tick of
  movement. `TELEPORT` moves it to a new position taken from the same
  distribution.
- `step`: the length of a `LOCAL` move, `d = v · DT` (speed by tick
  length). With the side `L` of a leaf or cell, `r = d / L` gives how often
  an entity changes leaf, so changing the step shows how the cost of the
  writes grows with the speed. In the solar system scenario (1 unit =
  1 meter, at most 1000 km/h, 30 ticks per second) the largest step is
  about 9.26, so the default of 10 is the fastest entity; the plan
  `plans/update-step.json` uses the steps of 10, 30, 100, 300 and
  1000 km/h. The limits of the world can cut a move.
  `TELEPORT` does not use the step: with more than one step, add
  `--param movement=LOCAL`, or the same `TELEPORT` measure is repeated for
  each step.
- `queryCenter`: `UNIFORM` puts the queries anywhere in the world, also where
  there are no entities. `DATA` puts them on the position of an entity, so
  the queries go where the entities are.
- `selectivity`: the share of the entities that a range query finds. Each
  region is sized on the data to contain that number of entities, so the
  results compare the cost of the search, not the size of the result.

All the data come from a fixed seed: two runs with the same parameters use
the same entities and the same queries.

## The report

```
./bench.sh report [--output FILE] RESULTS.json...
```

The report is a single HTML file and needs no internet connection. The
default output is next to the last results file, with the same name and the
`.html` extension. It shows one section for each
operation. Each section has:

- a log-log chart for each distribution, with the number of entities on the
  x axis and the time on the y axis, and one line for each series;
- a table with the values and, for each series, the ratio against the
  baseline: green when it is at least 10% better, red when it is at least
  10% worse.

A series is a pair of result file and index, for example
`reference-linear-quick · LINEAR_SCAN`.

The controls at the top choose:

- the baseline;
- the value of each parameter that is not on the axes;
- the metric (mean or percentiles, if the run used `--percentiles`);
- the time per entity, instead of the time per call, for the batch
  benchmarks.

To compare two runs, for example before and after a change, pass both files:

```
./bench.sh report results/before.json results/after.json
```

The result files can also be opened with the online
[JMH Visualizer](https://jmh.morethan.io/). It shows the memory per entity as
one more benchmark, with mode `footprint`.

## Adding a new index

1. Add a record to `IndexConfig` and create the index in
   `LocalPartitionNode2.createPartition`.
2. Give the index a name in `IndexNames.parse`, and add the name to
   `IndexNames.DEFAULT` and to the `@Param` of `WorkloadState.index` if the
   runs must measure it by default.
3. Build the jar again and run the benchmarks; open the report with the
   linear scan as baseline.
   To measure only the new index, use `--index NEW_INDEX` and pass the
   reference results to the report together with the new ones:

   ```
   ./bench.sh report --output results/comparison.html results/reference-linear-quick.json results/<file>.json
   ```

## Reliable measures

- Build the jar again after each change and after each `git pull`. If a
  source file is newer than the jar, the tool prints a warning at the start,
  because the run would measure old code. Each result has the commit of the
  code (`"commit"`), with `-dirty` at the end if there were changes that
  were not committed.
- Results are comparable only when they come from the same machine. Use the
  ratios, not the absolute values: Java timings are not the timings of the
  final implementation.
- For the full profile, close the other programs, connect the power and use
  the "best performance" power mode of Windows.
- The quick profile has few iterations, so its error is large: it shows big
  trends, not small differences. The error of each value is in the JSON
  (`scoreError`) and in the JMH output.
- Insert and remove with a batch of 1 also include a small fixed cost of JMH,
  the same for all the indices, so their ratios look closer to 1.
- The memory per entity comes from the heap size after a garbage collection.
  It is approximate, but good enough to compare the indices.

## How the benchmarks work

- **The partition does not change during a measure.** Insert and remove are
  undone after each call, outside the measured time. Updates move a batch
  and then move it back.
- **The inputs are ready before the measure.** Batches, regions and query
  points are prepared at the start, in a ring that the calls go through.
- **Each configuration runs in a new JVM** (a JMH fork), so the runs do not
  affect each other.

## Results in the repository

The `results/` directory is ignored by git, because the measures depend on
the machine. To keep a measure, add the JSON file and its HTML report with
`git add -f`.

The reference measures of an index are named
`reference-<index>-<profile>.json`. The repository has
`reference-linear-quick.json`, the linear scan with the quick profile. Like
all the measures, it is valid only for the machine where it was taken: on
another machine, take it again before comparing it with a new index.

The other measures to keep are named
`<date>-<index or change>-<profile>.json`.

## Unit tests

The unit tests of this module check the generators of the data, the sizing
of the regions, and that the benchmarks leave the partition as they found
it. They run with the other tests of the project:

```
mvn -f .. test
```

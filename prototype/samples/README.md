# Samples

*[Italiano](README-it.md)*

Small clients that show how to use BADSPACE as an embedded library, with
local nodes. Each sample is a module with one program, a class with `main`,
that answers one question about the API. The code is linear and keeps the
API calls in sight: each sample repeats its short setup and uses a few
entities with explicit coordinates.

Each sample builds and runs on its own. The order below is a suggested
reading order: the prerequisites of a sample are the concepts it uses, not
the other samples.

## Running a sample

From `prototype`:

```
mvn install -DskipTests
mvn -pl samples/<name> exec:java
```

The persistence samples take an optional save directory; without it they use
a new temporary directory, and they print the directory they use:

```
mvn -pl samples/save-and-load exec:java -Dexec.args=<directory>
```

In IntelliJ IDEA each sample has a run configuration with the name of its
class.

## First steps

| Sample | Question | Prerequisites |
|---|---|---|
| [`getting-started`](getting-started) (`GettingStarted`) | What is the shortest complete path: space, partition, claim and writer, insert, commit, move of the tick, read and release? | None |
| [`partition-configuration`](partition-configuration) (`PartitionConfiguration`) | How do I choose the index, the removal policy and the retained ticks of a partition, and what limits do the coordinates have? | Spaces and partitions |
| [`three-dimensional-space`](three-dimensional-space) (`ThreeDimensionalSpace`) | What changes in a 3D space? | The complete path |

## Writes and ticks

| Sample | Question | Prerequisites |
|---|---|---|
| [`batch-writes`](batch-writes) (`BatchWrites`) | How do I insert, set and remove many entities in one call, and what happens when a batch is rejected? | Writer and commit |
| [`commits-and-ticks`](commits-and-ticks) (`CommitsAndTicks`) | When do readers see a commit? What happens with more commits in a tick, and with writes after the last commit? | Writer and commit |
| [`writer-rollback`](writer-rollback) (`WriterRollback`) | How does a writer discard its writes not committed, also of the ticks before, and how do I check the health of the partition? | Commits and ticks |
| [`write-modes`](write-modes) (`WriteModes`) | When do the writes reach the node, at once, at the commit or with a flush, and what changes between the two write modes? | Commits and ticks, ownership |

## Snapshots

| Sample | Question | Prerequisites |
|---|---|---|
| [`snapshot-queries`](snapshot-queries) (`SnapshotQueries`) | How do I read by ID and query by box, circle and nearest entities? | Acquired snapshot |
| [`snapshot-isolation`](snapshot-isolation) (`SnapshotIsolation`) | Does a snapshot change while the writer publishes new positions? | Commits and ticks |
| [`snapshot-retention`](snapshot-retention) (`SnapshotRetention`) | How do I read past ticks, and what happens when they leave the retention window? | Commits and ticks |
| [`acquisition-timeouts`](acquisition-timeouts) (`AcquisitionTimeouts`) | How does an acquisition end, by release or by timeout? | Acquired snapshot |
| [`multi-partition-queries`](multi-partition-queries) (`MultiPartitionQueries`) | How do I query the snapshots of the same tick on more partitions, also with different indexes, and what happens to a partition without that snapshot? | Snapshot queries, retention window |

## Life of a partition and metadata

| Sample | Question | Prerequisites |
|---|---|---|
| [`ownership-handoff`](ownership-handoff) (`OwnershipHandoff`) | How does a writer pass the ownership to another writer? | Claim and writer |
| [`partition-removal`](partition-removal) (`PartitionRemoval`) | How do I remove a partition with `REQUIRE_EMPTY` or `DISCARD_ENTITIES`, and when does the removal become effective? | Commits and ticks, retention window |
| [`entity-metadata`](entity-metadata) (`EntityMetadata`) | How do I write, read and delete the metadata of the entities? | Writer and commit |
| [`snapshot-decisions`](snapshot-decisions) (`SnapshotDecisions`) | How do I decide the writes on a snapshot, and which writes does the snapshot not see yet? | Entity metadata, commits and ticks |

## Persistence

| Sample | Question | Prerequisites |
|---|---|---|
| [`save-and-load`](save-and-load) (`SaveAndLoad`) | How do I save a partition and load it in a later run? | Commits and ticks, entity metadata |
| [`save-management`](save-management) (`SaveManagement`) | How do I save a past tick, list the saves and delete the older ones? | Retention window |

## Concurrency

| Sample | Question | Prerequisites |
|---|---|---|
| [`concurrent-reader-writer`](concurrent-reader-writer) (`ConcurrentReaderWriter`) | How do a writer and a reader work on two threads? | Commits and ticks |
| [`multi-writer`](multi-writer) (`MultiWriter`) | How do writers of different partitions share the tick of the space? | Commits and ticks |
| [`lagged-reader`](lagged-reader) (`LaggedReader`) | How does a reader draw more frames than ticks, between two past snapshots? | Retention window |
| [`writer-failover`](writer-failover) (`WriterFailover`) | How does a coordinator replace a stuck writer, and how does the new writer know that the tick has no commit yet? | Ownership handoff |
| [`writer-per-tick`](writer-per-tick) (`WriterPerTick`) | How do writers take turns, one per tick, and how does a new writer know that a stuck writer already committed the tick? | Writer failover |
| [`periodic-saves`](periodic-saves) (`PeriodicSaves`) | How do I save on a second thread while the writer goes on? | Save management |

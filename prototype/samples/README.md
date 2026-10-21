# Samples

*[Italiano](README-it.md)*

Small clients that show how to use BADSPACE as an embedded library, with
local nodes. Each sample is a module with one `main` class. The first ones
show one part of the API each; the last ones are small programs for a use
case, with more threads.

Read them in this order: each sample uses only what the ones before it
explain.

## Running a sample

From `prototype`:

```
mvn install -DskipTests
mvn -pl samples/<name> exec:java
```

In IntelliJ IDEA each sample has a run configuration with the name of its
class.

## Parts of the API

1. [`spaces-and-partitions`](spaces-and-partitions) (`SpacesAndPartitions`):
   the generators of the IDs, shared by all the spaces of the process; 2D and
   3D spaces; partitions on local nodes, with the name that the software gives
   them and their configuration (index, removal policy, retained ticks); the limits of the coordinates; the
   operations shared by 2D and 3D.
2. [`writes-and-commits`](writes-and-commits) (`WritesAndCommits`): the
   writer claims a partition and writes through the handle of its token;
   single and batch writes; a batch that fails changes nothing; the commit
   confirms the writes in the current tick and the move of the tick publishes
   them.
3. [`snapshot-reads`](snapshot-reads) (`SnapshotReads`): reads by ID and
   queries (box, circle, nearest entities) on an acquired snapshot, which does
   not see the commits after it.
4. [`ticks-and-retention`](ticks-and-retention) (`TicksAndRetention`): the
   retention window, the snapshots of past ticks, skipped ticks, and why a
   snapshot is not available.
5. [`acquisition-timeouts`](acquisition-timeouts) (`AcquisitionTimeouts`):
   the release of an acquisition and its timeout.
6. [`ownership-handoff`](ownership-handoff) (`OwnershipHandoff`): the
   generations of the ownership, the handoff to another writer, the revocation
   by a coordinator, and the release that discards the writes not committed.
7. [`partition-removal`](partition-removal) (`PartitionRemoval`): the two
   removal policies, the readers after the removal, and the destruction of the
   partition when no snapshot is left.
8. [`entity-metadata`](entity-metadata) (`EntityMetadata`): the application
   metadata of the entities, written through the handle of the writer in the
   same commit as the positions and read by ID on an acquired snapshot; a
   batch that fails changes nothing; conditional writes, like a
   compare-and-set or the reservation of two entities, that change nothing
   and return the false conditions when a condition is false; removing an
   entity deletes its metadata.
9. [`partition-saves`](partition-saves) (`PartitionSaves`): the saves of
   three partitions of a node at two ticks, with the synchronous
   `saveLatest()` and `save(tick)` and the reference they return, with the
   name of the partition and the tick; the files in the save directory of the
   node, one per partition name and tick; what a new node
   on the same directory finds after a restart. Pass a directory as argument
   to keep the saves there.

## Use cases

9. [`random-updater`](random-updater) (`RandomUpdater`): one writer and one
   reader on two threads; the writer moves the entities at random at each
   tick, the reader shows the latest snapshot.
10. [`multi-writer`](multi-writer) (`MultiWriter`): many partitions on one
    node, each with its writer on its own thread, and a tick shared by all of
    them, like the loop of a game server.
11. [`lagged-reader`](lagged-reader) (`LaggedReader`): a reader that draws
    more frames than there are ticks, a little in the past, between the
    snapshots of two retained ticks, like a game client.
12. [`writer-failover`](writer-failover) (`WriterFailover`): a coordinator
    takes the ownership away from a stuck writer and gives it to a new one,
    which goes on from the latest snapshot; the fencing token rejects the old
    writer when it wakes up.

![BADSPACE](img/BADSPACE.jpeg)

[Italiano](README.it.md)

BADSPACE is a project for a real-time database of entities in a 2D or 3D space, designed as a foundation for simulation, monitoring or gaming software. The goal is to track entity positions and answer proximity queries while thousands of entities move at every tick. Scalability is based on dividing the world into partitions that are created and removed dynamically, each with a single writer.

The goal is a flexible tool that developers can adapt to their own needs. The software chooses how to partition entities, where to place each partition and which spatial index to use. BADSPACE provides the operations and guarantees on which to build application logic.

The project is at the prototype stage. The Java [prototype](prototype/) is an embedded library with local nodes, used to validate the design decisions. The [OKF bundle](okf-bundle/index.md) separates the [current implementation](okf-bundle/current/index.md) from [future work](okf-bundle/future/index.md). Current status:

- **Spaces and partitions:** 2D and 3D spaces with independent ticks; each space creates its own local nodes through a factory, and partitions are created and removed on them; spatial index, removal policy and snapshot retention chosen per partition. Entity IDs use a Snowflake-style generator.
- **Writes and reads:** batch entity writes, partition commits and logical ticks. One writer owns a partition through a fencing token; readers acquire stable snapshots of completed ticks. Published snapshots remain available within the configured retention window, and acquired snapshots can outlive partition removal.
- **Metadata:** application metadata for entities, with conditional writes that can update positions and metadata together.
- **Persistence:** synchronous saving of one partition's published snapshot to a node directory, listing and deleting saves, and loading its entities and metadata into a new partition. The software schedules saves and chooses which ones to keep.
- **Queries:** reads by entity ID, range and k-nearest queries on a partition; range and k-nearest aggregation across snapshots of the same tick on selected partitions. The caller chooses the tick and handles unavailable snapshots.

The [prototype TODO](TODO.md) tracks the next blocks, starting with a remote gRPC node. Raycasting, migration between partitions and coordinated recovery are future work. The current save and load mechanism covers one partition at a time; it does not provide recovery of a whole space after a crash.

The [samples](prototype/samples/README.md) show how to use the API, in the order needed to understand it.

> **Note on language:** the documentation is currently in Italian, while the code is in English. English documentation will be written when the project is more mature.

## Setup for coding agents

The skills for coding agents are in `.agents/skills`. Claude Code reads them from `.claude/skills`, which is not in the repository. After you clone the repository, run the setup script from any directory:

- Linux and macOS: `./agents-setup.sh`, which creates a symbolic link.
- Windows: `agents-setup.cmd`, which creates a directory junction and needs no special permissions.

You can run the script again at any time, for example after you move the repository: it only fixes what is missing or wrong.

License: [Apache 2.0](LICENSE).

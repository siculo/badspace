![BADSPACE](img/BADSPACE.jpeg)

[Italiano](README.it.md)

BADSPACE is a project for a real-time database of entities in a 2D or 3D space, designed as a foundation for simulation, monitoring or gaming software. The goal is to track entity positions and answer proximity queries (range, k-nearest, raycasting) while thousands of entities move at every tick. Scalability is based on dividing the world into partitions that are created and removed dynamically, each with a single writer.

The goal is a flexible tool that developers can adapt to their own needs. The design allows for use as a library or as a service, a choice of partitioning and clustering strategies, and APIs on which to build application logic. BADSPACE is aimed at developers looking for the tools to build their own solution.

The project is at the prototype stage: the Java prototype (`prototype/`) is used to validate the design decisions, documented in the [OKF bundle](okf-bundle/index.md). Current status:

- **Done:** embedded library with 2D and 3D spaces, partitions on local nodes, spatial indexes chosen when a partition is created, insert, read, update and removal of entities.
- **In progress:** partition commit, with one writer and many concurrent readers on the same partition.

> **Note on language:** the documentation is currently in Italian, while the code is in English. English documentation will be written when the project is more mature.

## Setup for coding agents

The skills for coding agents are in `.agents/skills`. Claude Code reads them from `.claude/skills`, which is not in the repository. After you clone the repository, run the setup script from any directory:

- Linux and macOS: `./agents-setup.sh`, which creates a symbolic link.
- Windows: `agents-setup.cmd`, which creates a directory junction and needs no special permissions.

You can run the script again at any time, for example after you move the repository: it only fixes what is missing or wrong.

License: [Apache 2.0](LICENSE).

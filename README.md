![BADSPACE](img/BADSPACE.jpeg)

[Italiano](README.it.md)

BADSPACE will be a real-time database for entities in a 2D or 3D space, meant as a base for simulation, monitoring or gaming software. It will track the position of entities and answer proximity queries (range, k-nearest, raycasting) while thousands of entities move at every tick. It will scale by dividing the world into partitions that are created and removed dynamically, each with a single writer.

The goal is a flexible tool that developers will adapt to their own needs, instead of using it as it is. It will be usable as a library or as a service: developers will choose partitioning and clustering strategies, and build their own application logic on top of its APIs. BADSPACE will be designed for people who do not want a ready-made service, but the tools to build their own solution.

The project is at the prototype stage: the Java prototype (`prototype/`) is used to validate the design decisions, documented in the [OKF bundle](okf-bundle/index.md). Current status:

- **Done:** embedded library with 2D and 3D spaces, partitions on local nodes, spatial indexes chosen when a partition is created, insert, read, update and removal of entities.
- **In progress:** partition commit, with one writer and many concurrent readers on the same partition.

> **Note on language:** the documentation is currently in Italian, while the code is in English. English documentation will be written when the project is more mature.

License: [Apache 2.0](LICENSE).

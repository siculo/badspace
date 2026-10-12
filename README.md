![BADSPACE](img/BADSPACE.jpeg)

[Italiano](README.it.md)

BADSPACE will be a real-time database for entities in a 2D or 3D space, meant as a base for simulation, monitoring or gaming software. It will track the position of entities and answer proximity queries (range, k-nearest, raycasting) while thousands of entities move at every tick. It will scale by dividing the world into partitions that are created and removed dynamically, each with a single writer.

The goal is a flexible tool that developers will adapt to their own needs, instead of using it as it is. It will be usable as a library or as a service: developers will choose partitioning and clustering strategies, and build their own application logic on top of its APIs. BADSPACE will be designed for people who do not want a ready-made service, but the tools to build their own solution.

The project is at the prototype stage: the Java prototype (`prototype/`) is used to validate the design decisions, documented in the [OKF bundle](okf-bundle/index.md). Current status:

- **Done:** embedded library with 2D and 3D spaces, partitions on local nodes, spatial indexes chosen when a partition is created, insert, read, update and removal of entities.
- **In progress:** partition commit, with one writer and many concurrent readers on the same partition.

> **Note on language:** the documentation is currently in Italian, while the code is in English. English documentation will be written when the project is more mature.

## Checkout

The repository contains a symbolic link: `.claude/skills` points to `.agents/skills`. On Linux and macOS, git creates it with no extra steps.

On Windows, git creates symbolic links only when `core.symlinks` is `true` and the user can create them (Developer Mode enabled, or a shell run as administrator). Otherwise git writes a plain text file with the target path, and the skills are not found. To clone the repository with symbolic links enabled:

```bash
git clone -c core.symlinks=true git@github.com:siculo/badspace.git
```

If the repository is already cloned, enable the option and restore the link:

```bash
git config core.symlinks true
git checkout -- .claude/skills
```

If you cannot enable Developer Mode or use an administrator shell, you can create a directory junction instead, which needs no special permissions. From `cmd`, in the repository root:

```cmd
del .claude\skills
mklink /J .claude\skills .agents\skills
git update-index --skip-worktree .claude/skills
```

Notes on the junction:

- `del` removes the text file that git wrote in place of the link.
- Windows stores the junction target as an absolute path: if you move the repository, create the junction again.
- Git still expects a text file at `.claude/skills`. The `skip-worktree` flag stops git from showing it as changed, so a `git add -A` does not commit the content of the skills in place of the link. If a future commit changes the link, run `git update-index --no-skip-worktree .claude/skills` before you pull.
- This setup has not been tested yet with Claude Code on Windows.

License: [Apache 2.0](LICENSE).

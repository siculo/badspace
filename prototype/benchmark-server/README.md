# Benchmark server

A standalone service for the Linux benchmark machine. It accepts an inline
benchmark plan and an `origin` branch or tag, fetches the revision, builds the
benchmark jar, and runs the plan in a child process. A web page shows the active
run, progress, logs, history, and files to download. Plans and results do not need
to be committed to Git. The existing benchmark CLI still works.

See the [Italian guide](README-it.md) for the complete setup and API examples.

## Install

Requirements: JDK 25, Maven, Git, and a dedicated clean clone with an `origin`
remote and non-interactive credentials. The service changes the clone's checkout
and leaves it at the measured commit with a detached HEAD. Local changes cause
the run to fail; the service does not delete them.

```sh
mvn -f prototype/pom.xml -pl benchmark-server -am clean package
```

Copy `target/benchmark-server.jar`, `start.sh`, and `server.properties` from this
module into a directory **outside the repository**. Edit the properties, then:

```sh
chmod +x start.sh
./start.sh
```

Open `http://<host>:8080/`. Load or paste a plan, choose a branch or tag, and start
the run. The page refreshes every two seconds. Downloaded HTML reports work
offline. `Ctrl+C` stops the service and its active build or benchmark.

Paths in the configuration are relative to its file. `repository` points to the
clone root containing `prototype/`. `results` must be outside the clone; it
defaults to `results/` next to the configuration. Each run gets its own directory
with `status.json`, `plan.json`, `run.log`, and `results/` for JSON and HTML files.
An optional `token` protects all API endpoints with bearer authentication. This
is an HTTP service for a trusted local network; the token does not encrypt traffic.

## API

| Method and path | Result |
| --- | --- |
| `POST /api/runs` | Accept a request; `202` with run ID and `Location` |
| `GET /api/runs` | History, state, progress and artifacts |
| `GET /api/runs/<id>` | One run, including its request and resolved commit |
| `GET /api/runs/<id>/log` | Last 64 KiB of the log |
| `GET /api/runs/<id>/files/<name>` | An artifact from the returned list |
| `POST /api/runs/<id>/cancel` | Request cancellation; safe to repeat |

Requests use `Content-Type: application/json` and exactly one of `branch` or
`tag`, plus `plan`, an object in the existing benchmark plan format. Branches
select the remote tip at fetch time; tags select their remote commit. The runner
checks the full plan before measuring. Comments and trailing commas are allowed.
Explicit output paths must stay under the relative `results/` directory.

Only one run can be active; another request gets `409`. The request limit is
1 MiB. There is no queue. Runs pass through `ACCEPTED`, `FETCHING`, `BUILDING`,
`RUNNING` and `SUCCEEDED`, or end as `FAILED` or `CANCELLED`. Cancellation stops
child JVMs as well. Partial files remain available. JMH progress applies to its
current invocation; the runner also prints the current run number within the plan.

History survives a restart. Unfinished saved runs become `INTERRUPTED` and are
not resumed. After a forced service kill, check for leftover Maven or benchmark
processes before restarting. Use `Ctrl+C` or `SIGTERM` for a normal shutdown.
Locks prevent two services from using the same clone or results directory.

The service builds only `benchmark` and its dependencies, with `clean package
-DskipTests`. The installed service jar does not change when the measured revision
changes. Older revisions work if they support `run --plan FILE` and the existing
benchmark jar location. To update the service itself, stop it and replace its jar.

## Tests

```sh
mvn -f prototype/pom.xml -pl benchmark-server -am test
```

Tests use real local Git repositories, Maven builds, HTTP requests and child JVMs.

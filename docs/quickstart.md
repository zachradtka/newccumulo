# Accumulo Quickstart

`accumulo quickstart` boots a complete, single-node Accumulo cluster in one
command — ZooKeeper, a manager, a tablet server, a garbage collector, and the
monitor — entirely in-process with no Hadoop install, no configuration files,
and no external services. It is built for local evaluation and development: the
cluster stores its data in a temporary directory that is deleted when you stop
it. This guide is the single source of truth for running the quickstart; you
should not need any other document to go from nothing to a running cluster you
can connect a shell to.

> **Not for production.** The quickstart runs every service in a throwaway
> process tree against an ephemeral data directory and ships with a well-known
> default password. It is a development tool. Do not put data you care about in
> it.

## Contents

- [Native CLI](#native-cli)
- [Docker](#docker)
  - [Standalone container](#standalone-container)
  - [Docker Compose](#docker-compose)
- [Connectivity: advertise host and port mapping](#connectivity-advertise-host-and-port-mapping)
- [Flag and environment-variable reference](#flag-and-environment-variable-reference)
- [Persistence](#persistence)
- [Security caveat](#security-caveat)
- [Troubleshooting](#troubleshooting)

## Native CLI

The native path runs the quickstart directly from an extracted Accumulo
binary tarball. You need a Java 17 runtime on your `PATH`.

**1. Build the binary tarball** (skip this if you already have one):

```bash
mvn package -DskipTests
```

This produces `assemble/target/accumulo-<version>-bin.tar.gz`.

**2. Extract it and enter the directory:**

```bash
tar -xzf assemble/target/accumulo-<version>-bin.tar.gz
cd accumulo-<version>
```

**3. Start the cluster:**

```bash
bin/accumulo quickstart
```

The command runs in the foreground. After a few seconds it prints a ready
banner:

```
==========================================================================
 Apache Accumulo Quickstart - ready

   Instance:       quickstart
   Monitor URL:    http://localhost:9995
   ZooKeeper:      localhost:2181
   Data dir:       /tmp/accumulo-quickstart-<random> (ephemeral)

   Connect with the shell:
     accumulo shell -zh localhost:2181 -zi quickstart -u root -p secret

   Press Ctrl-C to stop.
==========================================================================
```

**4. Connect with the shell** in a second terminal (from the same extracted
directory) and run a basic table round-trip:

```bash
bin/accumulo shell -zh localhost:2181 -zi quickstart -u root -p secret
```

```
root@quickstart> createtable mytable
root@quickstart mytable> insert row1 family qualifier value1
root@quickstart mytable> scan
row1 family:qualifier []    value1
root@quickstart mytable> quit
```

**5. Stop the cluster** by pressing `Ctrl-C` in the first terminal. All
services shut down and the temporary data directory is removed.

## Docker

The Docker image bundles the same quickstart with a Java 17 runtime, so you do
not need to build or extract anything. Pull it from GitHub Container Registry:

```bash
docker pull ghcr.io/zachradtka/newccumulo:latest
```

The image's default command is `quickstart`, so `docker run` with no command
starts the cluster.

### Standalone container

Run a single container and publish the four service ports to your host:

```bash
docker run --rm -it \
  -p 2181:2181 \
  -p 9995:9995 \
  -p 9997:9997 \
  -p 9999:9999 \
  ghcr.io/zachradtka/newccumulo:latest
```

The published ports **must use identical host and container numbers** (`2181:2181`,
not `12181:2181`). See
[Connectivity](#connectivity-advertise-host-and-port-mapping) for why.

Once the ready banner prints, connect a shell. The simplest way is to exec into
the running container:

```bash
docker exec -it $(docker ps -q --filter ancestor=ghcr.io/zachradtka/newccumulo:latest) \
  accumulo shell -u root -p secret
```

```
root@quickstart> createtable mytable
root@quickstart mytable> insert row1 family qualifier value1
root@quickstart mytable> scan
row1 family:qualifier []    value1
root@quickstart mytable> quit
```

If you have a native Accumulo tarball extracted on the host, you can instead
connect to the published ports directly, exactly as in the native CLI section:
`accumulo shell -zh localhost:2181 -zi quickstart -u root -p secret`.

Stop the container with `Ctrl-C` (or `docker stop`); `--rm` removes it.

### Docker Compose

The repository ships a Compose file that runs the cluster and a second
container which connects to it over the Compose network and verifies a
create / insert / scan round-trip:

```bash
docker compose -f assemble/docker/quickstart/docker-compose.yml up
```

The `accumulo` service sets `ACCUMULO_ADVERTISE_HOST` to its own Compose
service name (`accumulo`) so the addresses it registers in ZooKeeper resolve
for any sibling container on the network. The `client` service waits for the
cluster, runs the round-trip, and prints `compose connectivity check PASSED`.

Override the image with the `ACCUMULO_IMAGE` environment variable; it defaults
to `ghcr.io/zachradtka/newccumulo:latest`.

## Connectivity: advertise host and port mapping

Accumulo is a distributed system even when it runs on one node. A client first
contacts ZooKeeper, and ZooKeeper hands back the **addresses** of the manager
and tablet server the client must then connect to. Those addresses have to be
reachable from wherever the client runs — and that is the whole connectivity
story.

**Two properties control this:**

- **Bind address** (`ACCUMULO_BIND_HOST`) — the network interface the services
  listen on inside their host or container.
- **Advertise address** (`ACCUMULO_ADVERTISE_HOST`) — the hostname the
  services register in ZooKeeper for clients to call back on.

**Native CLI.** Neither variable is set. Services bind and advertise loopback,
the client runs on the same host, and everything resolves to `localhost`. There
is nothing to configure.

**Standalone Docker.** The image sets `ACCUMULO_BIND_HOST=0.0.0.0` (so
Docker's port forwarding, which arrives on the container's bridge interface
rather than its loopback, actually reaches the services) and
`ACCUMULO_ADVERTISE_HOST=localhost` (so a client on *your host* gets back
`localhost:<port>` addresses). This is why **standalone Docker requires 1:1
port mapping**: ZooKeeper advertises `localhost:9997` for the tablet server, so
host port `9997` must forward to container port `9997`. Remapping a port
(`-p 19997:9997`) breaks the callback — the client connects to ZooKeeper fine,
then tries the advertised `localhost:9997` and finds nothing there.

**Docker Compose.** The `accumulo` service overrides
`ACCUMULO_ADVERTISE_HOST=accumulo` — its Compose service name. Sibling
containers resolve `accumulo` over the Compose network and reach the cluster at
`accumulo:2181`, `accumulo:9997`, and so on. No host port mapping is needed for
container-to-container traffic (the Compose file publishes `9995` only as a
convenience for viewing the monitor from your browser).

## Flag and environment-variable reference

All flags are passed to `accumulo quickstart` (native) or appended to the
`docker run` command (Docker). The values below match the implementation; the
`--help` output is generated from the same source.

### Flags

| Flag | Default | Description |
| --- | --- | --- |
| `-h`, `--help`, `-?` | — | Print usage and exit. |
| `--port-base <n>` | — | Assign four contiguous ports from a base: ZooKeeper=`n`, manager=`n+1`, tablet server=`n+2`, monitor=`n+3`. Mutually exclusive with the per-service port flags below. |
| `--zk-port <n>` | `2181` | ZooKeeper client port. |
| `--manager-port <n>` | `9999` | Manager client port. |
| `--tserver-port <n>` | `9997` | Tablet server client port. |
| `--monitor-port <n>` | `9995` | Monitor HTTP port. |
| `--tservers <n>` | `1` | Number of tablet servers (must be ≥ 1). |
| `--scan-servers <n>` | `0` | Number of scan servers (must be ≥ 0). Accepted and validated; scan-server JVMs are not started in this release. |
| `--compactors <n>` | `1` | Number of compactors (must be ≥ 1). Accepted and validated; compactor JVMs are not started in this release. |
| `--heap-mb <n>` | `256` | Per-service JVM heap size, in megabytes. |
| `--root-password <pw>` | `secret` | Root user password. Overrides `ACCUMULO_ROOT_PASSWORD`. |
| `--data-dir <path>` | — | **Not yet supported.** Persistent data directory — a Phase 2 feature. Passing it fails fast with a clear error. See [Persistence](#persistence). |

`--port-base` and the per-service port flags (`--zk-port`, `--manager-port`,
`--tserver-port`, `--monitor-port`) cannot be combined; supplying both forms is
an error.

### Environment variables

| Variable | Default (native / Docker) | Description |
| --- | --- | --- |
| `ACCUMULO_ROOT_PASSWORD` | `secret` / `secret` | Root user password. The `--root-password` flag takes precedence over it. An empty value is treated as unset. |
| `ACCUMULO_BIND_HOST` | unset / `0.0.0.0` | RPC bind address (`rpc.bind.addr`). Unset means Accumulo's built-in binding is used. The Docker image sets `0.0.0.0` so forwarded ports reach the services. |
| `ACCUMULO_ADVERTISE_HOST` | unset / `localhost` | RPC advertise address (`rpc.advertise.addr`) — the host clients are told to call back on. Unset means Accumulo's built-in behavior. The Docker image sets `localhost`; Compose overrides it to the service name. |

## Persistence

**Phase 1 (this release) is ephemeral.** Every quickstart run — native or
Docker — stores its data in a fresh temporary directory that is deleted when
the cluster stops. The ready banner tags the data dir line `(ephemeral)` to
make this explicit. Nothing survives a restart.

Persistent storage via a `--data-dir` flag is a **Phase 2** feature and is not
available yet. If you pass `--data-dir`, the quickstart fails fast rather than
misleading you:

```
Persistence via --data-dir is not yet supported in this release. See zachradtka/newccumulo#8 for status.
```

The Docker image already reserves a `/data` volume for this future support, but
in this release that volume is unused and the data directory remains the
container's ephemeral temp space. Track progress in
[issue #8](https://github.com/zachradtka/newccumulo/issues/8).

## Security caveat

**The quickstart is not secured and must not be exposed to untrusted networks.**
It ships with a well-known default root password (`secret`). Anyone who can
reach the cluster has full administrative control of it and all its data.

- On the **native CLI** and a **standalone Docker** container, the cluster
  advertises `localhost` and is only reachable from your own machine — which is
  the intended, safe configuration for local evaluation.
- The moment the cluster advertises a **non-loopback** address (a Compose
  service name, a LAN hostname, an IP) *while still using the default
  password*, it becomes reachable — and fully controllable — by other machines
  and containers. When that happens, the ready banner prints a loud
  `SECURITY WARNING` block.

To silence the warning and actually secure the cluster, set a strong password
on every service via `ACCUMULO_ROOT_PASSWORD` (or `--root-password`) before
exposing it. Even so, treat the quickstart as a development tool, not a
hardened deployment.

## Troubleshooting

**`Port <n> (...) is already in use.`**
Another process (often a previous quickstart that did not exit cleanly) holds
one of the default ports. Free it, or shift the quickstart's ports with
`--port-base 21810` to move all four at once, or with the individual
`--zk-port` / `--manager-port` / `--tserver-port` / `--monitor-port` flags.

**`Persistence via --data-dir is not yet supported ...`**
You passed `--data-dir`. It is a Phase 2 feature; remove the flag. See
[Persistence](#persistence).

**`Quickstart failed to become ready`**
The cluster did not accept connections within the readiness timeout (60s).
This usually means the host is heavily loaded or low on memory. Retry; if it
persists, give the services more memory with `--heap-mb 512`.

**The shell cannot connect / times out.**
Confirm the cluster is still running (the launching terminal should show the
ready banner and no later errors). For **standalone Docker**, the most common
cause is non-1:1 port mapping — every `-p` must map a host port to the *same*
container port. See
[Connectivity](#connectivity-advertise-host-and-port-mapping).

**`Ctrl-C` seems to hang.**
Shutdown stops every child JVM and removes the temp data directory; this takes
a few seconds. If a service is wedged, each child JVM is force-killed after a
short grace period, so the command always exits.

**A Docker container connects but immediately disconnects.**
The client reached a port that forwards into the container, but the address
ZooKeeper advertised does not resolve back to the cluster. Re-check the
advertise host and port mapping for your scenario in
[Connectivity](#connectivity-advertise-host-and-port-mapping).

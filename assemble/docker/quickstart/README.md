<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->
# Accumulo Quickstart image

A complete, single-node [Apache Accumulo](https://accumulo.apache.org) cluster
in one container - ZooKeeper, manager, tablet server, garbage collector, and
monitor - with a bundled Java 17 runtime. Built for local evaluation and
development, **not for production**: it ships with a well-known default password
and is ephemeral unless you opt into persistence with `--data-dir`.

## Run it

```bash
docker run --rm -it \
  -p 2181:2181 \
  -p 9995:9995 \
  -p 9997:9997 \
  -p 9999:9999 \
  ghcr.io/zachradtka/newccumulo:latest
```

The image's default command is `quickstart`, so no command is needed. After a
few seconds the container prints a ready banner with the connection details.

Ports **must be mapped 1:1** (`2181:2181`, not `12181:2181`): the cluster
advertises `localhost:<port>` addresses that clients call back on, so the host
port must match the container port.

## Connect a shell

Exec into the running container and run a table round-trip:

```bash
docker exec -it <container> accumulo shell -u root -p secret
```

```
root@quickstart> createtable mytable
root@quickstart mytable> insert row1 family qualifier value1
root@quickstart mytable> scan
row1 family:qualifier []    value1
```

## Defaults

| Setting | Value |
| --- | --- |
| Instance name | `quickstart` |
| Root user / password | `root` / `secret` |
| ZooKeeper port | `2181` |
| Manager port | `9999` |
| Tablet server port | `9997` |
| Monitor (web UI) | `9995` - `http://localhost:9995` |

## Configuration

Set the root password and silence the security warning that appears once the
cluster is reachable beyond loopback:

```bash
docker run --rm -it -e ACCUMULO_ROOT_PASSWORD=<strong-password> ... \
  ghcr.io/zachradtka/newccumulo:latest
```

Quickstart flags can be appended to the `docker run` command, e.g.
`... ghcr.io/zachradtka/newccumulo:latest quickstart --heap-mb 512`.

Key environment variables: `ACCUMULO_ROOT_PASSWORD`, `ACCUMULO_BIND_HOST`,
`ACCUMULO_ADVERTISE_HOST`.

## Multi-container with Docker Compose

The repository's
[`docker-compose.yml`](https://github.com/zachradtka/newccumulo/blob/main/assemble/docker/quickstart/docker-compose.yml)
runs the cluster plus a client container that connects over the Compose
network and verifies a create / insert / scan round-trip.

## Persistence

By default the cluster is **ephemeral** - its data lives in a temporary
directory and is discarded when the container stops. To persist data across
restarts, point `--data-dir` at the reserved `/data` volume and mount it from
the host:

```bash
docker run --rm -it \
  -p 2181:2181 -p 9995:9995 -p 9997:9997 -p 9999:9999 \
  -v "$PWD/accumulo-data:/data" \
  ghcr.io/zachradtka/newccumulo:latest quickstart --data-dir /data
```

The first run initializes the directory; later runs against the same path
resume from it, preserving tables, data, and user accounts. A resumed cluster
recovers its prior state automatically, including after an unclean shutdown.

See the [persistence section of the quickstart guide](https://github.com/zachradtka/newccumulo/blob/main/docs/quickstart.md#persistence)
for the data-dir refusal rules (version mismatch, foreign or corrupt markers)
and recovery details.

## Full documentation

See the canonical
[quickstart guide](https://github.com/zachradtka/newccumulo/blob/main/docs/quickstart.md)
for the native CLI workflow, the full flag and environment-variable reference,
the connectivity model, and troubleshooting.

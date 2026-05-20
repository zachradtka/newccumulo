#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

FROM eclipse-temurin:17-jre-jammy

# tini handles signal forwarding and zombie reaping for MAC's spawned child JVMs.
# gosu drops root cleanly after the entrypoint chowns mounted volumes.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        tini \
        gosu \
        curl \
        ca-certificates; \
    rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 1000 accumulo \
 && useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash accumulo

# The build context is assemble/target/ (the Maven profile points here). The
# tarball is copied in and extracted under /opt/accumulo; the top-level dir
# inside the tarball is collapsed via --strip-components=1 so paths stay short
# regardless of the snapshot/release version suffix.
ARG ACCUMULO_TARBALL=accumulo-bin.tar.gz
COPY ${ACCUMULO_TARBALL} /tmp/accumulo-bin.tar.gz
RUN set -eux; \
    mkdir -p /opt/accumulo; \
    tar -xzf /tmp/accumulo-bin.tar.gz -C /opt/accumulo --strip-components=1; \
    rm /tmp/accumulo-bin.tar.gz; \
    chown -R accumulo:accumulo /opt/accumulo

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# /data is reserved for Phase 2 persistence support. Created and chowned now so
# the volume permissions handshake in entrypoint.sh has a stable target when
# callers mount a host directory. Phase 1's data dir remains the JVM's
# ephemeral /tmp.
RUN mkdir -p /data && chown accumulo:accumulo /data
VOLUME ["/data"]

# Bind to the wildcard so Docker's -p port-forwarded traffic (which DNATs from
# the host port to the container's bridge interface, not its loopback) actually
# reaches our services. ACCUMULO_BIND_HOST drives BOTH rpc.bind.addr on
# Accumulo's services AND clientPortAddress on MAC's embedded ZooKeeper -
# without the ZK override, ZK would still listen on loopback and a host-side
# client could TCP-connect through docker-proxy only to be disconnected on the
# inner forward.
#
# Container network-namespace isolation on bridge mode already prevents
# accidental exposure to the host network, so binding loopback-only would just
# break the port-forwarding use case without adding security.
#
# rpc.advertise.addr=localhost so when host clients ask ZK where to find a
# tserver, they get back an address that maps through -p back into the
# container. Compose users override to their service name on the bridge net.
ENV ACCUMULO_BIND_HOST=0.0.0.0 \
    ACCUMULO_ADVERTISE_HOST=localhost \
    PATH=/opt/accumulo/bin:${PATH}

EXPOSE 2181 9995 9997 9999

ENTRYPOINT ["tini", "--", "/entrypoint.sh"]
CMD ["quickstart"]

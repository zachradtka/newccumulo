# Separate gateway tier; gateway performance ceiling accepted

Status: accepted

## Context

Accumulo's native Java client looks up tablet locations and connects directly
to the tablet servers holding the data, in parallel — the same direct-to-data
model as Cassandra and Redis native drivers. The gateway (ADR 0001) breaks
this: all scan and write traffic flows client → gateway → tablet servers and
back, and gateway instances are pure proxies with no data locality.

## Decision

The gateway runs as a **separate, horizontally-scaled tier**, network-close to
the cluster — not co-located on tablet-server nodes.

The gateway's **performance ceiling is accepted and documented**: it will not
match the native Java client's throughput or latency. The gateway exists for
reach and approachability. Latency-critical and highest-throughput workloads
should continue to use the Java client directly.

## Considered Options

- **Co-locate a gateway on each tablet-server node** — appears data-local but
  is not: the load balancer routes by connection, not by data location, so a
  request still lands on an arbitrary gateway. It yields no real locality and
  the gateway competes with the tablet server for CPU and memory. Rejected as a
  false optimization.

## Consequences

- The gateway tier scales independently of the Accumulo cluster.
- One extra in-datacenter network hop on every data-plane operation; this is
  the inherent, non-removable cost of the gateway model.
- Product documentation must state the perf ceiling honestly so adopting orgs
  choose the gateway for the right reasons.

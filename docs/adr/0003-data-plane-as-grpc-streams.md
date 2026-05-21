# Data-plane operations are gRPC streams; gateway instances are stateless

Status: accepted

## Context

A production deployment runs multiple gateway instances behind a load balancer.
Accumulo's `BatchWriter` buffers mutations and flushes asynchronously — its
performance value comes from batching across calls — so a writer interaction is
inherently stateful and that state must live somewhere.

## Decision

Both data-plane operations are modelled as gRPC streams, symmetrically:

- **Scans** are server-streaming.
- **Batch writes** are client-streaming (bidirectional, so the gateway can
  report flush errors back).

The gateway holds the per-interaction state (scan cursor, `BatchWriter` buffer)
for the lifetime of the stream and no longer. A single HTTP/2 stream cannot
migrate between instances, so the stream pins itself to one gateway instance
for free. Gateway instances are otherwise stateless and scale horizontally
behind a plain load balancer — no session affinity config, no session registry.

## Considered Options

- **Stateless unary write RPCs** — each call opens, writes, flushes, closes a
  `BatchWriter`. Rejected: discards batching, so every call pays a full flush.
- **Named writer sessions + sticky load balancing** — preserves batching but
  requires session-affinity config, session IDs, server-side timeouts, and
  orphan cleanup. Rejected as unnecessary operational weight.

## Consequences

- A client doing trickle writes holds an open stream for a long time. HTTP/2
  streams are cheap, but the gateway needs idle timeouts and the client library
  must transparently reconnect.
- The unit of statefulness is the stream, not a named server-side object.

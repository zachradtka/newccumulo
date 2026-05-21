# Error model: coarse gRPC status code plus structured Accumulo detail

Status: accepted

## Context

Accumulo's Java API throws a rich exception hierarchy (`TableNotFoundException`,
`TableExistsException`, `AccumuloSecurityException` with a `SecurityErrorCode`,
`MutationsRejectedException` carrying per-mutation constraint violations and
authorization failures, and more). gRPC offers ~16 coarse status codes plus
optional structured error detail. Idiomatic client libraries must be able to
re-raise precise, typed native exceptions.

## Decision

Errors carry two layers:

- The **coarse gRPC status code** — for interop, generic tooling, and the
  retryable-vs-not signal (`UNAVAILABLE` vs `INVALID_ARGUMENT`).
- A **structured error-detail proto** — an Accumulo-specific error enum plus
  operation-specific fields. Each client library inspects the detail and
  re-raises a precise native exception.

The error enum and detail proto are part of the public `.proto` contract; the
conformance suite asserts on them.

Because writes are a client-stream (ADR 0003), `MutationsRejectedException` is
modelled as **partial failure within an open write stream** — the gateway
reports which mutations were rejected and why over the bidirectional stream,
without terminating the stream.

## Considered Options

- **gRPC status codes only** — rejected as lossy: distinct Accumulo errors
  collapse onto six codes and `MutationsRejectedException`'s payload is lost.
- **Tunnel a serialized Java exception** — rejected: recouples every client to
  Java, defeating the purpose of the effort.

## Consequences

- The error enum is a compatibility-bearing part of the API contract.
- Client libraries each maintain a native exception hierarchy mapped from the
  detail proto.

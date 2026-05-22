# Client library architecture: idiomatic wrappers, conformance-gated

Status: accepted

## Context

The goal is genuinely developer-friendly clients in multiple languages
(Python first, then JavaScript/TypeScript, then Go). Raw generated gRPC stubs
are not friendly. Hand-written idiomatic clients are friendly but risk drifting
apart as the `.proto` contract evolves — the failure mode that left Cassandra's
and Redis's multi-language clients inconsistent.

## Decision

Each client library is a **thin, idiomatic, hand-written wrapper over generated
gRPC stubs**. The mechanical layer is generated; the idiomatic layer is kept
deliberately thin so it has little room to diverge.

Correctness is defined by a **shared, language-agnostic conformance suite** —
behavioral scenarios run against a real gateway. A client library is not "done"
until it passes the suite; passing it is a gating CI requirement.

The `.proto` files, the gateway, all client libraries, and the conformance
suite live **together in the fork's monorepo** — single source of truth, atomic
cross-cutting changes, no cross-repo version dance.

**Python** is the first and reference language; the conformance suite is
written alongside it, so subsequent clients are a fill-in-the-blanks exercise
against an existing definition of correct.

## Considered Options

- **Raw generated stubs only** — rejected: not developer-friendly.
- **Discipline only, no conformance suite** — rejected: reliably fails at two
  or more clients (the Cassandra/Redis drift outcome).

## Consequences

- Every new client language is a real library-design effort, but bounded by the
  conformance suite.
- A short cross-language design-guidelines doc may be added once 2+ clients
  exist, to keep idioms consistent.

# Credential-passthrough trust model for the gateway

Status: accepted

## Context

The gateway sits between non-Java clients and Accumulo, so it is a trust
boundary. Accumulo enforces cell-level security: a scan carries an
`Authorizations` set, and a mutation carries a `ColumnVisibility`. Whatever sits
in front of Accumulo must guarantee a client cannot scan with authorizations it
is not entitled to — otherwise cell-level visibility security is bypassed
entirely.

## Decision

The gateway uses **credential passthrough**. A client presents real Accumulo
credentials; the gateway authenticates to Accumulo *as that user*. Accumulo's
own permission checks and per-user max-authorizations enforcement do all the
work. The gateway never makes a security decision — it is a non-deciding
translator. TLS is mandatory on the gRPC channel because credentials cross it.

## Considered Options

- **Gateway as a single service principal**, mapping client identity to allowed
  authorizations itself. Rejected: the gateway would reimplement Accumulo's
  security model — a permanent, high-stakes liability and a likely source of
  CVEs.
- **Hybrid** — gateway authenticates clients with its own mechanism (mTLS,
  OIDC) but still impersonates per-user into Accumulo. Not chosen now; the
  client-auth mechanism can be revisited later without changing the principle
  that Accumulo enforces authz.

## Consequences

- Every client needs real Accumulo credentials.
- The gateway inherits Accumulo's auth mechanisms; Kerberos delegation through
  the gateway is awkward and may constrain early deployments to password
  tokens.
- Accumulo remains the single source of truth for authentication and
  authorization.

# gRPC gateway for multi-language clients

Status: accepted

## Context

Accumulo only ships a Java client API. There is no supported way to build
client applications in Python, JavaScript/TypeScript, Go, or other languages.
The 1.x Thrift proxy that once enabled this was removed in 2.x. The internal
Thrift RPC protocols (`tabletserver.thrift`, `manager.thrift`, etc.) are
version-coupled and carry no compatibility guarantee, so they cannot serve as a
public client contract.

## Decision

We will add multi-language client support via a **gateway process** that
embeds the existing, already-stable Java client API and exposes it over
**gRPC**. Non-Java clients are thin generated stubs that talk to the gateway.

The existing Java client is **unchanged and remains native** — it continues to
connect directly to ZooKeeper and the tablet servers, and stays the fastest,
direct path. The gateway is **purely additive**: a new process for non-Java
clients only, which itself embeds the same native Java client internally. Java
developers are unaffected by this effort.

## Considered Options

- **Native-protocol drivers** (the Cassandra/Redis model): each language
  re-implements a public wire protocol directly, no extra process. Rejected:
  Accumulo has no stable public wire protocol, and defining, freezing, and
  forever maintaining one — including tablet location lookup and SASL/Kerberos
  negotiation — is a multi-year commitment of permanent surface area.
- **Thrift gateway** (the Accumulo 1.x / HBase model): proven precedent, and
  Accumulo already has in-house Thrift build plumbing. Rejected as transport
  because Thrift's JavaScript/TypeScript bindings are weak and the project is in
  maintenance mode (slow releases, original corporate sponsor departed).
- **REST/JSON gateway** (the HBase Stargate model): most universally
  approachable. Rejected as the primary transport because Accumulo keys and
  values are raw byte arrays — REST forces base64 encoding on every field — and
  scans are inherently streaming, which REST handles awkwardly.

## Consequences

- The gateway is an extra process to deploy, run, and secure — it becomes a
  trust boundary between clients and Accumulo.
- gRPC handles binary keys/values natively and maps server-streaming directly
  onto scans.
- The gRPC `.proto` files become a public, versioned API surface that must be
  maintained with backward-compatibility discipline.
- A REST/JSON surface is not precluded; it could be added later as a thin
  secondary transport for browser and quick-script use.

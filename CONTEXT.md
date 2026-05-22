# Context

Glossary of domain terms for Apache Accumulo. Definitions only — no implementation
details.

## Terms

### Client-side development

Building applications that connect to Accumulo from a separate process to read,
write, scan, and administer data. Portable across languages given a network
gateway. This is the scope of the multi-language client effort.

### Server-side development

Extending Accumulo with code that runs *inside* the tablet server JVM on the
data path — iterators, constraints, balancers, custom compaction logic. Bound to
the JVM by architecture. Explicitly **out of scope** for the multi-language
client effort.

### Gateway

A standalone server process that embeds the Java `AccumuloClient` and exposes a
subset of its capabilities over gRPC. Non-Java clients are thin generated stubs
that talk to the gateway. It is a trust boundary between clients and Accumulo.

### Data plane

The gateway operations a client app uses to move data: scanning, batch
scanning, and batch mutation/deletion. Distinguished from control-plane
operations (table lifecycle, security administration). Phase 1 of the gateway
covers the data plane plus minimal table lifecycle.

### Client library

A per-language, idiomatic, hand-written wrapper over generated gRPC stubs that
developers use to talk to the gateway. Distinct from the raw generated stubs.
Python is the first and reference client library.

### Conformance suite

A shared, language-agnostic set of behavioral test scenarios run against a real
gateway. It is the executable definition of "a correct Accumulo client
library"; every client library must pass it.
